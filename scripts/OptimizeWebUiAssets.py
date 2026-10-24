"""Optional offline WebUI icon optimization (requires Pillow with WebP support).

Usage: python scripts/OptimizeWebUiAssets.py prepared-directory fresh-output-directory
Both directories contain local proprietary artwork and must stay out of Git.
"""
import hashlib
import io
import json
import sys
from pathlib import Path

from PIL import Image


def main():
    source, output = (Path(arg).resolve() for arg in sys.argv[1:])
    if output.exists() or output == source or source in output.parents:
        raise ValueError("Choose a fresh directory outside the input directory")
    manifest = json.loads((source / "manifest.json").read_text())
    output.mkdir(parents=True)
    (output / "icons").mkdir()
    replacements = {}
    paths = sorted(set().union(*(manifest[key].values() for key in ("items", "ui", "sprites"))))
    ui = set(manifest["ui"].values())
    sprites = set(manifest["sprites"].values())
    for name in paths:
        file = (source / name).resolve()
        if not file.is_relative_to(source):
            raise ValueError("Asset path escapes input")
        with Image.open(file) as original:
            image = original.convert("RGBA")
            image.thumbnail((96, 96) if name in sprites else (64, 64), Image.Resampling.LANCZOS)
            encoded = io.BytesIO()
            if name in ui:
                image.save(encoded, "PNG", optimize=True)
                extension = "png"
            else:
                image.save(encoded, "WEBP", quality=88, method=6, exact=True)
                extension = "webp"
        data = encoded.getvalue()
        target = "icons/" + hashlib.sha256(data).hexdigest() + "." + extension
        if not (output / target).exists():
            (output / target).write_bytes(data)
        replacements[name] = target
    for key in ("items", "ui", "sprites"):
        manifest[key] = {name: replacements[path] for name, path in manifest[key].items()}
    manifest["bytes"] = sum(p.stat().st_size for p in (output / "icons").iterdir())
    manifest["source"] = "Stock resolver and colorizer; WebP 88 at 64px, Sprite icons 96px; lossless PNG UI; SHA-256 deduplicated"
    (output / "manifest.json").write_text(json.dumps(manifest, sort_keys=True, separators=(",", ":")))
    print(f"Prepared {len(replacements)} source images; {manifest['bytes']:,} packaged image bytes")


if __name__ == "__main__":
    main()
