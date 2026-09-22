"""Minimal RCON client. Library use: Rcon(port, password).cmd("...") ; CLI: rcon.py <port> <password> <command...>"""
import socket, struct, sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")


class Rcon:
    def __init__(self, port, password, host="127.0.0.1", timeout=600):
        self.s = socket.create_connection((host, port), timeout=timeout)
        self._id = 0
        self._send(3, password)
        self._recv()

    def _send(self, kind, body):
        self._id += 1
        b = body.encode("utf8") + b"\x00\x00"
        self.s.sendall(struct.pack("<iii", len(b) + 8, self._id, kind) + b)

    def _recv_exact(self, n):
        data = b""
        while len(data) < n:
            chunk = self.s.recv(n - len(data))
            if not chunk:
                raise ConnectionError("rcon closed")
            data += chunk
        return data

    def _recv(self):
        n = struct.unpack("<i", self._recv_exact(4))[0]
        data = self._recv_exact(n)
        return struct.unpack("<i", data[:4])[0], data[8:-2].decode("utf8", "replace")

    def cmd(self, command):
        self._send(2, command)
        # Vanilla reads one packet per TCP read and drops the connection if two arrive together,
        # so the end marker (an unknown-type packet, answered in order) goes out only after the
        # first reply part has arrived. It marks the end of a reply split over several packets.
        parts = [self._recv()[1]]
        self._send(0, "")
        sentinel = self._id
        while True:
            rid, body = self._recv()
            if rid == sentinel:
                break
            parts.append(body)
        return "".join(parts)


if __name__ == "__main__":
    r = Rcon(int(sys.argv[1]), sys.argv[2])
    print(r.cmd(" ".join(sys.argv[3:])))
