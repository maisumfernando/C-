#!/usr/bin/env python3
"""Regenerates the website screenshots (site/img/*.webp) from the Robolectric captures.

Run after `./gradlew :app:testDebugUnitTest` (captures land in app/build/screenshots/).
Needs Pillow with WebP support (developer tool only; CI does not run it).
"""
import os
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "build", "screenshots")
OUT = os.path.join(ROOT, "site", "img")
WIDTH = 600
POPUP_BAND = 120  # 40 dp at xxhdpi: the empty space above the keyboard reserved for key previews

KEYBOARD = {
    "keyboard_light": "kbd-light.webp",
    "keyboard_dark": "kbd-dark.webp",
    "dictation_listening": "kbd-dictation.webp",
    "dictation_polishing_dark": "kbd-polishing-dark.webp",
    "dictation_code": "kbd-code.webp",
    "dictation_password": "kbd-password.webp",
    "writing_tools": "kbd-tools.webp",
    "keyboard_one_handed": "kbd-onehanded.webp",
}
SCREENS = {
    "app_home": "app-home.webp",
    "app_home_dark": "app-home-dark.webp",
    "app_models": "app-models.webp",
    "app_onboarding": "app-onboarding.webp",
}
# The speed test is rendered on a taller screen; the notice shown when no model is installed
# (always the case in tests) is cut out so the intro sits right above the results.
BENCHMARK = {"app_benchmark": "app-benchmark.webp", "app_benchmark_dark": "app-benchmark-dark.webp"}


def scaled(im):
    return im.resize((WIDTH, round(im.height * WIDTH / im.width)), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, dst in KEYBOARD.items():
        im = Image.open(os.path.join(SRC, name + ".png")).convert("RGB")
        scaled(im.crop((0, POPUP_BAND, im.width, im.height))).save(os.path.join(OUT, dst), "WEBP", quality=84, method=6)
    for name, dst in SCREENS.items():
        im = Image.open(os.path.join(SRC, name + ".png")).convert("RGB")
        scaled(im).save(os.path.join(OUT, dst), "WEBP", quality=82, method=6)
    for name, dst in BENCHMARK.items():
        im = Image.open(os.path.join(SRC, name + ".png")).convert("RGB")
        top, rest = im.crop((0, 0, im.width, 820)), im.crop((0, 1190, im.width, im.height))
        joined = Image.new("RGB", (im.width, top.height + rest.height))
        joined.paste(top, (0, 0))
        joined.paste(rest, (0, top.height))
        scaled(joined).crop((0, 0, WIDTH, 1300)).save(os.path.join(OUT, dst), "WEBP", quality=82, method=6)
    print(f"images written to {OUT}")


if __name__ == "__main__":
    main()
