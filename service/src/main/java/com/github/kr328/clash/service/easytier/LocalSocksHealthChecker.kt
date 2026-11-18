package com.github.kr328.clash.service.easytier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 本地 SOCKS5 分层健康检查器（RFC 1928 字节流实现，禁用 java.net.Proxy 以获得可分级的错误）。
 *
 * 分层（PROJECT_BRIEF §5 LocalSocksHealthChecker）：
 *  1. TCP_CONNECT  127.0.0.1:11080 是否监听；
 *  2. GREETING     SOCKS5 方法协商（支持 00 无认证 / 02 用户密码）；
 *  3. AUTH         用户名密码认证（如需）；
 *  4. CONNECT_REMOTE 经本地 SOCKS5 CONNECT 到 Lightsail 1080/1081。
 */
class LocalSocksHealthChecker(
    private val connectTimeoutMs: Int = 3000,
    private val readTimeoutMs: Int = 5000,
) {
    enum class Stage { TCP_CONNECT, GREETING, AUTH, CONNECT_REMOTE }

    sealed class CheckResult {
        abstract val stage: Stage
        abstract val elapsedMs: Long

        data class Ok(override val stage: Stage, override val elapsedMs: Long) : CheckResult()
        data class Failed(
            override val stage: Stage,
            override val elapsedMs: Long,
            val detail: String,
        ) : CheckResult()
    }

    /**
     * 检查本地 EasyTier SOCKS5（1、2 级）。
     */
    suspend fun checkLocal(host: String = "127.0.0.1", port: Int): CheckResult =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.soTimeout = readTimeoutMs
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                greet(input, output)
                CheckResult.Ok(Stage.GREETING, System.currentTimeMillis() - start)
            } catch (e: Exception) {
                failure(start, socket, e)
            } finally {
                runCatching { socket?.close() }
            }
        }

    /**
     * 经本地 SOCKS5 对远端执行 CONNECT（3/4 级，即 Overlay 应用层连通性验收）。
     *
     * @param viaHost/viaPort 本地 EasyTier SOCKS5（如 127.0.0.1:11080）
     * @param targetHost/targetPort 远端目标（如 10.126.126.10:1080）
     */
    suspend fun checkThrough(
        viaHost: String,
        viaPort: Int,
        targetHost: String,
        targetPort: Int,
        username: String? = null,
        password: String? = null,
    ): CheckResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(viaHost, viaPort), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val method = greet(input, output, username != null)
            if (method == 0x02.toByte()) {
                require(!(username == null || password == null)) { "server requires username/password auth" }
                authenticate(input, output, username, password)
            }

            connectRemote(input, output, targetHost, targetPort)
            CheckResult.Ok(Stage.CONNECT_REMOTE, System.currentTimeMillis() - start)
        } catch (e: Exception) {
            failure(start, socket, e)
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun failure(start: Long, socket: Socket?, e: Exception): CheckResult.Failed {
        val connected = socket?.isConnected == true
        val stage = if (!connected) Stage.TCP_CONNECT else Stage.CONNECT_REMOTE
        return CheckResult.Failed(
            stage = stage,
            elapsedMs = System.currentTimeMillis() - start,
            detail = describe(e),
        )
    }

    /** 返回服务器选定的认证方法字节（0x00 无认证 / 0x02 用户密码） */
    @Throws(IOException::class)
    private fun greet(input: InputStream, output: OutputStream, withAuth: Boolean = false): Byte {
        // 支持 无认证(00) / 用户密码(02)
        val methods = if (withAuth) byteArrayOf(0x05, 0x02, 0x00, 0x02) else byteArrayOf(0x05, 0x01, 0x00)
        output.write(methods)
        output.flush()
        val ver = input.read()
        val method = input.read()
        if (ver != 0x05 || method < 0) {
            throw IOException("bad SOCKS5 greeting reply: ver=$ver method=$method")
        }
        if (method == 0xFF) {
            throw IOException("no acceptable SOCKS5 auth method (server rejected 00/02)")
        }
        return method.toByte()
    }

    /** RFC 1929 用户名密码认证（0x01 版本） */
    @Throws(IOException::class)
    private fun authenticate(input: InputStream, output: OutputStream, username: String, password: String) {
        val u = username.toByteArray(Charsets.UTF_8)
        val p = password.toByteArray(Charsets.UTF_8)
        require(u.size in 1..255 && p.size in 0..255) { "username/password length out of RFC 1929 range" }
        val req = ByteArray(3 + u.size + p.size)
        req[0] = 0x01
        req[1] = u.size.toByte()
        u.copyInto(req, 2)
        req[2 + u.size] = p.size.toByte()
        p.copyInto(req, 3 + u.size)
        output.write(req)
        output.flush()
        val ver = input.read()
        val status = input.read()
        if (ver != 0x01 || status != 0x00) {
            throw IOException("SOCKS5 username/password auth rejected (ver=$ver status=$status)")
        }
    }

    /** DOMAIN 型 CONNECT；返回绑定地址可忽略，reply code 非 00 抛带码错误 */
    @Throws(IOException::class)
    private fun connectRemote(input: InputStream, output: OutputStream, host: String, port: Int) {
        val h = host.toByteArray(Charsets.US_ASCII)
        require(h.isNotEmpty() && h.size <= 255) { "target host too long for SOCKS5 DOMAIN" }
        val req = ByteArray(7 + h.size)
        req[0] = 0x05 // VER
        req[1] = 0x01 // CMD: CONNECT
        req[2] = 0x00 // RSV
        req[3] = 0x03 // ATYP: DOMAIN
        req[4] = h.size.toByte()
        h.copyInto(req, 5)
        req[5 + h.size] = (port ushr 8).toByte()
        req[6 + h.size] = (port and 0xFF).toByte()
        output.write(req)
        output.flush()

        val ver = input.read()
        val rep = input.read()
        if (ver != 0x05) throw IOException("bad SOCKS5 CONNECT reply: ver=$ver")
        if (rep != 0x00) throw IOException(replyMessage(rep))
        input.read() // RSV
        // 跳过 BND.ADDR/BND.PORT（ATYP 决定长度）
        when (val atyp = input.read()) {
            0x01 -> skip(input, 4 + 2)
            0x03 -> {
                val len = input.read()
                skip(input, len + 2)
            }
            0x04 -> skip(input, 16 + 2)
            else -> throw IOException("bad BND.ATYP: $atyp")
        }
    }

    @Throws(IOException::class)
    private fun skip(input: InputStream, n: Int) {
        var remaining = n
        val buf = ByteArray(64)
        while (remaining > 0) {
            val r = input.read(buf, 0, minOf(remaining, buf.size))
            if (r < 0) throw IOException("EOF while reading SOCKS5 reply")
            remaining -= r
        }
    }

    private fun replyMessage(rep: Int): String = when (rep) {
        0x01 -> "general SOCKS server failure"
        0x02 -> "connection not allowed by ruleset"
        0x03 -> "network unreachable"
        0x04 -> "host unreachable"
        0x05 -> "connection refused"
        0x06 -> "TTL expired"
        0x07 -> "command not supported"
        0x08 -> "address type not supported"
        else -> "SOCKS5 reply code $rep"
    }

    private fun describe(e: Exception): String = when (e) {
        is java.net.ConnectException -> "tcp connect failed: ${e.message ?: "refused"}"
        is java.net.SocketTimeoutException -> "timeout: ${e.message ?: "no reply"}"
        is java.net.UnknownHostException -> "unknown host: ${e.message}"
        is IllegalArgumentException -> e.message ?: "invalid argument"
        is IOException -> e.message ?: e.javaClass.simpleName
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }
}
