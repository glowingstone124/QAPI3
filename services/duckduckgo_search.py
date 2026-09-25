"""Small HTTP relay for DuckDuckGo Lite search. Run on a separate host from QAPI3."""

from __future__ import annotations

import argparse
import json
import re
import socket
from html.parser import HTMLParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import BoundedSemaphore
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlparse, urlencode
from urllib.request import Request, urlopen


LITE_URL = "https://lite.duckduckgo.com/lite/"
MAX_HTML_BYTES = 2_000_000
MAX_RESULTS = 8
MAX_PAGES = 3
SLOTS = BoundedSemaphore(4)


class SearchError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code


def destination(href: str) -> str | None:
    if href.startswith("//"):
        href = "https:" + href
    try:
        parsed = urlparse(href)
        if parsed.hostname in {"duckduckgo.com", "www.duckduckgo.com"} and parsed.path == "/l/":
            href = parse_qs(parsed.query).get("uddg", [""])[0]
            parsed = urlparse(href)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc or not parsed.hostname:
            return None
    except ValueError:
        return None
    return href[:2000]


class LiteParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.results: list[dict[str, str]] = []
        self.next_form: dict[str, str] = {}
        self._in_next_form = False
        self._link: dict[str, object] | None = None
        self._snippet: list[str] | None = None
        self._snippet_index: int | None = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        classes = (values.get("class") or "").split()
        if tag == "form" and "next_form" in classes:
            self._in_next_form = True
        elif tag == "input" and self._in_next_form:
            name = values.get("name")
            if name in {"s", "vqd"}:
                self.next_form[name] = values.get("value") or ""
        elif tag == "a" and "result-link" in classes:
            self._link = {"href": values.get("href") or "", "text": []}
        elif tag == "td" and "result-snippet" in classes:
            self._snippet = []
            self._snippet_index = len(self.results) - 1 if self.results else None

    def handle_data(self, data: str) -> None:
        if self._link is not None:
            self._link["text"].append(data)
        if self._snippet is not None:
            self._snippet.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag == "a" and self._link is not None:
            title = " ".join("".join(self._link["text"]).split())
            url = destination(str(self._link["href"]))
            if title and url:
                self.results.append({"title": title[:200], "url": url, "snippet": ""})
            self._link = None
        elif tag == "td" and self._snippet is not None:
            if self._snippet_index is not None:
                self.results[self._snippet_index]["snippet"] = " ".join("".join(self._snippet).split())[:600]
            self._snippet = None
            self._snippet_index = None
        elif tag == "form":
            self._in_next_form = False


def fetch_page(form: dict[str, str]) -> str:
    request = Request(
        LITE_URL,
        data=urlencode(form).encode("utf-8"),
        headers={"User-Agent": "Mozilla/5.0 (compatible; QAPI3Search/1.0)",
                 "Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=8) as response:
            body = response.read(MAX_HTML_BYTES + 1)
            if len(body) > MAX_HTML_BYTES:
                raise SearchError(502, "upstream_too_large", "DuckDuckGo response exceeded size limit")
            return body.decode(response.headers.get_content_charset() or "utf-8", errors="replace")
    except HTTPError as error:
        status = 429 if error.code in {403, 429} else 502
        raise SearchError(status, "rate_limited" if status == 429 else "upstream_error",
                          "DuckDuckGo request failed") from error
    except (TimeoutError, socket.timeout) as error:
        raise SearchError(504, "upstream_timeout", "DuckDuckGo request timed out") from error
    except URLError as error:
        raise SearchError(502, "upstream_error", "DuckDuckGo request failed") from error


def search(query: str, page_fetcher=fetch_page) -> list[dict[str, str]]:
    results: list[dict[str, str]] = []
    seen: set[str] = set()
    form = {"q": query}
    for _ in range(MAX_PAGES):
        try:
            html = page_fetcher(form)
        except SearchError:
            if results:
                break
            raise
        if re.search(r"anomaly-modal|challenge-form|Unfortunately.{0,100}bots", html, re.I | re.S):
            if results:
                break
            raise SearchError(429, "captcha", "DuckDuckGo requested bot verification")
        parsed = LiteParser()
        parsed.feed(html)
        previous_count = len(results)
        for item in parsed.results:
            if item["url"] not in seen:
                seen.add(item["url"])
                results.append(item)
                if len(results) == MAX_RESULTS:
                    return results
        if len(results) == previous_count:
            break
        offset = parsed.next_form.get("s", "")
        token = parsed.next_form.get("vqd", "")
        if not offset.isdigit() or not token or not parsed.results:
            break
        form = {"q": query, "s": offset, "vqd": token}
    if not results and not re.search(r"No results (?:found|\.?)", html, re.I):
        raise SearchError(502, "invalid_response", "DuckDuckGo returned no recognizable results")
    return results


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format: str, *args: object) -> None:
        # Query terms and result URLs do not belong in access logs.
        pass

    def send_json(self, status: int, data: dict[str, object]) -> None:
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        url = urlparse(self.path)
        if url.path == "/health":
            self.send_json(200, {"status": "ok"})
            return
        if url.path != "/search":
            self.send_json(404, {"error": "not_found"})
            return
        query = parse_qs(url.query).get("q", [""])[0].strip()
        if not 1 <= len(query) <= 500:
            self.send_json(400, {"error": "bad_query", "message": "q must contain 1 to 500 characters"})
            return
        if not SLOTS.acquire(blocking=False):
            self.send_json(503, {"error": "busy", "message": "Search service is busy"})
            return
        try:
            self.send_json(200, {"results": search(query)})
        except SearchError as error:
            self.send_json(error.status, {"error": error.code, "message": str(error)})
        except Exception:
            self.send_json(502, {"error": "search_unavailable", "message": "Search service failed"})
        finally:
            SLOTS.release()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9123)
    args = parser.parse_args()
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
