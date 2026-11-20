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
  * 装了 qrcode 就把二维码画成图片并在屏幕上打开（终端再打一份备用）；
    没装则只打印链接（手机手输即可）；
  * 传输完成后按 Ctrl+C 结束，服务进程退出即断开。

为什么默认用图片而不是终端字符画：终端里的 ▀/▄ 半块字符依赖字体与行高，
不同终端下模块可能被拉成长方形、上下被裁掉，或者因配色变成反色二维码，
手机端（ZXing）经常扫不出来。图片是 1:1 正方形模块 + 4 模块静区，稳定得多。

安全提示：这是局域网内的明文 HTTP，只在自己信任的网络里用；用完即关。
"""

import argparse
import http.server
import ipaddress
import os
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import webbrowser

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


def _windows_candidates() -> list:
    """Windows：只取"带默认网关"的网卡，并让无线排在有线前面。

    单纯按私网地址挑会踩坑——本机同时存在 VMware(192.168.x)、Hyper-V(172.x)、
    EasyTier 覆盖网(10.126.126.x) 和代理 TUN(198.18.x)，这些地址手机都连不上。
    带默认网关的网卡才是真正对外连通的那块，手机通常也在这个网络里。
    """
    script = (
        "Get-NetIPConfiguration -ErrorAction SilentlyContinue | "
        "Where-Object { $_.IPv4DefaultGateway -ne $null } | "
        'ForEach-Object { "$($_.IPv4Address.IPAddress)|$($_.InterfaceAlias)" }'
    )
    try:
        out = subprocess.run(
            ["powershell", "-NoProfile", "-Command", script],
            capture_output=True, text=True, timeout=10,
        ).stdout
    except Exception:
        return []

    wireless = ("wi-fi", "wifi", "wlan", "无线", "wireless")
    ranked = []

    for line in out.splitlines():
        line = line.strip()
        if "|" not in line:
            continue

        ip, alias = line.split("|", 1)
        ip = ip.strip()
        if not _plausible_lan(ip):
            continue

        is_wireless = any(word in alias.lower() for word in wireless)
        ranked.append((0 if is_wireless else 1, ip, alias))

    ranked.sort()
    return [ip for _, ip, _ in ranked]


def lan_candidates() -> list:
    """按可信度排序的局域网地址候选（第一个用于打印链接）"""
    # Windows 上只要拿到"带默认网关"的网卡就够了；VMware / Hyper-V / EasyTier /
    # 代理 TUN 的地址都在这之外，列出来只会误导。
    gateway = _windows_candidates()
    if gateway:
        return gateway

    seen = []

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        ip = sock.getsockname()[0]
        if _plausible_lan(ip) and ip not in seen:
            seen.append(ip)
    except OSError:
        pass
    finally:
        sock.close()

    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if _plausible_lan(ip) and ip not in seen:
                seen.append(ip)
    except OSError:
        pass

    return seen



def human_size(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} GB"


def _build_qr(url: str):
    """构造二维码对象。

    border=4 是 ISO/IEC 18004 要求的静区宽度；用 border=1 时很多解码器
    会因为边缘没有足够空白而放弃识别。
    """
    import qrcode  # type: ignore

    qr = qrcode.QRCode(
        error_correction=qrcode.constants.ERROR_CORRECT_M,
        box_size=10,
        border=4,
    )
    qr.add_data(url)
    qr.make(fit=True)
    return qr


def _write_qr_png(qr) -> str:
    """渲染成 PNG（需要 Pillow）。模块放大到整图约 900px，方便远距离扫描。"""
    from PIL import Image  # noqa: F401  # make_image 依赖 Pillow

    size = len(qr.get_matrix())
    qr.box_size = max(8, 900 // size)

    path = os.path.join(tempfile.gettempdir(), f"easyclash-qr-{secrets.token_hex(4)}.png")
    qr.make_image(fill_color="black", back_color="white").save(path)
    return path


def _write_qr_svg(qr) -> str:
    """没有 Pillow 时的退路：手写 SVG，用浏览器打开同样清晰。"""
    matrix = qr.get_matrix()
    size = len(matrix)
    rects = []
    for y, row in enumerate(matrix):
        for x, dark in enumerate(row):
            if dark:
                rects.append(f'<rect x="{x}" y="{y}" width="1" height="1"/>')

    svg = (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}" '
        f'width="900" height="900" shape-rendering="crispEdges">'
        f'<rect width="{size}" height="{size}" fill="#fff"/>'
        f'<g fill="#000">{"".join(rects)}</g></svg>'
    )

    path = os.path.join(tempfile.gettempdir(), f"easyclash-qr-{secrets.token_hex(4)}.svg")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(svg)
    return path


def _open_file(path: str) -> bool:
    try:
        if hasattr(os, "startfile"):  # Windows
            os.startfile(path)  # type: ignore[attr-defined]
        else:
            webbrowser.open("file://" + path)
        return True
    except OSError:
        return False


def _print_qr_terminal(qr) -> bool:
    """终端备用二维码：用 ANSI 背景色画方块，颜色不受终端主题影响。"""
    matrix = qr.get_matrix()
    columns = shutil.get_terminal_size((80, 24)).columns

    # 每个模块占 2 个字符宽、1 行高，正好接近方形；放不下就不画，免得被裁掉
    if len(matrix) * 2 + 2 > columns:
        return False

    dark = "\x1b[40m  \x1b[0m"
    light = "\x1b[47m  \x1b[0m"

    for row in matrix:
        print("".join(dark if cell else light for cell in row))

    return True


def present_qr(url: str, open_window: bool = True) -> None:
    try:
        qr = _build_qr(url)
    except Exception:
        print("  （未安装 qrcode，无法生成二维码；直接输入上面的链接即可）")
        print("   pip install qrcode pillow   # 可选，用于生成二维码")
        print()
        return

    image_path = None
    opened = False

    if open_window:
        try:
            image_path = _write_qr_png(qr)
        except Exception:
            try:
                image_path = _write_qr_svg(qr)
            except Exception:
                image_path = None

        if image_path and _open_file(image_path):
            opened = True
            print(f"  二维码图片已打开：{image_path}")
            print("  手机对准屏幕上的图片扫码（终端里那条只是备用）")
            print()

    if not _print_qr_terminal(qr):
        if opened:
            print("  （终端太窄，显示不下二维码；请用上面打开的图片）")
        else:
            print("  （终端太窄，二维码没画出来；请把上面的链接直接输入手机）")
        print()


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
    candidates = [] if args.host else lan_candidates()
    host = args.host or (candidates[0] if candidates else "127.0.0.1")

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

    if len(candidates) > 1:
        print("  本机其它地址（第一个连不上时换一个试试）：")
        for ip in candidates[1:]:
            print(f"    http://{ip}:{port}/{token}")
        print()
    print("  手机端：配置 → 导入 → 从链接 / 二维码导入")
    print()

    present_qr(url, open_window=not args.no_qr_window)

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
    parser.add_argument(
        "--no-qr-window",
        action="store_true",
        help="不自动打开二维码图片窗口（只打印链接和终端二维码）",
    )

    return serve(parser.parse_args(argv))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
