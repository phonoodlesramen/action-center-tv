# The website

The project's site, at <https://actioncentertv.com/>. It's plain HTML and CSS in `public/`,
with no build step, no JavaScript, and nothing loaded from other sites.

| File | What |
|---|---|
| `public/index.html` | The home page |
| `public/install/index.html` | Install, a shorter version of [`docs/install.md`](../docs/install.md) |
| `public/works-with/index.html` | "Does it work with…?": devices and apps |
| `public/privacy/index.html` | What the app and the site collect, from [`docs/security-and-privacy.md`](../docs/security-and-privacy.md) |
| `public/404.html` | The page GitHub Pages shows for a missing path |
| `public/assets/site.css` | The styles, in the app's colours |
| `public/sitemap.xml`, `public/robots.txt` | For search engines |
| `make_images.py` | Makes `public/assets/img/` from `docs/images/` and the launcher icon |
| `check_site.py` | The checks CI runs |

## Changing it

- **Look at it locally:** `python -m http.server 8000 --directory site/public`, then open
  <http://localhost:8000/>.
- **Check it:** `python site/check_site.py`. It checks:
  - each page's title, description, canonical link and Open Graph tags;
  - every internal link, image and `#anchor`;
  - alt text, JSON-LD, and the sitemap.
- **A new page:** copy an existing one, keeping its `<head>` tags, then add it to
  `sitemap.xml` and to the nav.
- **New screenshots:** after `docs/images/` changes, run `python site/make_images.py`
  (needs Pillow) and commit what it writes.
- **Keep it true:** the site repeats what the docs say. When a feature, a requirement or
  what leaves the TV changes, change the page too.
- **Publishing:** `.github/workflows/site.yml` publishes `public/` to GitHub Pages when a
  merge to `main` changes `site/`.
