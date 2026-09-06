"""A local stand-in for the OpenAI Images API that returns real PNGs.
Speaks /v1/images/generations (JSON) and /v1/images/edits (multipart) exactly as the
production adapter expects, logs every request with a timestamp, and adds a small delay so
'text first, pictures follow' is observable in the UI."""
import base64, json, re, struct, sys, time, zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DELAY = 1.2

def png(w, h, kind, seed, transparent=False):
    rows = bytearray()
    for y in range(h):
        rows.append(0)
        t = y / max(1, h - 1)
        for x in range(w):
            u = x / max(1, w - 1)
            alpha = 0 if transparent else 255
            if kind == "bg":
                r, g, b = int(95 + 70 * (1 - t) + 25 * u), int(62 + 44 * (1 - t)), int(38 + 30 * (1 - t))
            else:
                r, g, b = int(36 + 30 * t), int(80 + 60 * (1 - t)), int(105 + 40 * (1 - t))
                dx, dy = (u - 0.5) / 0.30, (t - 0.30) / 0.22
                if dx * dx + dy * dy < 1: r, g, b, alpha = 224, 194, 166, 255
                if 0.25 < u < 0.75 and t > 0.52: r, g, b, alpha = 70 + seed % 40, 50, 60, 255
                if kind == "card" and (0.025 < u < 0.04 or 0.96 < u < 0.975 or 0.02 < t < 0.03 or 0.97 < t < 0.98):
                    r, g, b = 194, 168, 113
            rows += bytes(((r + seed) % 256, g, b, alpha))
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(rows), 6)) + chunk(b"IEND", b""))

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0)); body = self.rfile.read(n)
        auth = self.headers.get("Authorization", "")
        if self.path.endswith("/images/generations"):
            req = json.loads(body); prompt = req.get("prompt", ""); size = req.get("size", "1024x1024"); mode = "generate"
            transparent = req.get("background") == "transparent"
        elif self.path.endswith("/images/edits"):
            m = re.search(rb'name="prompt"\r\n\r\n(.*?)\r\n--', body, re.S); prompt = m.group(1).decode("utf-8", "replace") if m else ""
            m = re.search(rb'name="size"\r\n\r\n(.*?)\r\n', body); size = m.group(1).decode() if m else "1024x1536"; mode = "edit"
            m = re.search(rb'name="background"\r\n\r\n(.*?)\r\n', body); transparent = bool(m and m.group(1) == b"transparent")
        else:
            self.send_response(404); self.end_headers(); return
        kind = "bg" if "Stage background" in prompt else "card" if "permanent character card" in prompt else "pt"
        w, h = [int(v) for v in size.split("x")]; w, h = w // 2, h // 2   # half size keeps the demo quick
        seed = abs(hash(prompt)) % 200
        t0 = time.time(); time.sleep(DELAY); data = png(w, h, kind, seed, transparent)
        print(f"{time.strftime('%H:%M:%S')} {mode:8} {kind} {w}x{h} auth={'ok' if auth.startswith('Bearer ') else 'MISSING'} "
              f"{time.time()-t0:.1f}s :: {prompt[:70]!r}", flush=True)
        out = json.dumps({"data": [{"b64_json": base64.b64encode(data).decode()}]}).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(out)))
        self.end_headers(); self.wfile.write(out)

if __name__ == "__main__":
    print("image stub listening on 127.0.0.1:9911", flush=True)
    ThreadingHTTPServer(("127.0.0.1", 9911), H).serve_forever()
