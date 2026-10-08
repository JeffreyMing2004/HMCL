"""Mock NorthStar self-update server for local update drills.

Serves the `/update` manifest and the new-version jar from localhost so the
launcher's full update loop (detect -> download -> SHA-1 verify -> swap ->
restart) can be rehearsed before the production endpoint is deployed.

Usage:
    python scripts/mock_update_server.py --jar HMCL/build/libs/HMCL-3.17.2.jar --port 8765

Point the launcher at it with:
    -Dhmcl.update_source.override=http://127.0.0.1:8765/update
"""

import argparse
import hashlib
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

JAR_PATH: Path
NEW_VERSION: str
FORCE = False


def sha1_of(path: Path) -> str:
    digest = hashlib.sha1()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            digest.update(chunk)
    return digest.hexdigest()


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/update":
            query = parse_qs(parsed.query)
            requested = query.get("version", ["?"])[0]
            channel = query.get("channel", ["?"])[0]
            print(f"[mock-update] /update requested by version={requested} channel={channel}")
            jar_url = f"http://127.0.0.1:{self.server.server_port}/{JAR_PATH.name}"
            manifest = {
                "version": NEW_VERSION,
                "jar": jar_url,
                "jarsha1": sha1_of(JAR_PATH),
                "force": FORCE,
            }
            body = json.dumps(manifest).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif parsed.path == "/" + JAR_PATH.name:
            size = JAR_PATH.stat().st_size
            print(f"[mock-update] serving {JAR_PATH.name} ({size} bytes)")
            self.send_response(200)
            self.send_header("Content-Type", "application/java-archive")
            self.send_header("Content-Length", str(size))
            self.end_headers()
            with JAR_PATH.open("rb") as f:
                while chunk := f.read(1 << 16):
                    self.wfile.write(chunk)
        else:
            self.send_error(404)

    def log_message(self, *args) -> None:
        pass  # keep drill output clean; requests are logged explicitly


def main() -> None:
    global JAR_PATH, NEW_VERSION

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path, help="new-version launcher jar to serve")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--force", action="store_true", help="serve force=true to drill a forced update")
    args = parser.parse_args()

    JAR_PATH = args.jar.resolve()
    if not JAR_PATH.is_file():
        raise SystemExit(f"jar not found: {JAR_PATH}")
    # Convention: HMCL-<version>.jar
    NEW_VERSION = JAR_PATH.name[len("HMCL-"):-len(".jar")]
    FORCE = args.force

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"[mock-update] version={NEW_VERSION} force={FORCE} "
          f"listening on http://127.0.0.1:{args.port}/update")
    server.serve_forever()


if __name__ == "__main__":
    main()
