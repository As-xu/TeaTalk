#!/usr/bin/env python3
"""0.26.0 更新检查的真机联调服务（仅开发用）。

列出目录里的 `release.json` 与同目录的 apk / .sha256，并把 apk 以限速方式
流式返回，方便在真机上截到「正在下载 xx%」的中间态。

用法：
    python tools/update_dev_server.py --root .tmp/update_feed --port 8778 --rate-kbps 1200
    adb reverse tcp:8778 tcp:8778
    adb shell am start -n com.teatalk.xs/.MainActivity \
        --es teatalk.update_feed http://127.0.0.1:8778/release.json
"""
from __future__ import annotations

import argparse
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def build_handler(root: str, kbps: int):
    chunk = max(4 * 1024, kbps * 1024 // 10)  # 每 100ms 写一块

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):  # 安静一点
            print("[update-dev]", fmt % args, flush=True)

        def _path(self) -> str:
            clean = self.path.split("?", 1)[0].lstrip("/")
            target = os.path.normpath(os.path.join(root, clean))
            if not target.startswith(os.path.abspath(root)):
                return ""
            return target

        def do_GET(self):
            path = self._path()
            if not path or not os.path.isfile(path):
                self.send_response(404)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            size = os.path.getsize(path)
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(size))
            self.end_headers()
            if not path.endswith(".apk"):
                with open(path, "rb") as handle:
                    self.wfile.write(handle.read())
                return
            sent = 0
            with open(path, "rb") as handle:
                while True:
                    data = handle.read(chunk)
                    if not data:
                        break
                    self.wfile.write(data)
                    sent += len(data)
                    if kbps > 0:
                        time.sleep(0.1)
            print(f"[update-dev] sent {sent} bytes of {os.path.basename(path)}", flush=True)

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".tmp/update_feed")
    parser.add_argument("--port", type=int, default=8778)
    parser.add_argument("--rate-kbps", type=int, default=1200)
    args = parser.parse_args()
    handler = build_handler(os.path.abspath(args.root), args.rate_kbps)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), handler)
    print(f"serving {args.root} on http://127.0.0.1:{args.port}/ (rate={args.rate_kbps} KB/s)", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
