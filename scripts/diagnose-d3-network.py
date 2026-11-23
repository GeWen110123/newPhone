"""Check the original D3 login-plugin URLs without changing network or app data."""

import argparse
import concurrent.futures
import datetime
import json
from pathlib import Path
import socket
import ssl
import urllib.error
import urllib.parse
import urllib.request


# Observed in the app's read-only downloader snapshot on 2026-11-20.
RESOURCE = "/obj/appeye/plugin/1128/2eeb5f84419c262f4174eb7199110cc0"
PRIMARY = "n98-lf-aweme-saveu-ncdn.bytetos.com"
BACKUP = "lf5-aweme-saveu.bytetos.com"
URLS = {
    "plugin_primary": "https://" + PRIMARY + RESOURCE,
    "plugin_backup_https": "https://" + BACKUP + RESOURCE,
    "plugin_backup_http": "http://" + BACKUP + RESOURCE,
    "control_https": "https://www.baidu.com/",
}


def check_url(item, timeout, via_proxy=False):
    name, url = item
    result = {"name": name, "host": urllib.parse.urlsplit(url).hostname}
    # Match the emulator's direct connection; do not inherit a shell proxy.
    proxies = {"http": "http://127.0.0.1:18868", "https": "http://127.0.0.1:18868"} if via_proxy else {}
    opener = urllib.request.build_opener(urllib.request.ProxyHandler(proxies))
    request = urllib.request.Request(url, headers={"Range": "bytes=0-1023"})
    try:
        with opener.open(request, timeout=timeout) as response:
            sample = response.read(1024)
            size = len(sample)
            expected_body = not name.startswith("plugin_") or sample.startswith(b"PK\x03\x04")
            result.update(
                http_status=response.status,
                sample_bytes=size,
                content_type=response.headers.get("Content-Type"),
                available=response.status in (200, 206) and size > 0 and expected_body,
            )
    except urllib.error.HTTPError as error:
        result.update(http_status=error.code, available=False)
        error.close()
    except (OSError, urllib.error.URLError) as error:
        result.update(error=str(error), available=False)
    return result


def check_tls_reply(host, timeout):
    incoming, outgoing = ssl.MemoryBIO(), ssl.MemoryBIO()
    tls = ssl.create_default_context().wrap_bio(
        incoming, outgoing, server_side=False, server_hostname=host
    )
    try:
        tls.do_handshake()
    except ssl.SSLWantReadError:
        pass
    result = {"host": host}
    try:
        with socket.create_connection((host, 443), timeout=timeout) as connection:
            result["remote_ip"] = connection.getpeername()[0]
            connection.sendall(outgoing.read())
            first = connection.recv(512)
            # A plain HTTP response to ClientHello precedes any URL request.
            # It cannot establish that the requested plugin file is missing.
            if first.startswith(b"HTTP/"):
                result.update(
                    reply="plaintext_http_before_tls",
                    response_line=first.split(b"\r\n", 1)[0].decode("ascii"),
                )
            elif first[:1] in (b"\x15", b"\x16"):
                result["reply"] = "tls_record"
            else:
                result["reply"] = "empty_or_unrecognized"
    except OSError as error:
        result["error"] = str(error)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--timeout", type=float, default=8)
    parser.add_argument("--via-d3-proxy", action="store_true", help="Test the configured local CDN helper")
    args = parser.parse_args()
    if not 1 <= args.timeout <= 30:
        parser.error("--timeout must be between 1 and 30 seconds")
    now = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8)))
    output = args.output or (
        Path(__file__).resolve().parents[1]
        / "target" / "setup" / ("d3-network-" + now.strftime("%Y%m%d-%H%M%S-%f") + ".json")
    )
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        requests = [pool.submit(check_url, item, args.timeout, args.via_d3_proxy) for item in URLS.items()]
        replies = [pool.submit(check_tls_reply, host, args.timeout) for host in (PRIMARY, BACKUP)]
        resources = [request.result() for request in requests]
        handshakes = [reply.result() for reply in replies]
    available = any(r["available"] for r in resources if r["name"].startswith("plugin_"))
    report = {
        "checked_at": now.isoformat(),
        "plugin": "m.l.aliyun_verify (observed original download URLs)",
        "plugin_resource_available": available,
        "request_route": "d3-cdn-proxy" if args.via_d3_proxy else "default Windows route",
        "resources": resources,
        "handshake_route": "default Windows route (independent comparison)",
        "handshakes": handshakes,
        "scope": "Resource connectivity only; does not verify account login or video stability.",
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    for result in resources:
        status = result.get("http_status", result.get("error", "unknown"))
        print(f'{result["name"]}: {status}; available={result["available"]}')
    for result in handshakes:
        print(f'{result["host"]}: {result.get("reply", result.get("error"))}')
    print(f"REPORT: {output.resolve()}")
    return 0 if available else 2


if __name__ == "__main__":
    raise SystemExit(main())
