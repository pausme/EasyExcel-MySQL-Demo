#!/usr/bin/env python3
"""Concurrent load test for POST /api/excel/import."""

import argparse
import json
import os
import sys
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from http.client import HTTPConnection, HTTPSConnection
from urllib.parse import urlsplit


EXCEL_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"


def parse_args():
    parser = argparse.ArgumentParser(description="Excel import load test")
    parser.add_argument("--base-url", required=True, help="Application URL, e.g. http://<host>:<port>")
    parser.add_argument("--file", required=True, dest="file_path", help="Path to the xlsx file")
    parser.add_argument("--concurrency", type=int, default=1, help="Concurrent upload requests, default: 1")
    parser.add_argument("--requests", type=int, default=1, help="Total requests per level, default: 1")
    parser.add_argument("--matrix", help="Concurrency matrix, e.g. 1,2,4")
    parser.add_argument("--timeout", type=float, default=3600, help="Request timeout in seconds, default: 3600")
    parser.add_argument("--chunk-size", type=int, default=1024 * 1024,
                        help="Upload read chunk size in bytes, default: 1 MiB")
    parser.add_argument("--output", help="Write the complete result to a JSON file")
    parser.add_argument("--token", default=os.environ.get("API_SECURITY_DEMO_USER_TOKEN", ""),
                        help="Bearer token for auth-enabled deployments "
                             "(default: env API_SECURITY_DEMO_USER_TOKEN)")
    return parser.parse_args()


def parse_endpoint(base_url, token=""):
    parsed = urlsplit(base_url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise ValueError("--base-url must include an http/https scheme and hostname")

    endpoint = parsed.path.rstrip("/") + "/api/excel/import"
    connection_type = HTTPSConnection if parsed.scheme == "https" else HTTPConnection
    return connection_type, parsed.hostname, parsed.port, endpoint, token


def upload_file(endpoint, file_path, timeout, chunk_size, request_no):
    connection_type, hostname, port, path, token = endpoint
    boundary = "----CodexImportLoadTest-" + uuid.uuid4().hex
    filename = os.path.basename(file_path)
    prefix = (
        "--{0}\r\n"
        "Content-Disposition: form-data; name=\"file\"; filename=\"{1}\"\r\n"
        "Content-Type: {2}\r\n\r\n"
    ).format(boundary, filename, EXCEL_CONTENT_TYPE).encode("utf-8")
    suffix = ("\r\n--{0}--\r\n".format(boundary)).encode("ascii")
    file_size = os.path.getsize(file_path)
    content_length = len(prefix) + file_size + len(suffix)

    started = time.perf_counter()
    connection = connection_type(hostname, port, timeout=timeout)
    try:
        connection.putrequest("POST", path)
        connection.putheader("Content-Type", "multipart/form-data; boundary={0}".format(boundary))
        connection.putheader("Content-Length", str(content_length))
        connection.putheader("Connection", "close")
        if token:
            connection.putheader("Authorization", "Bearer {0}".format(token))
        connection.endheaders()
        connection.send(prefix)
        with open(file_path, "rb") as file_handle:
            while True:
                chunk = file_handle.read(chunk_size)
                if not chunk:
                    break
                connection.send(chunk)
        connection.send(suffix)

        response = connection.getresponse()
        response_body = response.read()
        elapsed_ms = round((time.perf_counter() - started) * 1000, 2)
        body_text = response_body.decode("utf-8", errors="replace")
        result = {
            "requestNo": request_no,
            "status": response.status,
            "elapsedMs": elapsed_ms,
            "response": parse_json_or_text(body_text),
        }
        if response.status < 200 or response.status >= 300:
            result["error"] = body_text[:1000]
        return result
    except Exception as exc:
        return {
            "requestNo": request_no,
            "status": None,
            "elapsedMs": round((time.perf_counter() - started) * 1000, 2),
            "error": "{0}: {1}".format(type(exc).__name__, str(exc)),
        }
    finally:
        connection.close()


def parse_json_or_text(text):
    try:
        return json.loads(text)
    except ValueError:
        return text[:1000]


def run_level(endpoint, file_path, concurrency, request_count, timeout, chunk_size):
    started = time.perf_counter()
    results = []
    worker_count = min(concurrency, request_count)
    with ThreadPoolExecutor(max_workers=worker_count, thread_name_prefix="import-load-") as executor:
        futures = [
            executor.submit(upload_file, endpoint, file_path, timeout, chunk_size, request_no)
            for request_no in range(1, request_count + 1)
        ]
        for future in as_completed(futures):
            results.append(future.result())

    results.sort(key=lambda item: item["requestNo"])
    elapsed_seconds = time.perf_counter() - started
    success_results = [
        item for item in results
        if isinstance(item.get("status"), int) and 200 <= item["status"] < 300
    ]
    imported_rows = sum(
        item.get("response", {}).get("imported", 0)
        for item in success_results
        if isinstance(item.get("response"), dict)
    )
    summary = {
        "concurrency": concurrency,
        "requests": request_count,
        "success": len(success_results),
        "failed": request_count - len(success_results),
        "elapsedSeconds": round(elapsed_seconds, 3),
        "requestsPerSecond": round(request_count / elapsed_seconds, 3) if elapsed_seconds else 0,
        "importedRows": imported_rows,
        "rowsPerSecond": round(imported_rows / elapsed_seconds, 3) if elapsed_seconds else 0,
        "results": results,
    }
    return summary


def main():
    args = parse_args()
    if args.concurrency < 1 or args.requests < 1:
        raise ValueError("--concurrency and --requests must be greater than 0")
    if args.timeout <= 0 or args.chunk_size <= 0:
        raise ValueError("--timeout and --chunk-size must be greater than 0")
    if not os.path.isfile(args.file_path):
        raise FileNotFoundError(args.file_path)

    endpoint = parse_endpoint(args.base_url, args.token)
    concurrency_levels = [args.concurrency]
    if args.matrix:
        concurrency_levels = [int(value.strip()) for value in args.matrix.split(",") if value.strip()]
        if not concurrency_levels or any(value < 1 for value in concurrency_levels):
            raise ValueError("--matrix must be a comma-separated list of positive integers")

    report = {
        "baseUrl": args.base_url,
        "file": os.path.abspath(args.file_path),
        "fileSizeBytes": os.path.getsize(args.file_path),
        "levels": [],
    }
    for concurrency in concurrency_levels:
        print("\n=== concurrency={0}, requests={1} ===".format(concurrency, args.requests))
        summary = run_level(endpoint, args.file_path, concurrency, args.requests, args.timeout, args.chunk_size)
        report["levels"].append(summary)
        print(json.dumps(summary, ensure_ascii=False, indent=2))

    if args.output:
        with open(args.output, "w", encoding="utf-8") as output_file:
            json.dump(report, output_file, ensure_ascii=False, indent=2)
        print("\nResults written to: {0}".format(os.path.abspath(args.output)))

    return 0 if all(level["failed"] == 0 for level in report["levels"]) else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError) as exc:
        print("Load test failed: {0}".format(exc), file=sys.stderr)
        sys.exit(2)
