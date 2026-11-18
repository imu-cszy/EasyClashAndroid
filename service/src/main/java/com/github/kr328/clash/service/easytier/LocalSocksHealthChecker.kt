package com.github.kr328.clash.service.easytier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * SOCKS5 分层健康检查器（RFC 1928 / 1929 字节流实现，不用 java.net.Proxy 以获得可分级的错误）。
 *
 * 分层：
 *  1. TCP_CONNECT     连接本地 EasyTier SOCKS5（默认 127.0.0.1:11080）；
 *  2. GREETING        方法协商（00 无认证 / 02 用户密码）；
 *  3. AUTH            本地认证（如需）；
 *  4. CONNECT_REMOTE  经隧道 CONNECT 到 Lightsail 端口；
 *  5. REMOTE_GREETING 隧道内对 Lightsail SOCKS5 做二次协商（端到端验证）；
 *  6. REMOTE_AUTH     Lightsail 认证（凭据错误会在此阶段暴露）；
 *  7. REMOTE_CONNECT  经 Lightsail 出口 CONNECT 探测目标（证明出口真的可用）。
 */
class LocalSocksHealthChecker(
    private val connectTimeoutMs: Int = 3000,
    private val readTimeoutMs: Int = 5000,
) {
    enum class Stage {
        TCP_CONNECT, GREETING, AUTH, CONNECT_REMOTE,
        REMOTE_GREETING, REMOTE_AUTH, REMOTE_CONNECT,
    }

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
            var stage = Stage.TCP_CONNECT
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.soTimeout = readTimeoutMs
                stage = Stage.GREETING
                greet(socket.getInputStream(), socket.getOutputStream())
                CheckResult.Ok(Stage.GREETING, System.currentTimeMillis() - start)
            } catch (e: Exception) {
                CheckResult.Failed(stage, System.currentTimeMillis() - start, describe(e))
            } finally {
                runCatching { socket?.close() }
            }
        }

    /**
     * 经本地 SOCKS5 对远端执行 CONNECT（3/4 级，验证 Overlay 应用层可达性）。
     * 只验证 TCP 可达，不做远端认证。
     */
    suspend fun checkThrough(
        viaHost: String,
        viaPort: Int,
        targetHost: String,
        targetPort: Int,
    ): CheckResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        var socket: Socket? = null
        var stage = Stage.TCP_CONNECT
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(viaHost, viaPort), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            stage = Stage.GREETING
            val method = greet(input, output)
            if (method == METHOD_USER_PASS) {
                stage = Stage.AUTH
                throw IOException("local socks5 requires username/password but none configured")
            }

            stage = Stage.CONNECT_REMOTE
            connectRemote(input, output, targetHost, targetPort)
            CheckResult.Ok(Stage.CONNECT_REMOTE, System.currentTimeMillis() - start)
        } catch (e: Exception) {
            CheckResult.Failed(stage, System.currentTimeMillis() - start, describe(e))
        } finally {
            runCatching { socket?.close() }
        }
    }

    /**
     * 端到端检测（推荐）：本地 SOCKS5 → 隧道 CONNECT 远端 SOCKS5 → 远端协商/认证 →
     * 远端 CONNECT 探测目标。
     *
     * 与 [checkThrough] 的区别：能区分「隧道/端口不可达」与「远端凭据错误」，
     * 并证明出口真的能发出连接。
     *
     * @param probeHost/probePort 经远端出口探测的目标（默认 1.1.1.1:80）
     */
    suspend fun checkRemoteThrough(
        viaHost: String,
        viaPort: Int,
        targetHost: String,
        targetPort: Int,
        username: String? = null,
        password: String? = null,
        probeHost: String = "1.1.1.1",
        probePort: Int = 80,
    ): CheckResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        var socket: Socket? = null
        var stage = Stage.TCP_CONNECT
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(viaHost, viaPort), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // 1) 本地 EasyTier SOCKS5（无认证）
            stage = Stage.GREETING
            val localMethod = greet(input, output, offerUserPass = username != null)
            if (localMethod == METHOD_USER_PASS) {
                stage = Stage.AUTH
                require(username != null && password != null) { "local socks5 requires auth but credentials empty" }
                authenticate(input, output, username, password)
            }

            // 2) 经隧道连到 Lightsail SOCKS5 端口
            stage = Stage.CONNECT_REMOTE
            connectRemote(input, output, targetHost, targetPort)

            // 3) 隧道内二级 SOCKS5：协商 + 认证
            stage = Stage.REMOTE_GREETING
            val remoteMethod = greet(input, output, offerUserPass = username != null)
            if (remoteMethod == METHOD_USER_PASS) {
                stage = Stage.REMOTE_AUTH
                require(username != null && password != null) { "remote socks5 requires auth but credentials empty" }
                authenticate(input, output, username, password)
            }

            // 4) 经出口探测目标
            stage = Stage.REMOTE_CONNECT
            connectRemote(input, output, probeHost, probePort)

            CheckResult.Ok(Stage.REMOTE_CONNECT, System.currentTimeMillis() - start)
        } catch (e: Exception) {
            CheckResult.Failed(stage, System.currentTimeMillis() - start, describe(e))
        } finally {
            runCatching { socket?.close() }
        }
    }

    /** 返回服务器选定的认证方法字节（0x00 无认证 / 0x02 用户密码） */
    @Throws(IOException::class)
    private fun greet(input: InputStream, output: OutputStream, offerUserPass: Boolean = false): Byte {
        val methods = if (offerUserPass) byteArrayOf(0x05, 0x02, 0x00, 0x02) else byteArrayOf(0x05, 0x01, 0x00)
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

    /** DOMAIN 型 CONNECT；reply code 非 00 抛带码错误 */
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
        input.read() // RSV（漏读会把 0 当 ATYP，2026-11-18 单测抓到的真实 bug）
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

    private companion object {
        const val METHOD_USER_PASS: Byte = 0x02
    }
}
