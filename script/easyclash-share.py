#!/usr/bin/env python3
"""EasyClash 配置包·局域网互传服务（电脑端）

把手机导出的 `.ecb`（或要导入手机的配置包）放到电脑上，用本脚本起一个临时 HTTP 服务，
手机端「配置 → 导入 → 从链接 / 二维码导入」即可扫码或手输链接下载。

用法：
    python easyclash-share.py easyclash-20261119-1300.ecb
    python easyclash-share.py 配置包.ecb --port 8765
    python easyclash-share.py 配置包.ecb --bind 192.168.1.10

特性：
  * 只提供一份文件（随机路径），没有目录浏览；
  * 默认监听 0.0.0.0，端口占用时自动 +1 重试；
  * 装了 qrcode / segno 就在终端打印二维码，否则只打印链接（手机手输即可）；
  * 传输完成后按 Ctrl+C 结束，服务进程退出即断开。

安全提示：这是局域网内的明文 HTTP，只在自己信任的网络里用；用完即关。
"""

import argparse
import http.server
import ipaddress
import os
import secrets
import socket
import sys
import threading

MIME = "application/octet-stream"


def _plausible_lan(ip: str) -> bool:
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return False

    if addr.is_loopback or addr.is_link_local or not addr.is_private:
        return False

    # 198.18.0.0/15 是常见的代理 TUN 网段，不是真正的局域网地址
    if ipaddress.ip_address("198.18.0.0") <= addr <= ipaddress.ip_address("198.19.255.255"):
        return False

    return True


def lan_ip() -> str:
    """取本机在局域网里的地址（不会真的发包）"""
    candidates = []

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        candidates.append(sock.getsockname()[0])
    except OSError:
        pass
    finally:
        sock.close()

    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            candidates.append(info[4][0])
    except OSError:
        pass

    for ip in candidates:
        if _plausible_lan(ip):
            return ip

    return candidates[0] if candidates else "127.0.0.1"


def human_size(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} GB"


def print_qr(url: str) -> bool:
    try:
        import qrcode  # type: ignore

        qr = qrcode.QRCode(border=1)
        qr.add_data(url)
        qr.make(fit=True)
        qr.print_ascii(invert=True)
        return True
    except Exception:
        pass

    try:
        import segno  # type: ignore

        segno.make(url).terminal(compact=True)
        return True
    except Exception:
        pass

    return False


def make_handler(path: str, token: str, payload: bytes):
    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def _not_found(self) -> None:
            body = b"not found"
            self.send_response(404)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self) -> None:  # noqa: N802 - http.server API
            if self.path not in (f"/{token}", f"/{token}/{os.path.basename(path)}"):
                return self._not_found()

            self.send_response(200)
            self.send_header("Content-Type", MIME)
            self.send_header("Content-Length", str(len(payload)))
            self.send_header(
                "Content-Disposition",
                f'attachment; filename="{os.path.basename(path)}"',
            )
            self.end_headers()
            self.wfile.write(payload)
            print(f"  → 已发送 {human_size(len(payload))} 给 {self.client_address[0]}")

        def do_HEAD(self) -> None:  # noqa: N802 - http.server API
            if self.path not in (f"/{token}", f"/{token}/{os.path.basename(path)}"):
                return self._not_found()

            self.send_response(200)
            self.send_header("Content-Type", MIME)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()

        def log_message(self, fmt: str, *args) -> None:  # 静音默认访问日志
            pass

    return Handler


def serve(args: argparse.Namespace) -> int:
    # 重定向到文件/管道时也要立刻看到链接输出
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(line_buffering=True)

    path = os.path.abspath(args.file)
    if not os.path.isfile(path):
        print(f"找不到文件：{path}", file=sys.stderr)
        return 2

    payload = open(path, "rb").read()
    token = secrets.token_hex(8)
    bind = args.bind or "0.0.0.0"
    host = args.host or lan_ip()

    port = args.port
    httpd = None
    for candidate in range(port, port + 20):
        try:
            httpd = http.server.ThreadingHTTPServer(
                (bind, candidate), make_handler(path, token, payload)
            )
            port = candidate
            break
        except OSError:
            continue

    if httpd is None:
        print(f"端口 {args.port}..{args.port + 19} 都被占用，换个 --port 试试", file=sys.stderr)
        return 3

    url = f"http://{host}:{port}/{token}"

    print()
    print("  EasyClash 配置包·局域网互传")
    print("  ─────────────────────────────")
    print(f"  文件：{os.path.basename(path)}（{human_size(len(payload))}）")
    print(f"  监听：{bind}:{port}")
    print()
    print(f"  链接：{url}")
    print()
    print("  手机端：配置 → 导入 → 从链接 / 二维码导入")
    print()

    if not print_qr(url):
        print("  （未安装 qrcode / segno，无法打印二维码；直接输入上面的链接即可）")
        print("   pip install qrcode   # 可选，用于生成二维码")
        print()

    print("  结束请按 Ctrl+C")
    print()

    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()

    try:
        thread.join()
    except KeyboardInterrupt:
        print("\n  已停止")
    finally:
        httpd.shutdown()
        httpd.server_close()

    return 0


def main(argv: list) -> int:
    parser = argparse.ArgumentParser(
        description="把 EasyClash 配置包通过局域网 HTTP 分享给手机",
    )
    parser.add_argument("file", help="要分享的 .ecb 文件")
    parser.add_argument("--port", type=int, default=8765, help="起始端口（默认 8765）")
    parser.add_argument("--bind", default=None, help="绑定地址（默认 0.0.0.0）")
    parser.add_argument("--host", default=None, help="打印链接时使用的主机（默认自动探测）")

    return serve(parser.parse_args(argv))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
