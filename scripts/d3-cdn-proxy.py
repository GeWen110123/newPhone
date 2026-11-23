"""Loopback HTTP proxy: route D3 public HTTP/HTTPS through one Windows NIC.

HTTPS uses CONNECT, so certificates and encrypted application traffic are untouched.
Windows applications retain their own routes. No request URLs or headers are
logged. This program does not submit or retry application requests.
"""

import argparse
import ipaddress
import json
import select
import secrets
import socket
import socketserver
import struct
import time
import urllib.parse


DNS_CACHE = {}


def skip_dns_name(data, offset):
    for _ in range(128):
        if offset >= len(data):
            raise OSError("Truncated DNS name")
        length = data[offset]
        if length == 0:
            return offset + 1
        if length & 0xC0 == 0xC0:
            if offset + 1 >= len(data):
                raise OSError("Truncated DNS pointer")
            return offset + 2
        if length > 63:
            raise OSError("Invalid DNS label")
        offset += length + 1
    raise OSError("Invalid DNS name")


def resolve_ipv4(host, interface_index, source_ip, dns_server):
    try:
        return [str(ipaddress.IPv4Address(host))]
    except ValueError:
        pass
    key = (host, interface_index, source_ip, dns_server)
    cached = DNS_CACHE.get(key)
    if cached and cached[0] > time.monotonic():
        return cached[1]
    labels = host.encode("idna").rstrip(b".").split(b".")
    if any(not label or len(label) > 63 for label in labels):
        raise ValueError("Invalid DNS hostname")
    transaction = secrets.randbits(16)
    query = struct.pack("!6H", transaction, 0x0100, 1, 0, 0, 0)
    query += b"".join(bytes([len(label)]) + label for label in labels) + b"\0\0\1\0\1"
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as connection:
        connection.settimeout(4)
        connection.setsockopt(socket.IPPROTO_IP, 31, struct.pack("!I", interface_index))
        connection.bind((source_ip, 0))
        connection.connect((dns_server, 53))
        connection.send(query)
        data = connection.recv(4096)
    if len(data) < 12:
        raise OSError("Truncated DNS response")
    response_id, flags, questions, answers, _, _ = struct.unpack("!6H", data[:12])
    if response_id != transaction or not flags & 0x8000 or flags & 0x020F or questions != 1:
        raise OSError("DNS response failed validation")
    offset = skip_dns_name(data, 12) + 4
    addresses, ttl = [], 60
    for _ in range(answers):
        offset = skip_dns_name(data, offset)
        if offset + 10 > len(data):
            raise OSError("Truncated DNS record")
        kind, record_class, record_ttl, length = struct.unpack("!HHIH", data[offset:offset + 10])
        offset += 10
        if offset + length > len(data):
            raise OSError("Truncated DNS data")
        if kind == 1 and record_class == 1 and length == 4:
            addresses.append(socket.inet_ntoa(data[offset:offset + 4]))
            ttl = min(ttl, max(1, record_ttl))
        offset += length
    if not addresses:
        raise OSError("No IPv4 DNS answer")
    if len(DNS_CACHE) > 512:
        DNS_CACHE.clear()
    DNS_CACHE[key] = (time.monotonic() + ttl, addresses)
    return addresses


def connect_public(host, port, interface_index, source_ip, dns_server):
    if port not in (80, 443):
        raise ValueError("Only public HTTP and HTTPS destinations are supported")
    last_error = OSError("No public IPv4 address")
    addresses = resolve_ipv4(host, interface_index, source_ip, dns_server)
    for ip in addresses[:4]:
        address = (ip, port)
        if not ipaddress.ip_address(address[0]).is_global:
            continue
        connection = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        connection.settimeout(8)
        try:
            # Windows IP_UNICAST_IF: DWORD interface index in network byte order.
            connection.setsockopt(socket.IPPROTO_IP, 31, struct.pack("!I", interface_index))
            connection.bind((source_ip, 0))
            connection.connect(address)
            return connection
        except OSError as error:
            last_error = error
            connection.close()
    raise last_error


def relay(client, upstream):
    client.settimeout(15)
    upstream.settimeout(15)
    readers = [client, upstream]
    while readers:
        ready, _, _ = select.select(readers, [], [], 90)
        if not ready:
            return
        for source in ready:
            target = upstream if source is client else client
            data = source.recv(65536)
            if not data:
                readers.remove(source)
                try:
                    target.shutdown(socket.SHUT_WR)
                except OSError:
                    pass
            else:
                target.sendall(data)


class Handler(socketserver.StreamRequestHandler):
    # Unbuffered reads prevent CONNECT bytes being stranded in an input buffer.
    rbufsize = 0

    def handle(self):
        upstream = None
        established = False
        try:
            self.connection.settimeout(10)
            request = self.rfile.readline(8193)
            if len(request) > 8192:
                return
            method, target, version = request.decode("iso-8859-1").strip().split(" ", 2)
            headers = []
            total = 0
            while True:
                line = self.rfile.readline(8193)
                total += len(line)
                if total > 65536 or not line:
                    return
                if line in (b"\r\n", b"\n"):
                    break
                if line.split(b":", 1)[0].lower() not in (b"proxy-authorization", b"proxy-connection"):
                    headers.append(line)
            if method == "GET" and target == "/__d3_health__":
                data = json.dumps(self.server.identity).encode("utf-8")
                self.wfile.write(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                 + str(len(data)).encode() + b"\r\nConnection: close\r\n\r\n" + data)
                return
            parsed = urllib.parse.urlsplit("//" + target if method == "CONNECT" else target)
            if not parsed.hostname or parsed.username or parsed.password:
                raise ValueError("Expected an HTTP proxy request")
            if method != "CONNECT" and parsed.scheme != "http":
                raise ValueError("HTTPS requires CONNECT")
            port = parsed.port or (443 if method == "CONNECT" else 80)
            upstream = connect_public(parsed.hostname, port, self.server.interface_index,
                                      self.server.source_ip, self.server.dns_server)
            if method == "CONNECT":
                self.wfile.write(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            else:
                path = urllib.parse.urlunsplit(("", "", parsed.path or "/", parsed.query, ""))
                upstream.sendall(f"{method} {path} {version}\r\n".encode("iso-8859-1")
                                 + b"".join(headers) + b"\r\n")
            established = True
            relay(self.connection, upstream)
        except (OSError, ValueError):
            if not established:
                try:
                    self.wfile.write(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                except OSError:
                    pass
        finally:
            if upstream is not None:
                upstream.close()


class Server(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--interface-index", type=int, required=True)
    parser.add_argument("--source-ip", required=True)
    parser.add_argument("--dns-server", required=True)
    parser.add_argument("--port", type=int, default=18868)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535 or args.interface_index < 1:
        parser.error("Invalid port or interface index")
    ipaddress.IPv4Address(args.source_ip)
    ipaddress.IPv4Address(args.dns_server)
    with Server(("127.0.0.1", args.port), Handler) as server:
        server.interface_index = args.interface_index
        server.source_ip = args.source_ip
        server.dns_server = args.dns_server
        server.identity = {"service": "d3-cdn-proxy", "version": 3, "interface_index": args.interface_index,
                           "source_ip": args.source_ip, "port": args.port, "dns_server": args.dns_server,
                           "route": "emulator_public_http_https_via_wlan"}
        print(json.dumps(server.identity), flush=True)
        server.serve_forever()


if __name__ == "__main__":
    main()
