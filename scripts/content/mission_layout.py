"""Shared offline helpers for the v1 and v2 mission layouts.

The mission importers write either the flat v1 split files or, when
`content/format.txt` selects layout 2, one folder per mission. This module keeps
layout detection, mission discovery and atomic writes in one place so no importer
can silently produce a mixed tree.
"""
import os
import pathlib
import tempfile

import yaml

FAMILIES = ("rank", "prestige", "expansion", "hidden")
SHARED = {"groups.yml": "groups", "properties.yml": "properties"}


def fail(message):
    raise SystemExit(message)


def format_of(content):
    """Return 1 or 2 using the same strict marker rules as the server reader."""
    marker = content / "format.txt"
    if not marker.exists():
        return 1
    text = marker.read_text(encoding="utf-8")
    if text == "2\n":
        return 2
    if text == "1\n":
        return 1
    fail(f"{marker}: expected exactly one line containing 1 or 2")


def section(path, name):
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
    except yaml.YAMLError as invalid:
        fail(f"{path}: {invalid}")
    if not isinstance(data, dict) or list(data) != [name]:
        fail(f"{path}: expected one {name} section")
    rows = data[name]
    if not isinstance(rows, dict):
        fail(f"{path}: expected a mapping under {name}")
    return rows


def mission_documents(content):
    """One mapping per mission: id -> {section: row} from either layout."""
    missions = {}
    layout = format_of(content)
    directory = content / "missions"
    if layout == 1:
        if not directory.is_dir():
            return missions
        for family in FAMILIES:
            path = directory / f"{family}.yml"
            if not path.is_file():
                continue
            for mission_id, row in section(path, "missions").items():
                if mission_id in missions:
                    fail(f"{path}: duplicate mission id {mission_id}")
                missions[mission_id] = {"missions": row}
        for name, kind in (("grants.yml", "grants"), ("comlinks.yml", "comlinks"), ("routes.yml", "routes")):
            path = directory / name
            if path.is_file():
                for mission_id, row in section(path, kind).items():
                    if mission_id not in missions:
                        fail(f"{path}: {kind} reference missing mission {mission_id}")
                    missions[mission_id][kind] = row
        return missions
    if not directory.is_dir():
        return missions
    for folder in sorted(directory.iterdir()):
        if folder.is_file():
            fail(f"{directory}: mixed v1 flat mission files and v2 mission folders: {folder.name}")
        if not folder.is_dir():
            fail(f"{directory}: invalid mission entry: {folder.name}")
        mission_id = folder.name
        mission_file = folder / "mission.yml"
        if not mission_file.is_file():
            fail(f"{folder}: missing mission.yml")
        row = section(mission_file, "missions")
        if list(row) != [mission_id]:
            fail(f"{mission_file}: mission id does not match folder {mission_id}")
        record = {"missions": row[mission_id]}
        for name, kind in (("grants.yml", "grants"), ("comlink.yml", "comlinks"), ("route.yml", "routes")):
            path = folder / name
            if path.is_file():
                nested = section(path, kind)
                if list(nested) != [mission_id]:
                    fail(f"{path}: expected exactly one {kind} row for {mission_id}")
                record[kind] = nested[mission_id]
        missions[mission_id] = record
    return missions


def mission_rows(content):
    """The merged `missions` section from either layout."""
    return {mission_id: record["missions"]
            for mission_id, record in mission_documents(content).items()}


def shared_rows(content, name):
    path = content / "shared" / "missions" / name
    if not path.is_file():
        return {}
    return section(path, SHARED[name])


def yaml_text(section_name, rows, header, sort_keys, allow_unicode=False):
    body = yaml.safe_dump({section_name: rows}, sort_keys=sort_keys, allow_unicode=allow_unicode,
                          width=1000, default_flow_style=False)
    return header.strip() + "\n\n" + body


def atomic_write(path, text):
    """Replace one file atomically; a partial import never leaves a half-written file."""
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary = tempfile.mkstemp(prefix="." + path.name + ".", dir=path.parent)
    try:
        with os.fdopen(handle, "w", encoding="utf-8", newline="") as output:
            output.write(text)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    except BaseException:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


def compare(outputs, check):
    """Report outputs whose bytes differ; returns True when the tree matches."""
    bad = []
    for path, text in outputs:
        if not path.is_file() or path.read_text(encoding="utf-8") != text:
            bad.append(str(path))
    if bad:
        if check:
            fail("content differs: " + ", ".join(bad))
        return False
    return True


def write_all(outputs):
    for path, text in outputs:
        atomic_write(path, text)
