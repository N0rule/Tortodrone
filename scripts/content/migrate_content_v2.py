#!/usr/bin/env python3
"""Convert a v1 flat mission catalogue to the v2 per-mission folder layout.

The converter is offline and deterministic: it reads only the checked-in v1 tree,
writes and validates a staging tree, then swaps it into place. Running it on an
already-v2 tree validates that tree and changes nothing. It never invents content;
every mission row, grant, comlink and route is copied from the v1 files.
"""
import argparse, hashlib, pathlib, shutil, sys, tempfile, yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
FAMILIES = ("rank", "prestige", "expansion", "hidden")
MISSION_ID = __import__("re").compile(r"[a-z][a-z0-9_]{0,127}")
HEADERS = {
    "mission.yml": "# Imported mission row; regenerate with scripts/content/import_missions.py.",
    "grants.yml": "# Imported concrete mission rewards; regenerate with scripts/content/import_mission_rewards.py.",
    "comlink.yml": "# Imported mission comlink dialogue; regenerate with scripts/content/import_mission_comlinks.py.",
    "route.yml": "# Imported mission gate route; regenerate with scripts/content/import_mission_routes.py.",
}
SHARED = {
    "groups.yml": ("groups", "# Imported mission groups; regenerate with scripts/content/import_missions.py."),
    "properties.yml": ("properties", "# Imported mission prestige/crown properties; regenerate with scripts/content/import_missions.py."),
}


def fail(message):
    raise SystemExit(message)


def load(path):
    try:
        with path.open(encoding="utf-8") as source:
            return yaml.safe_load(source)
    except yaml.YAMLError as invalid:
        fail(f"{path}: {invalid}")


def section(path, name, required=True):
    if not path.is_file():
        if required:
            fail(f"missing {path}")
        return {}
    data = load(path)
    if not isinstance(data, dict) or list(data) != [name]:
        fail(f"{path}: expected one {name} section")
    rows = data[name]
    if not isinstance(rows, dict):
        fail(f"{path}: expected a mapping under {name}")
    return rows


def dump(path, payload, header, sort_keys, unicode):
    path.parent.mkdir(parents=True, exist_ok=True)
    body = yaml.safe_dump(payload, sort_keys=sort_keys, allow_unicode=unicode,
                          width=1000, default_flow_style=False)
    path.write_text(header.strip() + "\n\n" + body, encoding="utf-8")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_v1_v2(content):
    """Read either layout and return the five domain payloads as plain dicts."""
    marker = content / "format.txt"
    format_value = 1
    if marker.exists():
        text = marker.read_text(encoding="utf-8")
        if text == "2\n":
            format_value = 2
        elif text != "1\n":
            fail(f"{marker}: expected exactly one line containing 1 or 2")
    missions = {}
    groups, properties, grants, comlinks, routes = {}, {}, {}, {}, {}
    if format_value == 1:
        directory = content / "missions"
        if directory.is_dir():
            for family in FAMILIES:
                path = directory / f"{family}.yml"
                if not path.is_file():
                    continue
                for mission_id, row in section(path, "missions").items():
                    if mission_id in missions:
                        fail(f"{path}: duplicate mission id {mission_id}")
                    missions[mission_id] = row
            groups = section(directory / "groups.yml", "groups", required=False)
            properties = section(directory / "properties.yml", "properties", required=False)
            grants = section(directory / "grants.yml", "grants", required=False)
            comlinks = section(directory / "comlinks.yml", "comlinks", required=False)
            routes = section(directory / "routes.yml", "routes", required=False)
        return format_value, missions, groups, properties, grants, comlinks, routes
    directory = content / "missions"
    shared = content / "shared" / "missions"
    for name, (kind, _) in SHARED.items():
        if name == "groups.yml":
            groups = section(shared / name, kind, required=False)
        else:
            properties = section(shared / name, kind, required=False)
    if directory.is_dir():
        for folder in sorted(directory.iterdir()):
            if folder.is_file():
                fail(f"{directory}: mixed v1 flat mission files and v2 mission folders: {folder.name}")
            if not folder.is_dir():
                fail(f"{directory}: invalid mission entry: {folder.name}")
            mission_id = folder.name
            if not MISSION_ID.fullmatch(mission_id):
                fail(f"{directory}: invalid mission folder name {mission_id}")
            allowed = {"mission.yml", "grants.yml", "comlink.yml", "route.yml"}
            for entry in sorted(folder.iterdir()):
                if not entry.is_file() or entry.name not in allowed:
                    fail(f"{folder}: unknown mission file {entry.name}")
            if not (folder / "mission.yml").is_file():
                fail(f"{folder}: missing mission.yml")
            row = section(folder / "mission.yml", "missions")
            if list(row) != [mission_id]:
                fail(f"{folder}/mission.yml: mission id does not match folder {mission_id}")
            missions[mission_id] = row[mission_id]
            if (folder / "grants.yml").is_file():
                grants[mission_id] = section(folder / "grants.yml", "grants")[mission_id]
            if (folder / "comlink.yml").is_file():
                comlinks[mission_id] = section(folder / "comlink.yml", "comlinks")[mission_id]
            if (folder / "route.yml").is_file():
                routes[mission_id] = section(folder / "route.yml", "routes")[mission_id]
    return format_value, missions, groups, properties, grants, comlinks, routes


def copy_tree(source, target):
    target.mkdir(parents=True, exist_ok=True)
    for path in sorted(source.rglob("*")):
        relative = path.relative_to(source)
        if relative == pathlib.Path("missions") or relative.parts[0] == "missions":
            continue
        if relative == pathlib.Path("format.txt"):
            continue
        if path.is_symlink():
            fail(f"symlink in content: {path}")
        destination = target / relative
        if path.is_dir():
            destination.mkdir(parents=True, exist_ok=True)
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, destination)


def write_v2(staging, missions, groups, properties, grants, comlinks, routes):
    (staging / "format.txt").write_text("2\n", encoding="utf-8")
    for name, (kind, header) in SHARED.items():
        rows = groups if kind == "groups" else properties
        if rows:
            dump(staging / "shared" / "missions" / name, {kind: dict(sorted(rows.items()))}, header,
                 sort_keys=False, unicode=False)
    for mission_id in sorted(missions):
        folder = staging / "missions" / mission_id
        dump(folder / "mission.yml", {"missions": {mission_id: missions[mission_id]}}, HEADERS["mission.yml"],
             sort_keys=False, unicode=False)
        if mission_id in grants:
            dump(folder / "grants.yml", {"grants": {mission_id: grants[mission_id]}}, HEADERS["grants.yml"],
                 sort_keys=True, unicode=True)
        if mission_id in comlinks:
            dump(folder / "comlink.yml", {"comlinks": {mission_id: comlinks[mission_id]}}, HEADERS["comlink.yml"],
                 sort_keys=True, unicode=True)
        if mission_id in routes:
            dump(folder / "route.yml", {"routes": {mission_id: routes[mission_id]}}, HEADERS["route.yml"],
                 sort_keys=True, unicode=True)


def regenerate_manifest(staging):
    path = staging / "_internal" / "default-source.properties"
    if not path.is_file():
        fail(f"missing source manifest: {path}")
    owned, source = {}, {}
    format_line = None
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line:
            continue
        key, _, value = line.partition("=")
        if key == "format":
            format_line = line
            continue
        if key.startswith("owned."):
            relative = key[len("owned."):]
            if relative == "missions" or relative.startswith("shared/missions/") or relative == "format.txt":
                continue
            if relative.startswith("missions/"):
                # Keep v1 flat files as removal pins so a previously prepared output
                # cannot retain them beside the new mission folders. Owner edits still
                # conflict instead of being removed.
                if len(relative.split("/")) == 2 and relative.endswith(".yml"):
                    owned[key] = value
                continue
            owned[key] = value
        elif key.startswith("source."):
            relative = key[len("source."):]
            if relative == "missions" or relative.startswith("missions/") or relative.startswith("shared/missions/") or relative == "format.txt":
                continue
            source[key] = value
        else:
            fail(f"{path}: unexpected manifest key {key}")
    if format_line != "format=1":
        fail(f"{path}: expected format=1")
    for file in sorted((staging / "missions").rglob("*.yml")) + sorted((staging / "shared" / "missions").glob("*.yml")):
        relative = file.relative_to(staging).as_posix()
        owned[f"owned.{relative}"] = digest(file)
        source[f"source.{relative}"] = digest(file)
    lines = [format_line]
    lines += [f"{key}={owned[key]}" for key in sorted(owned)]
    lines += [f"{key}={source[key]}" for key in sorted(source)]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def verify(staging, expected):
    actual = read_v1_v2(staging)
    if actual[0] != 2:
        fail(f"{staging}: staged tree is not format 2")
    for index, name in enumerate(("missions", "groups", "properties", "grants", "comlinks", "routes"), start=1):
        if actual[index] != expected[index]:
            fail(f"{staging}: converted {name} differ from v1")
    manifest = staging / "_internal" / "default-source.properties"
    for line in manifest.read_text(encoding="utf-8").splitlines():
        if not line.startswith("source."):
            continue
        relative = line[len("source."):].partition("=")[0]
        expected_digest = line.rsplit("=", 1)[1]
        file = staging / relative
        if not file.is_file() or digest(file) != expected_digest:
            fail(f"{manifest}: digest mismatch for {relative}")


def swap(content, staging):
    backup = content.with_name(content.name + ".v1-backup")
    if backup.exists():
        fail(f"refusing to overwrite existing backup: {backup}")
    content.rename(backup)
    try:
        staging.rename(content)
    except Exception:
        backup.rename(content)
        raise
    shutil.rmtree(backup)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--content", type=pathlib.Path, default=ROOT / "content")
    args = parser.parse_args()
    content = args.content.resolve()
    if not content.is_dir():
        fail(f"content directory is missing: {content}")
    format_value, missions, groups, properties, grants, comlinks, routes = read_v1_v2(content)
    if format_value == 2:
        verify(content, (2, missions, groups, properties, grants, comlinks, routes))
        print(f"PASS: content is already format 2; {len(missions)} missions unchanged")
        return
    if not missions:
        fail(f"{content / 'missions'}: no v1 missions to convert")
    for kind, rows in (("grants", grants), ("comlinks", comlinks), ("routes", routes)):
        unknown = set(rows) - set(missions)
        if unknown:
            fail(f"{content}: {kind} reference missing missions: {', '.join(sorted(unknown))}")
    staging = pathlib.Path(tempfile.mkdtemp(prefix="content-v2-", dir=content.parent))
    try:
        copy_tree(content, staging)
        write_v2(staging, missions, groups, properties, grants, comlinks, routes)
        regenerate_manifest(staging)
        verify(staging, (1, missions, groups, properties, grants, comlinks, routes))
        swap(content, staging)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    print(f"PASS: converted {len(missions)} missions to {content} (format 2)")


if __name__ == "__main__":
    main()
