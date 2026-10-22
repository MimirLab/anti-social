#!/usr/bin/env python3
"""Trace the supplied anti social artwork into Android vector resources.

Build-time only: requires Pillow and potrace. No generated/reimagined artwork,
runtime bitmap decoding, fonts, or network requests are needed by the app.
Intermediate traces stay on the external build volume.
"""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
from PIL import Image, ImageChops

REPO = Path(__file__).resolve().parents[1]
BUILD = Path(os.environ.get("ANTISOCIAL_BUILD_ROOT", "/run/media/red/RedtopStorage/DarwinBuild/LiberBuild"))
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--potrace", default="potrace")
args = parser.parse_args()
source = REPO / "branding/anti-social-reference.png"
assert hashlib.sha256(source.read_bytes()).hexdigest() == "250eb505cb727cfbf333cea41ebd73c55034777d41332f7ad904c17fd4e6d230", "The approved brand reference changed"
work = BUILD / "rebrand/vector-traces"
work.mkdir(parents=True, exist_ok=True)
res = REPO / "app/src/main/res"
image = Image.open(source).convert("L")


def trace(name, rectangle):
    pixels = image.crop(rectangle).point(lambda value: 255 if value >= 128 else 0, "1")
    pixels = pixels.crop(ImageChops.invert(pixels).getbbox())
    pbm = work / f"{name}.pbm"
    svg = work / f"{name}.svg"
    pixels.save(pbm)
    subprocess.run([args.potrace, "--svg", "--opttolerance", "0.2", "--output", str(svg), str(pbm)], check=True)
    paths = ET.parse(svg).findall(".//{http://www.w3.org/2000/svg}path")
    return pixels.width, pixels.height, [path.attrib["d"].replace("\n", " ") for path in paths]


def vector(paths, viewport, size, transform, background=None):
    width, height = viewport
    output = [f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{size[0]}dp" android:height="{size[1]}dp" android:viewportWidth="{width}" android:viewportHeight="{height}">']
    if background:
        output.append(f'<path android:fillColor="{background}" android:pathData="M0,0H{width}V{height}H0Z"/>')
    output.append('<group ' + " ".join(f'android:{key}="{value:.7f}"' for key, value in transform.items()) + '>')
    output.extend(f'<path android:fillColor="#FFFFFF" android:pathData="{path}"/>' for path in paths)
    output.append('</group></vector>\n')
    return "\n".join(output)


def write(relative, content):
    destination = res / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(content)


w, h, paths = trace("break-free", (330, 76, 986, 708))
write("drawable/ic_antisocial.xml", vector(paths, (w, h), (48, 48), {"scaleX": .1, "scaleY": -.1, "translateY": h}))
scale = 60 / max(w, h)
foreground = vector(paths, (108, 108), (108, 108), {"scaleX": .1 * scale, "scaleY": -.1 * scale, "translateX": (108 - w * scale) / 2, "translateY": (108 + h * scale) / 2})
write("drawable/launcher_foreground.xml", foreground)
(REPO / "branding/anti-social-icon.svg").write_text(
    '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108">'
    '<rect width="108" height="108" rx="24" fill="#111315"/>'
    f'<g fill="#fff" transform="translate({(108-w*scale)/2} {(108+h*scale)/2}) scale({.1*scale} {-.1*scale})">'
    + ''.join(f'<path d="{path}"/>' for path in paths) + '</g></svg>\n')
write("mipmap-anydpi/ic_launcher.xml", vector(paths, (108, 108), (108, 108), {"scaleX": .1 * scale, "scaleY": -.1 * scale, "translateX": (108 - w * scale) / 2, "translateY": (108 + h * scale) / 2}, "#111315"))
write("mipmap-anydpi/ic_launcher_round.xml", '<inset xmlns:android="http://schemas.android.com/apk/res/android" android:drawable="@mipmap/ic_launcher"/>\n')
for qualifier in ("v26", "v33"):
    monochrome = '<monochrome android:drawable="@drawable/launcher_foreground"/>' if qualifier == "v33" else ""
    adaptive = '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@color/launcher_background"/><foreground android:drawable="@drawable/launcher_foreground"/>' + monochrome + '</adaptive-icon>\n'
    for name in ("ic_launcher", "ic_launcher_round"):
        write(f"mipmap-anydpi-{qualifier}/{name}.xml", adaptive)
w, h, paths = trace("wordmark", (188, 716, 1064, 872))
write("drawable/brand_wordmark.xml", vector(paths, (w, h), (w * 24 / h, 24), {"scaleX": .1, "scaleY": -.1, "translateY": h}))
print("Traced the supplied break-free emblem and wordmark into native Android vectors.")
