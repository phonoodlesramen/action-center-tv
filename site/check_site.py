"""Checks the site before it's published. CI runs it; so can you:

    python site/check_site.py

Standard library only. For each page in site/public it checks:
- the search basics: lang, one <h1>, a title and a description of a sensible length, and a
  canonical link and Open Graph tags that point at the page itself on SITE;
- that every link, image and stylesheet inside the site exists, and every anchor (#id) is on
  its page;
- that images have alt text and a size, that JSON-LD parses, and that nothing loads over
  plain http or from another site;
- that sitemap.xml lists every page but the 404, and robots.txt points to it.
"""

import json
import re
import sys
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urldefrag, urljoin, urlparse

SITE = "https://actioncentertv.com"
PUBLIC = Path(__file__).resolve().parent / "public"
NOT_FOUND = "404.html"


class Page(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.lang = None
        self.title = ""
        self.meta: dict[str, str] = {}
        self.links: list[tuple[str, str]] = []  # (what, url)
        self.ids: set[str] = set()
        self.images: list[dict[str, str | None]] = []
        self.h1 = 0
        self.json_ld: list[str] = []
        self._in_title = False
        self._in_json_ld = False
        self._json_buffer = ""

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if "id" in a and a["id"]:
            self.ids.add(a["id"])
        if tag == "html":
            self.lang = a.get("lang")
        elif tag == "title":
            self._in_title = True
        elif tag == "h1":
            self.h1 += 1
        elif tag == "meta":
            key = a.get("name") or a.get("property")
            if key:
                self.meta[key] = a.get("content") or ""
        elif tag == "link":
            if a.get("rel") == "canonical":
                self.meta["canonical"] = a.get("href") or ""
            elif a.get("href"):
                self.links.append(("link", a["href"]))
        elif tag == "a" and a.get("href"):
            self.links.append(("a", a["href"]))
        elif tag == "img":
            self.images.append(a)
            if a.get("src"):
                self.links.append(("img", a["src"]))
            for part in (a.get("srcset") or "").split(","):
                if part.strip():
                    self.links.append(("img", part.split()[0]))
        elif tag == "script":
            if a.get("type") == "application/ld+json":
                self._in_json_ld = True
                self._json_buffer = ""
            elif a.get("src"):
                self.links.append(("script", a["src"]))

    def handle_endtag(self, tag):
        if tag == "title":
            self._in_title = False
        elif tag == "script" and self._in_json_ld:
            self._in_json_ld = False
            self.json_ld.append(self._json_buffer)

    def handle_data(self, data):
        if self._in_title:
            self.title += data
        if self._in_json_ld:
            self._json_buffer += data


def page_url(path: Path) -> str:
    rel = path.relative_to(PUBLIC).as_posix()
    if rel == "index.html":
        return SITE + "/"
    if rel.endswith("/index.html"):
        return SITE + "/" + rel[: -len("index.html")]
    return SITE + "/" + rel


def target_file(url: str) -> Path | None:
    """The file a URL on SITE serves, or None if it's not on SITE."""
    parsed = urlparse(url)
    if f"{parsed.scheme}://{parsed.netloc}" != SITE:
        return None
    path = PUBLIC / parsed.path.lstrip("/")
    if parsed.path.endswith("/") or parsed.path == "":
        path = path / "index.html"
    return path


def main() -> int:
    errors: list[str] = []
    pages = sorted(PUBLIC.rglob("*.html"))
    parsed: dict[Path, Page] = {}
    for path in pages:
        page = Page()
        page.feed(path.read_text(encoding="utf-8"))
        parsed[path] = page

    for path, page in parsed.items():
        name = path.relative_to(PUBLIC).as_posix()
        here = page_url(path)

        def err(msg: str) -> None:
            errors.append(f"{name}: {msg}")

        if page.lang != "en":
            err('<html> needs lang="en"')
        if page.h1 != 1:
            err(f"has {page.h1} <h1> elements, not 1")
        title = page.title.strip()
        if not 20 <= len(title) <= 65:
            err(f"title is {len(title)} characters (want 20-65): {title!r}")
        description = page.meta.get("description", "")
        if not 70 <= len(description) <= 170:
            err(f"description is {len(description)} characters (want 70-170)")

        if name == NOT_FOUND:
            if "noindex" not in page.meta.get("robots", ""):
                err("the 404 page needs <meta name=robots content=noindex>")
        else:
            if page.meta.get("canonical") != here:
                err(f"canonical is {page.meta.get('canonical')!r}, want {here!r}")
            if page.meta.get("og:url") != here:
                err(f"og:url is {page.meta.get('og:url')!r}, want {here!r}")
            for key in ["og:title", "og:description", "og:image", "og:image:alt", "twitter:card"]:
                if not page.meta.get(key):
                    err(f"missing {key}")
            image = target_file(page.meta.get("og:image", ""))
            if image is None or not image.is_file():
                err(f"og:image {page.meta.get('og:image')!r} isn't a file on the site")

        for block in page.json_ld:
            try:
                json.loads(block)
            except json.JSONDecodeError as e:
                err(f"JSON-LD doesn't parse: {e}")

        for image in page.images:
            if image.get("alt") is None:
                err(f"<img src={image.get('src')!r}> has no alt")
            if not image.get("width") or not image.get("height"):
                err(f"<img src={image.get('src')!r}> needs width and height")

        for what, url in page.links:
            if url.startswith(("mailto:", "tel:")):
                continue
            if url.startswith("http://"):
                err(f"plain http link: {url}")
                continue
            absolute = urljoin(here, url)
            target, fragment = urldefrag(absolute)
            if what != "a" and not absolute.startswith(SITE):
                err(f"{what} loads from another site: {url}")
                continue
            file = target_file(target)
            if file is None:
                continue  # a link to another site
            if not file.is_file():
                err(f"broken link: {url} (no {file.relative_to(PUBLIC).as_posix()})")
                continue
            if fragment and file.suffix == ".html":
                other = parsed.get(file)
                if other is not None and fragment not in other.ids:
                    err(f"broken anchor: {url} (no id {fragment!r})")

    sitemap = PUBLIC / "sitemap.xml"
    listed = set(re.findall(r"<loc>([^<]+)</loc>", sitemap.read_text(encoding="utf-8")))
    wanted = {page_url(p) for p in pages if p.name != NOT_FOUND}
    for url in sorted(wanted - listed):
        errors.append(f"sitemap.xml: missing {url}")
    for url in sorted(listed - wanted):
        errors.append(f"sitemap.xml: lists {url}, which isn't a page")
    robots = (PUBLIC / "robots.txt").read_text(encoding="utf-8")
    if f"Sitemap: {SITE}/sitemap.xml" not in robots:
        errors.append("robots.txt: needs the Sitemap line")

    for line in errors:
        print(line)
    print(f"{len(pages)} pages, {len(errors)} problem(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
