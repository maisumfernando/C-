#!/usr/bin/env python3
"""Builds the public website (GitHub Pages) and an optional single-file preview.

Usage:
  python3 site/build.py --out _site [--apk path/to/app.apk]
  python3 site/build.py --artifact preview.html [--apk ...]

Everything the page needs is served from the site itself: no third-party requests.
"""
import argparse
import hashlib
import html
import json
import os
import re
import shutil
from datetime import date

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SITE = os.path.join(ROOT, "site")
OWNER, REPO = "maisumfernando", "C-"
PAGES_HOST = f"{OWNER}.github.io"
SOURCE_URL = f"https://github.com/{OWNER}/{REPO}"
RELEASES_URL = f"{SOURCE_URL}/releases"
WEBSITE = f"https://{PAGES_HOST}/{REPO}/"


def read_version():
    for line in open(os.path.join(ROOT, "version.properties"), encoding="utf-8"):
        if line.startswith("versionName="):
            name = line.split("=", 1)[1].strip()
            major, minor, patch = (int(x) for x in name.split("."))
            return name, major * 10000 + minor * 100 + patch
    raise SystemExit("versionName missing")


def changelog():
    """Converts CHANGELOG.md sections into (version, date, items) and HTML."""
    releases, current = [], None
    for raw in open(os.path.join(ROOT, "CHANGELOG.md"), encoding="utf-8"):
        line = raw.rstrip()
        m = re.match(r"^##\s+([0-9.]+)\s*(?:—|-)\s*(.+)$", line)
        if m:
            current = {"version": m.group(1), "date": m.group(2).strip(), "items": []}
            releases.append(current)
        elif line.startswith("- ") and current is not None:
            current["items"].append(line[2:].strip())
    parts = []
    for r in releases:
        items = "\n".join(f"          <li>{html.escape(i)}</li>" for i in r["items"])
        parts.append(
            f'        <article class="release">\n'
            f'          <div class="release__head"><span class="release__version">Versão {html.escape(r["version"])}</span>'
            f'<span class="release__date">{html.escape(r["date"])}</span></div>\n'
            f"          <ul>\n{items}\n          </ul>\n        </article>"
        )
    return releases, "\n".join(parts)


def render(apk, for_artifact=False):
    version, code = read_version()
    releases, changelog_html = changelog()
    template = open(os.path.join(SITE, "index.html"), encoding="utf-8").read()
    if apk:
        size_mb = os.path.getsize(apk) / 1_000_000
        sha = hashlib.sha256(open(apk, "rb").read()).hexdigest()
        apk_name = f"Teclado-do-Fernando-Godinho-{version}.apk"
        href = f"{WEBSITE}download/{apk_name}" if for_artifact else f"download/{apk_name}"
        label = f"Baixar para Android · v{version}"
        short = "Baixar"
        meta = f"{size_mb:.0f} MB · Android 8.0 ou superior · os modelos de IA são baixados pelo app"
    else:
        sha, apk_name, size_mb = "disponível na próxima versão publicada", None, 0
        href, label, short = RELEASES_URL, "Baixar para Android", "Baixar"
        meta = "Android 8.0 ou superior · os modelos de IA são baixados pelo app"
    values = {
        "VERSION": version, "SHA256": sha, "DOWNLOAD_HREF": href, "DOWNLOAD_LABEL": label,
        "DOWNLOAD_SHORT": short, "DOWNLOAD_META": meta, "CHANGELOG": changelog_html,
        "SOURCE_URL": SOURCE_URL, "RELEASES_URL": RELEASES_URL, "PAGES_HOST": PAGES_HOST,
    }
    for k, v in values.items():
        template = template.replace("{{" + k + "}}", v)
    leftover = re.findall(r"\{\{[A-Z_]+\}\}", template)
    if leftover:
        raise SystemExit(f"unfilled placeholders: {leftover}")
    return template, version, code, releases, apk_name, sha


def build_site(out, apk):
    page, version, code, releases, apk_name, sha = render(apk)
    if os.path.isdir(out):
        shutil.rmtree(out)
    os.makedirs(os.path.join(out, "download"))
    open(os.path.join(out, "index.html"), "w", encoding="utf-8").write(page)
    for name in ("styles.css", "app.js", "icon.svg"):
        shutil.copy(os.path.join(SITE, name), os.path.join(out, name))
    shutil.copytree(os.path.join(SITE, "fonts"), os.path.join(out, "fonts"))
    os.makedirs(os.path.join(out, "catalog"))
    shutil.copy(os.path.join(ROOT, "catalog", "models.json"), os.path.join(out, "catalog", "models.json"))
    notes = "; ".join(releases[0]["items"][:3]) if releases else ""
    json.dump({"versionName": version, "versionCode": code, "notes": notes[:480], "url": WEBSITE},
              open(os.path.join(out, "version.json"), "w", encoding="utf-8"), ensure_ascii=False)
    if apk:
        shutil.copy(apk, os.path.join(out, "download", apk_name))
        open(os.path.join(out, "download", apk_name + ".sha256"), "w").write(f"{sha}  {apk_name}\n")
    open(os.path.join(out, ".nojekyll"), "w").close()
    print(f"site built in {out} (version {version}, apk: {apk_name or 'none'})")


def build_artifact(path, apk):
    page, *_ = render(apk, for_artifact=True)
    head = re.search(r"<!-- HEAD -->(.*?)<!-- /HEAD -->", page, re.S).group(1)
    body = re.search(r"<!-- BODY -->(.*?)<!-- /BODY -->", page, re.S).group(1)
    css = open(os.path.join(SITE, "styles.css"), encoding="utf-8").read()
    js = open(os.path.join(SITE, "app.js"), encoding="utf-8").read()
    icon = open(os.path.join(SITE, "icon.svg"), encoding="utf-8").read()
    import base64
    icon_uri = "data:image/svg+xml;base64," + base64.b64encode(icon.encode()).decode()
    body = body.replace('src="icon.svg"', f'src="{icon_uri}"').replace('<script src="app.js"></script>', f"<script>{js}</script>")
    # The preview host serves fonts from Google Fonts; the real site self-hosts them.
    fonts = ('<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:opsz,wght@12..96,400..800'
             '&family=Figtree:ital,wght@0,400..700;1,400..700&family=JetBrains+Mono:wght@400..600&display=swap">')
    meta_desc = re.search(r'<meta name="description"[^>]*>', head).group(0)
    out = (
        "<title>Teclado do Fernando Godinho</title>\n"
        '<meta name="claude:viewport-fit-sides" content="cover">\n'
        f"{meta_desc}\n{fonts}\n<style>\n{css}\n</style>\n{body}"
    )
    open(path, "w", encoding="utf-8").write(out)
    print(f"preview written to {path}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out")
    ap.add_argument("--artifact")
    ap.add_argument("--apk")
    args = ap.parse_args()
    if args.out:
        build_site(args.out, args.apk)
    if args.artifact:
        build_artifact(args.artifact, args.apk)
