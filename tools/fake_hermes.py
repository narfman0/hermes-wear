"""Stand-in for the Hermes API server's POST /v1/responses SSE stream.

Used to test the watch app without touching the real agent. Emits the same event
types the real server documents: response.created, response.output_text.delta,
response.completed, plus ': keepalive' comments.

    python3 tools/fake_hermes.py --port 18642 --key testkey [--delay 0.5]
"""
import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def make_handler(key, delay):
    class H(BaseHTTPRequestHandler):
        def log_message(self, fmt, *a):
            print("fake-hermes:", fmt % a, flush=True)

        def _json(self, code, obj):
            data = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.path == "/health":
                return self._json(200, {"status": "ok", "platform": "fake-hermes"})
            if self.path != "/v1/models":
                return self._json(404, {"error": "not found"})
            if self.headers.get("Authorization") != f"Bearer {key}":
                return self._json(401, {"error": "unauthorized"})
            return self._json(200, {"object": "list", "data": [{"id": "hermes-agent", "object": "model"}]})

        def do_POST(self):
            if self.headers.get("Authorization") != f"Bearer {key}":
                self.send_response(401)
                self.end_headers()
                return
            if self.path != "/v1/responses":
                self.send_response(404)
                self.end_headers()
                return
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
            print("fake-hermes: request", json.dumps(body), flush=True)
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()

            def ev(name, data):
                self.wfile.write(f"event: {name}\ndata: {json.dumps(data)}\n\n".encode())
                self.wfile.flush()

            ev("response.created", {"type": "response.created", "response": {"id": "resp_1"}})
            self.wfile.write(b": keepalive\n\n")
            self.wfile.flush()
            reply = f"You said: {body.get('input')}. This is a streamed test reply from the fake Hermes server."
            for word in reply.split(" "):
                ev("response.output_text.delta", {"type": "response.output_text.delta", "delta": word + " "})
                time.sleep(delay)
            ev("response.completed", {"type": "response.completed", "response": {"id": "resp_1"}})

    return H


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=18642)
    ap.add_argument("--key", default="testkey")
    ap.add_argument("--delay", type=float, default=0.05, help="seconds between words")
    a = ap.parse_args()
    ThreadingHTTPServer(("0.0.0.0", a.port), make_handler(a.key, a.delay)).serve_forever()
