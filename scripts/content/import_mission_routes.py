#!/usr/bin/env python3
"""Import stock mission gate routes from a live `MissionGateSummary` capture.

`content/missions/rank.yml` lists the catalogue rank missions.  The client draws a
mission's gate card from a server `MissionGateSummary`: the icon, colours, flags and
the ordered wheels that describe the floors the party walks.  That object is not
shipped, so this converter reads a wire capture of the live gate DSet plus the
deployed level table and writes `content/missions/routes.yml`.

A wedge names its level by message key.  The deployed level table maps that key to
the canonical scene id; only the first non-live (`< 900000`) scene per key is used
so a route never depends on a captured 900xxx pack.  An unresolved wedge keeps
scene 0 and the route is reported as unavailable at runtime instead of being
guessed.

The capture is authoritative for values this converter emits.  Nothing is derived
from the level name, icon or ordering.
"""
import argparse
import json
import pathlib

import yaml

import mission_layout

ROOT = pathlib.Path(__file__).resolve().parents[2]
LIVE_SCENE_FLOOR = 900000
LEVEL_TYPES = {"LEVEL", "TERMINAL", "LOBBY", "SUBTOWN", "CORE", "SPECIAL"}


class Refused(Exception):
    pass


def fail(where, message):
    raise SystemExit(f"{where}: {message}")


def level_key(wedge_name, where):
    if not wedge_name or "|" not in wedge_name and not wedge_name.startswith(("m.", "%town:")):
        fail(where, f"unexpected wedge name {wedge_name!r}")
    key = wedge_name.split("|")[-1]
    if key.startswith("%town:"):
        key = key[len("%town:"):]
    elif key.startswith("m."):
        key = key[2:]
    return key


def load_levels(path):
    """internalId and normalized key -> sorted canonical scene candidates."""
    candidates = {}
    for number, line in enumerate(path.read_text().splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.split("\t")
        if parts[0] == "levelId":
            continue
        if len(parts) < 3:
            fail(f"{path}:{number}", "expected levelId, internalId, defaultSceneId")
        internal, scene = parts[1], int(parts[2])
        keys = {internal}
        if internal.startswith("%town:"):
            keys.add(internal[len("%town:"):])
        elif internal.startswith("m."):
            keys.add(internal[2:])
        for key in keys:
            candidates.setdefault(key, set()).add(scene)
    return candidates


def canonical_scene(candidates, key):
    scenes = sorted(scene for scene in candidates.get(key, ()) if scene < LIVE_SCENE_FLOOR)
    return scenes[0] if scenes else 0


def gate_missions(missions):
    return {name: row for name, row in missions.items()
            if row.get("type") == "rank"
            and (row.get("requirement") or {}).get("type") in ("gate", "gate_conversation")}


def load_missions(path):
    document = yaml.safe_load(path.read_text()) or {}
    return gate_missions(document.get("missions") or {})


def colors_of(summary, where):
    result = []
    for entry in summary.get("_colorizations") or ():
        if entry.get("@") != "com.threerings.opengl.renderer.config.ColorizationConfig$Normal":
            fail(where, f"unsupported gate colorization {entry.get('@')!r}")
        result.append(int(entry["colorization"]))
    if not result:
        fail(where, "gate has no colorizations")
    return result


def wheel_of(wheel, where, levels):
    kind = wheel.get("@")
    if kind != "com.threerings.projectx.dungeon.data.GateSummary$Constant":
        raise Refused(f"{where}: unsupported wheel {kind!r}")
    lengths = wheel.get("_lengths")
    wedges = wheel.get("_wedges") or ()
    if not lengths or len(lengths) < 1:
        fail(where, "wheel has no lengths")
    if len(wedges) != 1:
        raise Refused(f"{where}: wheel has {len(wedges)} wedges; only single-floor wheels are verified")
    wedge = wedges[0]
    level_type = (wedge.get("_type") or {}).get("name")
    if level_type not in LEVEL_TYPES:
        fail(where, f"unexpected level type {level_type!r}")
    name = wedge.get("_name")
    if not name:
        fail(where, "wedge has no level name")
    key = level_key(name, where)
    icon = wedge.get("_icon")
    description = wedge.get("_description")
    if not icon or description is None:
        fail(where, "wedge has no icon or description")
    return {
        "skip": int(wheel.get("_skip", 0)),
        "velocity": float(wheel.get("_velocity", 0.0)),
        "start": int(wheel.get("_start", 0)),
        "lengths": [float(value) for value in lengths],
        "wedges": [{
            "scene": canonical_scene(levels, key),
            "level": key,
            "name": name,
            "icon": icon,
            "description": description,
            "type": level_type,
            "restricted": bool(wedge.get("_restricted")),
        }],
    }


def route_of(summary, levels, where):
    if summary.get("_expansionId") is not None or summary.get("_prestigeId") is not None:
        raise Refused(f"{where}: gate has an expansion or prestige requirement")
    wheels = summary.get("_wheels") or ()
    if not wheels:
        fail(where, "gate has no wheels")
    return {
        "gateId": int(summary["_gateId"]),
        "icon": summary["_icon"],
        "colors": colors_of(summary, where),
        "description": summary["_description"],
        "sourceId": int(summary["_sourceId"]),
        "destId": int(summary["_destId"]),
        "priority": int(summary["_priority"]),
        "tokens": int(summary["_tokens"]),
        "flags": int(summary["_flags"]),
        "wheels": [wheel_of(wheel, f"{where} wheel {index}", levels)
                   for index, wheel in enumerate(wheels)],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gates", required=True, type=pathlib.Path,
                        help="live MissionGateSummary JSON array")
    parser.add_argument("--levels", required=True, type=pathlib.Path,
                        help="deployed levels table (levelId, internalId, defaultSceneId)")
    parser.add_argument("--content", type=pathlib.Path, default=ROOT / "content",
                        help="mission catalogue root; layout 2 writes one route.yml per mission")
    parser.add_argument("--missions", type=pathlib.Path,
                        help="flat v1 missions file to read instead of the catalogue root")
    parser.add_argument("--output", type=pathlib.Path,
                        help="flat v1 output file; defaults to the catalogue layout")
    parser.add_argument("--check", action="store_true", help="compare with the checked-in tree instead of writing")
    args = parser.parse_args()

    missions = load_missions(args.missions) if args.missions is not None else gate_missions(mission_layout.mission_rows(args.content))
    levels = load_levels(args.levels)
    summaries = {}
    for summary in json.loads(args.gates.read_text()):
        name = summary.get("_name") or ""
        if not name.startswith("%mission:m."):
            fail(args.gates, f"unexpected gate name {name!r}")
        summaries[name[len("%mission:m."):]] = summary

    routes, skipped, unavailable = {}, [], []
    for mission in sorted(missions):
        summary = summaries.get(mission)
        if summary is None:
            skipped.append(mission)
            continue
        try:
            route = route_of(summary, levels, mission)
        except Refused as refused:
            skipped.append(f"{mission} ({refused})")
            continue
        missing = [wedge["level"] for wheel in route["wheels"] for wedge in wheel["wedges"]
                   if wedge["scene"] == 0]
        if missing:
            unavailable.append(f"{mission}: {', '.join(missing)}")
        routes[mission] = route

    header = "# Imported mission gate route; regenerate with scripts/content/import_mission_routes.py."
    if args.output is not None:
        outputs = [(args.output, mission_layout.yaml_text("routes", routes, header, sort_keys=True, allow_unicode=True))]
    else:
        outputs = [(args.content / "missions" / mission_id / "route.yml",
                    mission_layout.yaml_text("routes", {mission_id: route}, header, sort_keys=True, allow_unicode=True))
                   for mission_id, route in sorted(routes.items())]
        stale = [path for path in (args.content / "missions").glob("*/route.yml")
                 if path.parent.name not in routes]
        if stale and args.check:
            fail("stale route files: " + ", ".join(str(path) for path in stale))
        for path in stale:
            path.unlink()
    if not mission_layout.compare(outputs, args.check):
        mission_layout.write_all(outputs)
    print(f"Wrote {len(routes)} routes, {len(skipped)} without a live gate, "
          f"{len(unavailable)} with unresolved floors")
    for line in skipped:
        print("  no live gate:", line)
    for line in unavailable:
        print("  unresolved:", line)

if __name__ == "__main__":
    main()
