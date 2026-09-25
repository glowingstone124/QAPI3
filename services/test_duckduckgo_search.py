import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.parse import quote

from duckduckgo_search import LiteParser, SearchError, destination, fetch_page, search


def page(rows, next_offset=None):
    html = "<html><body><table>" + "".join(
        f'<tr><td><a class="result-link" href="{url}">{title}</a></td></tr>'
        f'<tr><td class="result-snippet">{snippet}</td></tr>'
        for url, title, snippet in rows
    ) + "</table>"
    if next_offset is not None:
        html += (f'<form class="next_form"><input name="s" value="{next_offset}">'
                 '<input name="vqd" value="token"></form>')
    return html + "</body></html>"


class DuckDuckGoSearchTest(unittest.TestCase):
    def test_redirect_and_snippet_parsing(self):
        target = "https://example.org/path?q=中文"
        redirect = "//duckduckgo.com/l/?uddg=" + quote(target, safe="") + "&amp;rut=123"
        parser = LiteParser()
        parser.feed(page([(redirect, "A &amp; B", "Useful <b>text</b> here")]))
        self.assertEqual([{"title": "A & B", "url": target, "snippet": "Useful text here"}], parser.results)
        self.assertIsNone(destination("javascript:alert(1)"))
        self.assertIsNone(destination("http://[invalid"))

    def test_pagination_and_url_deduplication(self):
        calls = []

        def fetch(form):
            calls.append(form.copy())
            if len(calls) == 1:
                return page([("https://a.example/", "A", "one")], 10)
            return page([("https://a.example/", "A", "one"),
                         ("https://b.example/", "B", "two")])

        self.assertEqual(["https://a.example/", "https://b.example/"],
                         [item["url"] for item in search("query", fetch)])
        self.assertEqual({"q": "query", "s": "10", "vqd": "token"}, calls[1])

    def test_captcha_is_not_an_empty_search(self):
        with self.assertRaises(SearchError) as caught:
            search("query", lambda _: '<form id="challenge-form"></form>')
        self.assertEqual((429, "captcha"), (caught.exception.status, caught.exception.code))

    def test_unknown_markup_is_not_an_empty_search(self):
        with self.assertRaises(SearchError) as caught:
            search("query", lambda _: "<html>changed layout</html>")
        self.assertEqual("invalid_response", caught.exception.code)

    def test_known_empty_search(self):
        self.assertEqual([], search("query", lambda _: "<html>No results found</html>"))

    def test_upstream_forbidden_is_reported_as_rate_limit(self):
        with patch("duckduckgo_search.urlopen", side_effect=HTTPError("https://lite.duckduckgo.com/lite/", 403, "Forbidden", {}, None)):
            with self.assertRaises(SearchError) as caught:
                fetch_page({"q": "query"})
        self.assertEqual((429, "rate_limited"), (caught.exception.status, caught.exception.code))


if __name__ == "__main__":
    unittest.main()
