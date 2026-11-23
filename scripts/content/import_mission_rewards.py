#!/usr/bin/env python3
"""Resolve imported mission reward references into concrete granted items.

`content/missions/rank.yml` keeps the stock client's own reward-table references.
Those references name entries in `spawn_table.xml`, which is not shipped with the
server.  This converter reads an exported `spawn_table.xml` and writes
`content/missions/grants.yml` with the item config, count, binding flag and any
explicit variant names each mission pays.

Only the deterministic item entry shapes used by mission reward tables are
accepted (`CompoundEntry`, `LockedItemEntry`, `VariantItemEntry`, `ItemEntry`,
`MultiItemEntry`).  Random tables, derived configs, optional entries and nested
spawn-table references are refused instead of being flattened into invented
rewards.
"""
import argparse
import pathlib
import sys
import xml.etree.ElementTree as ET

import yaml

import mission_layout

ROOT = pathlib.Path(__file__).resolve().parents[2]
PREFIX = "com.threerings.projectx.dungeon.config.SpawnTableConfig$"
ITEM_ENTRIES = {
    PREFIX + "LockedItemEntry",
    PREFIX + "VariantItemEntry",
    PREFIX + "ItemEntry",
    PREFIX + "MultiItemEntry",
}


def fail(where, message):
    raise SystemExit(f"{where}: {message}")


def text_of(element, where):
    if element is None:
        fail(where, "missing element")
    if list(element):
        fail(where, "unexpected nested element")
    return element.text or ""


def name_ref(element, where):
    if element is None:
        return None
    for child in element:
        if child.tag != "name":
            fail(where, f"unexpected reference field {child.tag!r}")
    name = element.find("name")
    if name is None:
        fail(where, "reference has no name")
    return text_of(name, where + ".name")


def boolean(element, where):
    value = text_of(element, where)
    if value == "true":
        return True
    if value == "false":
        return False
    fail(where, f"expected true or false, found {value!r}")


def integer(element, where):
    value = text_of(element, where)
    try:
        return int(value)
    except ValueError:
        fail(where, f"expected integer, found {value!r}")


def variant_table(element, where):
    """Return explicit variant config names from a VariantItemEntry table."""
    variants = []
    name = element.find("name")
    if name is not None:
        text_of(name, where + ".name")  # table name itself is not a granted value
    arguments = element.find("arguments")
    if arguments is not None:
        pending = None
        for child in arguments:
            if child.tag == "key":
                pending = text_of(child, where + ".key")
            elif child.tag == "value":
                if pending is None:
                    fail(where, "argument value without a key")
                if pending not in ("Variant", "variants"):
                    fail(where, f"unsupported variant table argument {pending!r}")
                value = child.find("name")
                if value is None:
                    fail(where, f"variant argument {pending!r} has no config reference")
                variants.append(text_of(value, where + ".value.name"))
                pending = None
            else:
                fail(where, f"unexpected argument field {child.tag!r}")
        if pending is not None:
            fail(where, f"argument key {pending!r} has no value")
    return variants


def grants(element, where):
    """Flatten one deterministic reward table entry into concrete grants."""
    cls = element.get("class")
    if cls is None:
        fail(where, "reward entry has no class")
    if cls == PREFIX + "CompoundEntry":
        result = []
        entries = element.find("entries")
        if entries is None:
            fail(where, "compound entry has no entries")
        for index, child in enumerate(entries):
            if child.tag != "entry":
                fail(where, f"unexpected compound field {child.tag!r}")
            result.extend(grants(child, f"{where}.entries[{index}]"))
        return result
    if cls not in ITEM_ENTRIES:
        fail(where, f"unsupported reward entry {cls!r}")
    item = element.find("item")
    item_name = name_ref(item, where + ".item")
    if not item_name:
        fail(where, "reward entry has no item")
    count = 1
    locked = cls in (PREFIX + "LockedItemEntry", PREFIX + "VariantItemEntry")
    variants = []
    for child in element:
        if child.tag in ("item", "variantTable"):
            continue
        if child.tag == "count":
            count = integer(child, where + ".count")
        elif child.tag == "locked":
            locked = boolean(child, where + ".locked")
        else:
            fail(where, f"unexpected item entry field {child.tag!r}")
    if count < 1 or count > 9999:
        fail(where, f"reward count out of range: {count}")
    table = element.find("variantTable")
    if table is not None:
        if cls != PREFIX + "VariantItemEntry":
            fail(where, "variant table on a non-variant entry")
        variants = variant_table(table, where + ".variantTable")
    row = {"config": item_name, "count": count, "locked": locked}
    if variants:
        row["variants"] = variants
    return [row]


def load_spawn_tables(path):
    root = ET.parse(path).getroot()
    if root.tag != "java":
        fail(str(path), "not an exported client catalogue")
    obj = root.find("object")
    if obj is None:
        fail(str(path), "catalogue has no object array")
    entries = {}
    for entry in obj.findall("entry"):
        name = text_of(entry.find("name"), str(path) + " entry name")
        if name in entries:
            fail(str(path), f"duplicate spawn table {name!r}")
        entries[name] = entry
    return entries


def load_missions(path):
    root = yaml.safe_load(path.read_text())
    if not isinstance(root, dict) or set(root) != {"missions"}:
        fail(str(path), "expected a single missions section")
    return root["missions"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spawn-table", required=True, type=pathlib.Path)
    parser.add_argument("--content", type=pathlib.Path, default=ROOT / "content",
                        help="mission catalogue root; layout 2 writes one grants.yml per mission")
    parser.add_argument("--missions", type=pathlib.Path,
                        help="flat v1 missions file to read instead of the catalogue root")
    parser.add_argument("--output", type=pathlib.Path,
                        help="flat v1 output file; defaults to the catalogue layout")
    parser.add_argument("--check", action="store_true", help="compare with the checked-in tree instead of writing")
    args = parser.parse_args()

    tables = load_spawn_tables(args.spawn_table)
    missions = load_missions(args.missions) if args.missions is not None else mission_layout.mission_rows(args.content)
    output = {}
    for mission_id, mission in sorted(missions.items()):
        rewards = mission.get("rewards") or []
        if not rewards:
            continue
        granted = []
        for row in rewards:
            reference = row.get("rewards") or {}
            name = reference.get("name")
            if not name:
                fail(mission_id, "reward entry has no table name")
            entry = tables.get(name)
            if entry is None:
                fail(mission_id, f"missing spawn table {name!r}")
            implementation = entry.find("implementation")
            if implementation is None:
                fail(name, "spawn table has no implementation")
            if implementation.get("class") != PREFIX + "Original":
                fail(name, f"unsupported spawn table implementation {implementation.get('class')!r}")
            root = implementation.find("root")
            if root is None:
                fail(name, "spawn table has no root")
            granted.extend(grants(root, f"{name}.root"))
        if granted:
            output[mission_id] = [
                {key: value for key, value in row.items() if key != "variants" or value}
                for row in granted
            ]

    for mission_id in output:
        if mission_id not in missions:
            fail(mission_id, "grant has no mission")

    header = "# Imported concrete mission rewards; regenerate with scripts/content/import_mission_rewards.py."
    if args.output is not None:
        outputs = [(args.output, mission_layout.yaml_text("grants", output, header, sort_keys=True, allow_unicode=True))]
    else:
        outputs = [(args.content / "missions" / mission_id / "grants.yml",
                    mission_layout.yaml_text("grants", {mission_id: rows}, header, sort_keys=True, allow_unicode=True))
                   for mission_id, rows in sorted(output.items())]
        stale = [path for path in (args.content / "missions").glob("*/grants.yml")
                 if path.parent.name not in output]
        if stale and args.check:
            fail("stale grant files: " + ", ".join(str(path) for path in stale))
        for path in stale:
            path.unlink()
    if not mission_layout.compare(outputs, args.check):
        mission_layout.write_all(outputs)
    print(f"Wrote {len(output)} missions, {sum(len(rows) for rows in output.values())} grants")

if __name__ == "__main__":
    main()
