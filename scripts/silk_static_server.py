#!/usr/bin/env python3
"""Serve the Silk Web build and route the device approval URL to the SPA."""

from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from functools import partial
from urllib.parse import urlsplit


class SilkStaticRequestHandler(SimpleHTTPRequestHandler):
    def send_head(self):
        if urlsplit(self.path).path.rstrip("/") == "/device":
            self.path = "/index.html"
        return super().send_head()


def main() -> None:
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("port", type=int)
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--directory", default=".")
    args = parser.parse_args()

    handler = partial(SilkStaticRequestHandler, directory=args.directory)
    ThreadingHTTPServer((args.bind, args.port), handler).serve_forever()


if __name__ == "__main__":
    main()
