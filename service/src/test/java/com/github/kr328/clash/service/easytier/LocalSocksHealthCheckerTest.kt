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
 * `nested = true` 时模拟"隧道对端也是一个 SOCKS5 服务"（二级握手）。
 */
class LocalSocksHealthCheckerTest {
    private val checker = LocalSocksHealthChecker(connectTimeoutMs = 2000, readTimeoutMs = 2000)

    private class FakeSocksServer(
        private val connectReply: Int,
        private val requireAuth: Boolean,
        private val authOk: Boolean,
        private val nested: Boolean = false,
        private val nestedRequireAuth: Boolean = false,
        private val nestedAuthOk: Boolean = true,
    ) {
        lateinit var socket: ServerSocket
        fun start() {
            socket = ServerSocket(0, 2, java.net.InetAddress.getByName("127.0.0.1"))
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
                if (!handshake(input, output, requireAuth, authOk, connectReply)) return
                if (nested) {
                    handshake(input, output, nestedRequireAuth, nestedAuthOk, 0x00)
                }
                try {
                    while (input.read() != -1) { /* drain */ }
                } catch (e: Exception) { }
            }
        }

        /** 一次完整 SOCKS5 会话：greeting(+auth) + CONNECT + reply。返回 false 表示认证被拒。 */
        private fun handshake(
            input: InputStream,
            output: OutputStream,
            requireAuth: Boolean,
            authOk: Boolean,
            reply: Int,
        ): Boolean {
            input.read() // ver
            val n = input.read()
            repeat(n) { input.read() }
            output.write(if (requireAuth) byteArrayOf(0x05, 0x02) else byteArrayOf(0x05, 0x00))
            output.flush()
            if (requireAuth) {
                val av = input.read()
                val ul = input.read()
                repeat(ul) { input.read() }
                val pl = input.read()
                repeat(pl) { input.read() }
                val status: Byte = if (av == 0x01 && authOk) 0x00 else 0x01
                output.write(byteArrayOf(0x01, status))
                output.flush()
                if (status != 0x00.toByte()) return false
            }
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
                else -> return false
            }
            output.write(byteArrayOf(0x05, reply.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            return true
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
        val f = FakeSocksServer(connectReply = 0x00, requireAuth = false, authOk = true)
            .also { it.start(); fake = it }
        val result = checker.checkLocal(port = f.socket.localPort)
        assertTrue(result is LocalSocksHealthChecker.CheckResult.Ok)
        assertEquals(LocalSocksHealthChecker.Stage.GREETING, result.stage)
    }

    @Test
    fun `checkLocal fails at tcp_connect when port closed`() = runBlocking {
        val dead = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port = dead.localPort
        dead.close()
        val result = checker.checkLocal(port = port)
        assertTrue(result is LocalSocksHealthChecker.CheckResult.Failed)
        assertEquals(LocalSocksHealthChecker.Stage.TCP_CONNECT, result.stage)
    }

    @Test
    fun `checkThrough succeeds and reports connect_remote stage`() = runBlocking {
        val f = FakeSocksServer(connectReply = 0x00, requireAuth = false, authOk = true)
            .also { it.start(); fake = it }
        val result = checker.checkThrough(
            viaHost = "127.0.0.1", viaPort = f.socket.localPort,
            targetHost = "10.126.126.10", targetPort = 1080,
        )
        assertTrue(result.toString(), result is LocalSocksHealthChecker.CheckResult.Ok)
        assertEquals(LocalSocksHealthChecker.Stage.CONNECT_REMOTE, result.stage)
    }

    @Test
    fun `checkThrough maps reply 0x05 to connection refused detail`() = runBlocking {
        val f = FakeSocksServer(connectReply = 0x05, requireAuth = false, authOk = true)
            .also { it.start(); fake = it }
        val result = checker.checkThrough(
            viaHost = "127.0.0.1", viaPort = f.socket.localPort,
            targetHost = "10.126.126.10", targetPort = 1080,
        )
        assertTrue(result is LocalSocksHealthChecker.CheckResult.Failed)
        assertEquals(LocalSocksHealthChecker.Stage.CONNECT_REMOTE, result.stage)
        assertTrue((result as LocalSocksHealthChecker.CheckResult.Failed).detail.contains("refused"))
    }

    @Test
    fun `checkThrough reports local auth required`() = runBlocking {
        val f = FakeSocksServer(connectReply = 0x00, requireAuth = true, authOk = true)
            .also { it.start(); fake = it }
        val result = checker.checkThrough(
            viaHost = "127.0.0.1", viaPort = f.socket.localPort,
            targetHost = "10.126.126.10", targetPort = 1080,
        )
        assertTrue(result is LocalSocksHealthChecker.CheckResult.Failed)
        assertEquals(LocalSocksHealthChecker.Stage.AUTH, result.stage)
    }

    @Test
    fun `checkRemoteThrough succeeds through nested socks server`() = runBlocking {
        val f = FakeSocksServer(
            connectReply = 0x00, requireAuth = false, authOk = true,
            nested = true, nestedRequireAuth = true, nestedAuthOk = true,
        ).also { it.start(); fake = it }
        val result = checker.checkRemoteThrough(
            viaHost = "127.0.0.1", viaPort = f.socket.localPort,
            targetHost = "10.126.126.10", targetPort = 1080,
            username = "u", password = "p",
        )
        assertTrue(result.toString(), result is LocalSocksHealthChecker.CheckResult.Ok)
        assertEquals(LocalSocksHealthChecker.Stage.REMOTE_CONNECT, result.stage)
    }

    @Test
    fun `checkRemoteThrough reports remote auth failure`() = runBlocking {
        val f = FakeSocksServer(
            connectReply = 0x00, requireAuth = false, authOk = true,
            nested = true, nestedRequireAuth = true, nestedAuthOk = false,
        ).also { it.start(); fake = it }
        val result = checker.checkRemoteThrough(
            viaHost = "127.0.0.1", viaPort = f.socket.localPort,
            targetHost = "10.126.126.10", targetPort = 1080,
            username = "u", password = "wrong",
        )
        assertTrue(result is LocalSocksHealthChecker.CheckResult.Failed)
        assertEquals(LocalSocksHealthChecker.Stage.REMOTE_AUTH, result.stage)
        assertTrue((result as LocalSocksHealthChecker.CheckResult.Failed).detail.contains("auth"))
    }

    @Test
    fun `checkRemoteThrough reports tunnel connect failure before remote handshake`() = runBlocking {
        val f = FakeSocksServer(connectReply = 0x04, requireAuth = false, authOk = true, nested = true)
            .also { it.start(); fake = it }
        val result = checker.checkRemoteThrough(
            viaHost = "127.0.0.1", viaPort = f.socket.localPort,
            targetHost = "10.126.126.10", targetPort = 1080,
            username = "u", password = "p",
        )
        assertTrue(result is LocalSocksHealthChecker.CheckResult.Failed)
        assertEquals(LocalSocksHealthChecker.Stage.CONNECT_REMOTE, result.stage)
    }
}
