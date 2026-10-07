#!/usr/bin/env python3
"""Exercise the real listener with synthetic loopback traffic, without game files."""
from pathlib import Path
import queue
import re
import socket
import subprocess
import sys
import threading
import time

ROOT = Path(__file__).resolve().parents[1]


def main():
    command = [sys.argv[1] if len(sys.argv) > 1 else "java", "-cp",
               str(ROOT / "build/classes"), "org.tortodrone.server.ConnectionLogger"]
    process = subprocess.Popen(command + ["--port", "0"], stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True)
    lines = queue.Queue()
    threading.Thread(target=lambda: [lines.put(line) for line in process.stdout], daemon=True).start()
    try:
        first = lines.get(timeout=10)
        match = re.search(r"127\.0\.0\.1:(\d+)", first)
        assert match, "Listener failed to start: " + first.strip()
        port = int(match.group(1))
        payload = b"\x00\x00\x00\x0cSK\x00\xfftest"
        with socket.create_connection(("127.0.0.1", port), timeout=5) as one:
            with socket.create_connection(("127.0.0.1", port), timeout=5) as two:
                one.sendall(payload)
                two.sendall(b"second")
                one.shutdown(socket.SHUT_WR)
                two.shutdown(socket.SHUT_WR)
                assert one.recv(1) == b"", "listener unexpectedly sent a response"
                assert two.recv(1) == b""
        with socket.create_connection(("127.0.0.1", port), timeout=5):
            pass  # Empty connection must also be accepted and closed cleanly.
        output = first
        deadline = time.monotonic() + 10
        while output.count("disconnected; received") < 3:
            output += lines.get(timeout=max(0.01, deadline - time.monotonic()))
            assert time.monotonic() < deadline, output
        assert "53 4b 00 ff 74 65 73 74" in output, output
        assert "SK..test" in output, output
        assert "received 12 bytes" in output and "received 6 bytes" in output, output
        assert "received 0 bytes" in output and "Connection #3" in output, output
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
    refused = subprocess.run(command + ["--bind", "0.0.0.0"], capture_output=True, timeout=5)
    assert refused.returncode != 0, "public binding must be rejected"
    print("PASS: concurrent connections, binary/ASCII logs, EOF, no responses, loopback restriction")


if __name__ == "__main__":
    main()
