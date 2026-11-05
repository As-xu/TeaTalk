#!/usr/bin/env python3
"""最小 WebDAV 服务端，仅用于 TeaTalk 0.24.0 真机验收。

用途：电脑上起一个只支持 PROPFIND / MKCOL / PUT / GET / DELETE 的临时服务，
配合 `adb reverse tcp:8765 tcp:8765`，让手机访问 http://127.0.0.1:8765/ 完成
「配置 → 备份 → 列表 → 恢复」闭环。不要用于生产。

用法：
    python tools/webdav_dev_server.py --root .tmp/webdav --port 8765 --user test --password secret

鉴权：所有请求需要 Basic Auth（用户名/密码由参数指定，默认 test/secret）。
"""

import argparse
import base64
import html
import os
import shutil
import sys
from datetime import datetime, timezone
from email.utils import format_datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import quote, unquote, urlsplit

ROOT = "."
USER = "test"
PASSWORD = "secret"


def dav_response(href: str, is_dir: bool, size: int, mtime: float) -> str:
    name = unquote(href.rstrip("/").rsplit("/", 1)[-1])
    rtype = "<D:collection/>" if is_dir else ""
    length = "" if is_dir else f"<D:getcontentlength>{size}</D:getcontentlength>"
    lastmod = format_datetime(datetime.fromtimestamp(mtime, timezone.utc), usegmt=True)
    return (
        "<D:response>"
        f"<D:href>{quote(href)}</D:href>"
        "<D:propstat><D:prop>"
        f"<D:displayname>{html.escape(name)}</D:displayname>"
        f"<D:resourcetype>{rtype}</D:resourcetype>"
        f"{length}"
        f"<D:getlastmodified>{lastmod}</D:getlastmodified>"
        "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>"
        "</D:response>"
    )


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "TeaTalkDevDAV/1.0"

    # ---- 基础 ----

    def log_message(self, fmt, *args):  # noqa: N802 - 覆盖 stdlib
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))

    def _authorized(self) -> bool:
        header = self.headers.get("Authorization", "")
        if not header.startswith("Basic "):
            return False
        try:
            raw = base64.b64decode(header[6:]).decode("utf-8")
        except Exception:
            return False
        user, _, password = raw.partition(":")
        return user == USER and password == PASSWORD

    def _reject(self) -> bool:
        if self._authorized():
            return False
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="TeaTalkDevDAV"')
        self.send_header("Content-Length", "0")
        self.end_headers()
        return True

    def _local(self, raw_path: str) -> str:
        path = unquote(urlsplit(raw_path).path).lstrip("/")
        full = os.path.abspath(os.path.join(ROOT, path))
        if not full.startswith(os.path.abspath(ROOT)):
            raise PermissionError(path)
        return full

    def _send(self, code: int, body: bytes = b"", ctype: str = "text/plain; charset=utf-8"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body and self.command != "HEAD":
            self.wfile.write(body)

    def _body(self) -> bytes:
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

    # ---- 方法 ----

    def do_PROPFIND(self, ):  # noqa: N802
        self._body()  # 必须先消费请求体，否则 keep-alive 会把 XML 当成下一个请求行
        if self._reject():
            return
        try:
            local = self._local(self.path)
        except PermissionError:
            self._send(403)
            return
        if not os.path.exists(local):
            self._send(404)
            return
        depth = self.headers.get("Depth", "0")
        parts = [dav_response(self.path, os.path.isdir(local), os.path.getsize(local) if os.path.isfile(local) else 0, os.path.getmtime(local))]
        if depth != "0" and os.path.isdir(local):
            for name in sorted(os.listdir(local)):
                child = os.path.join(local, name)
                href = self.path.rstrip("/") + "/" + name
                parts.append(
                    dav_response(
                        href,
                        os.path.isdir(child),
                        0 if os.path.isdir(child) else os.path.getsize(child),
                        os.path.getmtime(child),
                    )
                )
        body = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<D:multistatus xmlns:D="DAV:">' + "".join(parts) + "</D:multistatus>"
        ).encode("utf-8")
        self._send(207, body, "application/xml; charset=utf-8")

    def do_MKCOL(self, ):  # noqa: N802
        if self._reject():
            return
        self._body()
        try:
            local = self._local(self.path)
        except PermissionError:
            self._send(403)
            return
        if os.path.exists(local):
            self._send(405)
            return
        try:
            os.makedirs(local, exist_ok=True)
        except OSError:
            self._send(409)
            return
        self._send(201)

    def do_PUT(self, ):  # noqa: N802
        if self._reject():
            return
        data = self._body()
        try:
            local = self._local(self.path)
        except PermissionError:
            self._send(403)
            return
        os.makedirs(os.path.dirname(local) or ROOT, exist_ok=True)
        existed = os.path.exists(local)
        with open(local, "wb") as fh:
            fh.write(data)
        self._send(204 if existed else 201)

    def do_GET(self, ):  # noqa: N802
        if self._reject():
            return
        try:
            local = self._local(self.path)
        except PermissionError:
            self._send(403)
            return
        if os.path.isdir(local):
            self._send(405)
            return
        if not os.path.exists(local):
            self._send(404)
            return
        with open(local, "rb") as fh:
            data = fh.read()
        self._send(200, data, "application/octet-stream")

    def do_DELETE(self, ):  # noqa: N802
        if self._reject():
            return
        try:
            local = self._local(self.path)
        except PermissionError:
            self._send(403)
            return
        if not os.path.exists(local):
            self._send(404)
            return
        if os.path.isdir(local):
            shutil.rmtree(local)
        else:
            os.remove(local)
        self._send(204)

    def do_HEAD(self, ):  # noqa: N802
        self.do_GET()


def main() -> None:
    global ROOT, USER, PASSWORD
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".tmp/webdav")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--user", default="test")
    parser.add_argument("--password", default="secret")
    args = parser.parse_args()
    ROOT = os.path.abspath(args.root)
    USER, PASSWORD = args.user, args.password
    os.makedirs(ROOT, exist_ok=True)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"TeaTalk dev WebDAV on http://127.0.0.1:{args.port}/ (root {ROOT}, user {USER})")
    print("For the device: adb reverse tcp:%d tcp:%d" % (args.port, args.port))
    server.serve_forever()


if __name__ == "__main__":
    main()
