#!/usr/bin/env python3
"""
ToF depth stream viewer — receives rendered RGBA frames from the phone.

USB (recommended):
    adb forward tcp:7777 tcp:7777
    python depth_viewer.py

WiFi:
    python depth_viewer.py 192.168.x.x

Controls:
    Q / Esc  — quit
    S        — save current frame as .png

Encoding: JPEG (quality 85) — reduces 1440×1080×4 raw (~6 MB) to ~50–150 KB per frame.
"""

import socket
import struct
import sys
import time
from pathlib import Path

import cv2
import numpy as np

HOST = sys.argv[1] if len(sys.argv) > 1 else "localhost"
PORT = 7777
MAGIC_JPEG  = b"JPEG"
MAGIC_RGBA  = b"RGBA"   # legacy
MAGIC_DEPTH = b"DPTH"   # legacy
HEADER_FMT  = "<4sHHI"   # magic, width, height, data_len
HEADER_SIZE = struct.calcsize(HEADER_FMT)


def recv_exactly(sock: socket.socket, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("Connection closed by remote")
        buf.extend(chunk)
    return bytes(buf)


def decode_jpeg(raw: bytes) -> np.ndarray:
    """Decode JPEG frame. Android already flipped rows; JPEG decoder gives BGR."""
    return cv2.imdecode(np.frombuffer(raw, np.uint8), cv2.IMREAD_COLOR)


def decode_rgba(raw: bytes, width: int, height: int) -> np.ndarray:
    """Legacy: decode raw RGBA frame from GL (bottom-up origin)."""
    img = np.frombuffer(raw, dtype=np.uint8).reshape(height, width, 4)
    img = np.flipud(img)
    return cv2.cvtColor(img, cv2.COLOR_RGBA2BGR)


def decode_depth(raw: bytes, width: int, height: int):
    """Fallback: decode raw DEPTH16 frame into a colourised BGR image."""
    pixels = np.frombuffer(raw, dtype="<u2").reshape(height, width)
    depth_m = (pixels & 0x1FFF).astype(np.float32) * 0.001
    valid = depth_m > 0
    d_min = float(np.percentile(depth_m[valid], 2))  if valid.any() else 0.0
    d_max = float(np.percentile(depth_m[valid], 98)) if valid.any() else 1.0
    span  = max(d_max - d_min, 0.001)
    norm  = 255 - np.clip((depth_m - d_min) / span * 255, 0, 255).astype(np.uint8)
    norm[~valid] = 0
    img = cv2.applyColorMap(norm, cv2.COLORMAP_INFERNO)
    img[~valid] = 0
    return img, depth_m


def overlay_hud(img: np.ndarray, fps: float, frame_num: int,
                width: int, height: int) -> None:
    cv2.putText(img, f"{width}x{height}  {fps:.1f} fps  frame {frame_num}",
                (8, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (220, 220, 220), 1, cv2.LINE_AA)


def main():
    print(f"Connecting to {HOST}:{PORT} …")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect((HOST, PORT))
    print("Connected.  Q/Esc=quit  C=colourmap  R=values  S=save")

    frame_num = 0
    fps = 0.0
    t_last = time.monotonic()
    img = None
    save_dir = Path(".")

    cv2.namedWindow("Depth Stream", cv2.WINDOW_NORMAL)

    try:
        while True:
            header = recv_exactly(sock, HEADER_SIZE)
            magic, width, height, data_len = struct.unpack(HEADER_FMT, header)

            raw = recv_exactly(sock, data_len)

            if magic == MAGIC_JPEG:
                img = decode_jpeg(raw)
            elif magic == MAGIC_RGBA:
                img = decode_rgba(raw, width, height)
            elif magic == MAGIC_DEPTH:
                img, _ = decode_depth(raw, width, height)
            else:
                print(f"Unknown magic: {magic!r}")
                break

            # FPS
            now = time.monotonic()
            fps = 0.9 * fps + 0.1 * (1.0 / max(now - t_last, 1e-6))
            t_last = now
            frame_num += 1

            overlay_hud(img, fps, frame_num, width, height)
            cv2.imshow("Depth Stream", img)

            key = cv2.waitKey(1) & 0xFF
            if key in (ord("q"), 27):
                break
            elif key == ord("s") and img is not None:
                fname = save_dir / f"depth_{frame_num:06d}.png"
                cv2.imwrite(str(fname), img)
                print(f"Saved {fname}")

    except ConnectionError as e:
        print(f"Disconnected: {e}")
    finally:
        sock.close()
        cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
