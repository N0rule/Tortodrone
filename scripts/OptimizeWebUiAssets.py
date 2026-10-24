"""Optional offline WebUI icon optimization (requires Pillow with WebP support).

Usage: python scripts/OptimizeWebUiAssets.py prepared-directory fresh-output-directory
Optional: append previous-source previous-output to reuse verified unchanged images.
All directories contain local proprietary artwork and must stay out of Git.
"""
import hashlib
import io
import json
import sys
from pathlib import Path

from PIL import Image


def main():
    if len(sys.argv) not in (3, 5):
        raise ValueError("Usage: source fresh-output [previous-source previous-output]")
    source, output = (Path(arg).resolve() for arg in sys.argv[1:3])
    cached = {}
    if len(sys.argv) == 5:
        previous_source, previous_output = (Path(arg).resolve() for arg in sys.argv[3:])
        raw = json.loads((previous_source / "manifest.json").read_text())
        prepared = json.loads((previous_output / "manifest.json").read_text())
        if prepared.get("source") != "Stock resolver and colorizer; WebP 88 at 64px, Sprite icons 96px; lossless PNG UI; SHA-256 deduplicated":
            raise ValueError("Cached pack uses different optimization settings")
        old_ui = set(raw["ui"].values()) | set(raw.get("symbols", {}).values())
        old_sprites = set(raw["sprites"].values())
        for group in ("items", "ui", "sprites", "symbols"):
            for key, name in raw.get(group, {}).items():
                target = prepared.get(group, {}).get(key)
                if not target:
                    continue
                file = (previous_output / target).resolve()
                if not file.is_relative_to(previous_output):
                    raise ValueError("Cached asset escapes input")
                data = file.read_bytes()
                if file.stem != hashlib.sha256(data).hexdigest():
                    raise ValueError("Cached asset fingerprint mismatch")
                cached[name] = (target, data, (name in old_ui, name in old_sprites))
    if output.exists() or output == source or source in output.parents:
        raise ValueError("Choose a fresh directory outside the input directory")
    manifest = json.loads((source / "manifest.json").read_text())
    output.mkdir(parents=True)
    (output / "icons").mkdir()
    replacements = {}
    paths = sorted(set().union(*(manifest.get(key, {}).values() for key in ("items", "ui", "sprites", "symbols"))))
    ui = set(manifest["ui"].values()) | set(manifest.get("symbols", {}).values())
    sprites = set(manifest["sprites"].values())
    for name in paths:
        file = (source / name).resolve()
        if not file.is_relative_to(source):
            raise ValueError("Asset path escapes input")
        if name in cached and cached[name][2] == (name in ui, name in sprites):
            target, data, _ = cached[name]
            if file.stem != hashlib.sha256(file.read_bytes()).hexdigest():
                raise ValueError("Source asset fingerprint mismatch")
            (output / target).write_bytes(data)
            replacements[name] = target
            continue
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
    for key in ("items", "ui", "sprites", "symbols"):
        manifest[key] = {name: replacements[path] for name, path in manifest.get(key, {}).items()}
    manifest["bytes"] = sum(p.stat().st_size for p in (output / "icons").iterdir())
    manifest["source"] = "Stock resolver and colorizer; WebP 88 at 64px, Sprite icons 96px; lossless PNG UI; SHA-256 deduplicated"
    (output / "manifest.json").write_text(json.dumps(manifest, sort_keys=True, separators=(",", ":")))
    print(f"Prepared {len(replacements)} source images; {manifest['bytes']:,} packaged image bytes")


if __name__ == "__main__":
    main()
