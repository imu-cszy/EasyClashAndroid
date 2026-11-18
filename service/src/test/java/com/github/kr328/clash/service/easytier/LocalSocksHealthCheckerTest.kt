package com.github.kr328.clash.service.easytier

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 用内嵌伪 SOCKS5 服务器验证 LocalSocksHealthChecker 的分层协议实现。
 */
class LocalSocksHealthCheckerTest {
    private val checker = LocalSocksHealthChecker(connectTimeoutMs = 2000, readTimeoutMs = 2000)

    /** connectReply：服务器对 CONNECT 的应答码；requireAuth/authOk 控制认证路径 */
    private class FakeSocksServer(private val connectReply: Int, private val requireAuth: Boolean, private val authOk: Boolean) {
        lateinit var socket: ServerSocket
        fun start() {
            socket = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (e: Exception) {
                        return@thread
                    }
                    thread(isDaemon = true) { serve(client) }
                }
            }
        }

        private fun serve(client: Socket) {
            client.use { c ->
                val input: InputStream = c.getInputStream()
                val output: OutputStream = c.getOutputStream()
                // greeting: 05 NMETHODS METHODS...
                input.read() // ver
                val n = input.read()
                repeat(n) { input.read() }
                output.write(if (requireAuth) byteArrayOf(0x05, 0x02) else byteArrayOf(0x05, 0x00))
                output.flush()
                if (requireAuth) {
                    // RFC1929: VER(01) ULEN UNAME PLEN PASSWD
                    val av = input.read()
                    val ul = input.read()
                    repeat(ul) { input.read() }
                    val pl = input.read()
                    repeat(pl) { input.read() }
                    val status: Byte = if (av == 0x01 && authOk) 0x00 else 0x01
                    output.write(byteArrayOf(0x01, status))
                    output.flush()
                    if (status != 0x00.toByte()) return
                }
                // CONNECT: VER CMD RSV ATYP ...
                input.read() // ver
                input.read() // cmd
                input.read() // rsv
                when (val atyp = input.read()) {
                    0x01 -> repeat(4 + 2) { input.read() }
                    0x03 -> {
                        val len = input.read()
                        repeat(len + 2) { input.read() }
                    }
                    0x04 -> repeat(16 + 2) { input.read() }
                    else -> return
                }
                output.write(byteArrayOf(0x05, connectReply.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                // 保持连接到客户端断开
                try {
                    while (input.read() != -1) { /* drain */ }
                } catch (e: Exception) { }
            }
        }

        fun close() {
            runCatching { socket.close() }
        }
    }

    private var fake: FakeSocksServer? = null

    @After
    fun tearDown() {
        fake?.close()
    }

    @Test
    fun `checkLocal succeeds greeting against no-auth server`() = runBlocking {
        val f = FakeSocksServer(connectReply = 0x00, requireAuth = false, authOk = true).also { it.start(); fake = it }
        val result = checker.checkLocal(port = f.socket.localPort)
        assertTrue(result is LocalSocksHealthChecker.CheckResult.Ok)
        assertEquals(LocalSocksHealthChecker.Stage.GREETING, (result as LocalSocksHealthChecker.CheckResult.Ok).stage)
    }

    @Test
    fun `checkLocal fails at tcp_connect when port closed`() = runBlocking {
        val dead = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port = dead.localPort
        dead.close()
        val result = checker.checkLocal(port = port)
        assertTrue(result is LocalSocksHealthChecker.CheckResult.Failed)
        assertEquals(LocalSocksHealthChecker.Stage.TCP_CONNECT, (result as LocalSocksHealthChecker.CheckResult.Failed).stage)
    }

    @Test
    fun `checkThrough succeeds and reports connect_remote stage`() = runBlocking {
        val f = FakeSocksServer(connectReply = 0x00, requireAuth = false, authOk = true).also { it.start(); fake = it }
        val result = checker.checkThrough(
            viaHost = "127.0.0.1", viaPort = f.socket.localPort,
            targetHost = "10.126.126.10", targetPort = 1080,
        )
        assertTrue(result.toString(), result is LocalSocksHealthChecker.CheckResult.Ok)
        assertEquals(LocalSocksHealthChecker.Stage.CONNECT_REMOTE, (result as LocalSocksHealthChecker.CheckResult.Ok).stage)
    }

    @Test
    fun `checkThrough maps reply 0x05 to connection refused detail`() = runBlocking {
        val f = FakeSocksServer(connectReply = 0x05, requireAuth = false, authOk = true).also { it.start(); fake = it }
        val result = checker.checkThrough(
            viaHost = "127.0.0.1", viaPort = f.socket.localPort,
            targetHost = "10.126.126.10", targetPort = 1080,
        )
        assertTrue(result is LocalSocksHealthChecker.CheckResult.Failed)
        result as LocalSocksHealthChecker.CheckResult.Failed
        assertEquals(LocalSocksHealthChecker.Stage.CONNECT_REMOTE, result.stage)
        assertTrue(result.detail.contains("refused"))
    }

    @Test
    fun `checkThrough surfaces auth rejection`() = runBlocking {
        val f = FakeSocksServer(connectReply = 0x00, requireAuth = true, authOk = false).also { it.start(); fake = it }
        val result = checker.checkThrough(
            viaHost = "127.0.0.1", viaPort = f.socket.localPort,
            targetHost = "10.126.126.10", targetPort = 1080,
            username = "u", password = "bad",
        )
        assertTrue(result is LocalSocksHealthChecker.CheckResult.Failed)
        assertTrue((result as LocalSocksHealthChecker.CheckResult.Failed).detail.contains("auth"))
    }
}
