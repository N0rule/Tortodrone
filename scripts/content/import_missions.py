#!/usr/bin/env python3
"""Convert a stock mission configuration export into the unified mission YAML tree.

The input is the client's own exported `mission.xml`, `mission_group.xml` and
`mission_property.xml` (the formats produced by the stock XML exporter).  Nothing
is authored here: every identifier, rank, reward reference, restriction and
prestige range is copied from those files.  The converter refuses an unrecognised
mission implementation, requirement, restriction, reward entry or argument shape
instead of dropping it, so an import cannot silently lose catalogue data.
"""
import argparse, pathlib, sys
import xml.etree.ElementTree as ET
import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
TYPES = {
    "com.threerings.projectx.mission.config.MissionConfig$Rank": "rank",
    "com.threerings.projectx.mission.config.MissionConfig$Prestige": "prestige",
    "com.threerings.projectx.mission.config.MissionConfig$Expansion": "expansion",
    "com.threerings.projectx.mission.config.MissionConfig$Hidden": "hidden",
}
REQUIREMENTS = {
    "com.threerings.projectx.mission.config.MissionConfig$Gate": "gate",
    "com.threerings.projectx.mission.config.MissionConfig$GateConversation": "gate_conversation",
    "com.threerings.projectx.mission.config.MissionConfig$Conversation": "conversation",
    "com.threerings.projectx.mission.config.MissionConfig$Exchange": "exchange",
    "com.threerings.projectx.mission.config.MissionConfig$RandomChooseExchange": "choose_exchange",
}
RESTRICTIONS = {
    "com.threerings.projectx.mission.config.MissionConfig$RankRange": "rank_range",
    "com.threerings.projectx.mission.config.MissionConfig$RankRestriction": "rank",
    "com.threerings.projectx.mission.config.MissionConfig$EventRestriction": "event",
    "com.threerings.projectx.mission.config.MissionConfig$MissionRestriction": "mission",
    "com.threerings.projectx.mission.config.MissionConfig$Schedule": "schedule",
}
DEPLOYMENTS = {"PRODUCTION", "PREVIEW", "DEV", "TEST", "NONE"}
PRESTIGE = {"LOW", "AVG", "HIGH", "MAX"}
DIFFICULTIES = {"EASY", "MEDIUM", "HARD"}


def fail(where, message):
    raise SystemExit(f"{where}: {message}")


def text_of(element, where):
    if element is None:
        fail(where, "missing element")
    if list(element):
        fail(where, "unexpected nested element")
    return element.text or ""


def number(element, where):
    value = text_of(element, where)
    try:
        return int(value)
    except ValueError:
        fail(where, f"expected integer, found {value!r}")


def boolean(element, where):
    value = text_of(element, where)
    if value == "true":
        return True
    if value == "false":
        return False
    fail(where, f"expected true or false, found {value!r}")


def name_ref(element, where):
    """A <tag><name>Config/Name</name></tag> reference."""
    if element is None:
        return None
    for child in element:
        if child.tag != "name":
            fail(where, f"unexpected reference field {child.tag!r}")
    name = element.find("name")
    if name is None:
        fail(where, "reference has no name")
    return text_of(name, where + ".name")


def class_of(element, where):
    value = element.get("class")
    if value is None:
        fail(where, "unexpected entry without a class")
    return value


def argument_value(element, where):
    if element.tag == "entry":
        values = {}
        for child in element:
            values[child.tag] = argument_value(child, where + "." + child.tag)
        return values
    if list(element):
        values = {}
        for child in element:
            if child.tag == "entry":
                values.setdefault("entries", []).append(argument_value(child, where + ".entry"))
            else:
                values[child.tag] = argument_value(child, where + "." + child.tag)
        return values
    return element.text or ""


def arguments(element, where):
    """<arguments><key>K</key><value>V</value>...</arguments> -> ordered mapping."""
    result = {}
    pending = None
    for child in element:
        if child.tag == "key":
            pending = text_of(child, where + ".key")
            if pending in result:
                fail(where, f"duplicate argument key {pending!r}")
        elif child.tag == "value":
            if pending is None:
                fail(where, "argument value without a key")
            result[pending] = argument_value(child, where + "." + pending)
            pending = None
        else:
            fail(where, f"unexpected argument field {child.tag!r}")
    if pending is not None:
        fail(where, f"argument key {pending!r} has no value")
    return result


def reference(element, where):
    result = {}
    for child in element:
        if child.tag == "name":
            result["name"] = text_of(child, where + ".name")
        elif child.tag == "arguments":
            result["arguments"] = arguments(child, where + ".arguments")
        else:
            fail(where, f"unexpected reference field {child.tag!r}")
    if "name" not in result:
        fail(where, "reference has no name")
    return result


def rewards(element, where):
    result = []
    for entry in element:
        if entry.tag != "entry":
            fail(where, f"unexpected reward field {entry.tag!r}")
        row = {"difficulty": "EASY"}
        for child in entry:
            if child.tag == "difficulty":
                row["difficulty"] = text_of(child, where + ".difficulty")
            elif child.tag == "rewards":
                row["rewards"] = reference(child, where + ".rewards")
            else:
                fail(where, f"unexpected reward entry field {child.tag!r}")
        if row["difficulty"] not in DIFFICULTIES:
            fail(where, f"unsupported reward difficulty {row['difficulty']!r}")
        if "rewards" not in row:
            fail(where, "reward entry has no rewards reference")
        result.append(row)
    return result


def ingredients(element, where):
    result = []
    for entry in element:
        if entry.tag != "entry":
            fail(where, f"unexpected ingredient field {entry.tag!r}")
        item = entry.find("item")
        if item is None:
            fail(where, "ingredient has no item")
        count = entry.find("count")
        row = {"item": name_ref(item, where + ".item")}
        if count is not None:
            row["count"] = number(count, where + ".count")
        for child in entry:
            if child.tag not in ("item", "count"):
                fail(where, f"unexpected ingredient entry field {child.tag!r}")
        result.append(row)
    return result


def recipe_choices(element, where):
    result = []
    for entry in element:
        if entry.tag != "entry":
            fail(where, f"unexpected recipe field {entry.tag!r}")
        row = {}
        for child in entry:
            if child.tag == "rarity":
                row["rarity"] = number(child, where + ".rarity")
            elif child.tag == "choose":
                row["choose"] = number(child, where + ".choose")
            elif child.tag == "recipes":
                recipes = []
                for recipe in child:
                    if recipe.tag != "entry":
                        fail(where, f"unexpected recipes field {recipe.tag!r}")
                    ref = recipe.find("ref")
                    if ref is None:
                        fail(where, "recipe has no ref")
                    recipes.append(reference(ref, where + ".recipes.ref"))
                row["recipes"] = recipes
            else:
                fail(where, f"unexpected recipe entry field {child.tag!r}")
        result.append(row)
    return result


def requirement(element, where):
    kind = REQUIREMENTS.get(class_of(element, where))
    if kind is None:
        fail(where, f"unrecognised requirement {class_of(element, where)!r}")
    result = {"type": kind}
    if kind in ("gate", "gate_conversation"):
        result["hasObjective"] = True
        result["dissolveToLab"] = False
    elif kind in ("conversation",):
        result["conversation"] = {}
    for child in element:
        if child.tag == "hasObjective":
            result["hasObjective"] = boolean(child, where + ".hasObjective")
        elif child.tag == "dissolveToLab":
            result["dissolveToLab"] = boolean(child, where + ".dissolveToLab")
        elif child.tag == "conversation" and kind in ("conversation", "gate_conversation"):
            conversation = {}
            for field in child:
                if field.tag == "name":
                    conversation["name"] = text_of(field, where + ".conversation.name")
                elif field.tag == "model":
                    conversation["model"] = reference(field, where + ".conversation.model")
                elif field.tag == "music":
                    conversation["music"] = name_ref(field, where + ".conversation.music")
                elif field.tag == "conversation":
                    conversation["conversation"] = name_ref(field, where + ".conversation.conversation")
                else:
                    fail(where, f"unexpected conversation field {field.tag!r}")
            result["conversation"] = conversation
        elif child.tag == "ingredients":
            result["ingredients"] = ingredients(child, where + ".ingredients")
        elif child.tag == "choose":
            result["choose"] = recipe_choices(child, where + ".choose")
        else:
            fail(where, f"unexpected requirement field {child.tag!r}")
    return result


def restrictions(element, where):
    result = []
    for entry in element:
        if entry.tag != "entry":
            fail(where, f"unexpected restriction field {entry.tag!r}")
        kind = RESTRICTIONS.get(class_of(entry, where + ".entry"))
        if kind is None:
            fail(where, f"unrecognised restriction {class_of(entry, where + '.entry')!r}")
        row = {"type": kind}
        for child in entry:
            if child.tag in ("rank", "subrank", "maxRank", "maxSubrank"):
                row[child.tag] = number(child, where + "." + child.tag)
            elif child.tag == "event":
                row["event"] = text_of(child, where + ".event")
            elif child.tag == "missionId":
                row["missionId"] = text_of(child, where + ".missionId")
            elif child.tag == "schedule":
                schedule = {}
                for field in child:
                    if field.tag == "refTime":
                        schedule["refTime"] = number(field, where + ".schedule.refTime")
                    elif field.tag == "interval":
                        schedule["interval"] = text_of(field, where + ".schedule.interval")
                    else:
                        fail(where, f"unexpected schedule field {field.tag!r}")
                row["schedule"] = schedule
            else:
                fail(where, f"unexpected restriction entry field {child.tag!r}")
        result.append(row)
    return result


def mission(entry, where):
    implementation = entry.find("implementation")
    if implementation is None:
        fail(where, "entry has no implementation")
    kind = TYPES.get(class_of(implementation, where))
    if kind is None:
        fail(where, f"unrecognised mission implementation {class_of(implementation, where)!r}")
    mission_id = text_of(implementation.find("id"), where + ".id")
    if not mission_id:
        fail(where, "empty mission id")
    result = {
        "name": text_of(entry.find("name"), where + ".name"),
        "type": kind,
        "deployment": "DEV",
        "support": False,
        "priority": 0,
        "showMissionsOnComplete": True,
        "prestige": "AVG",
        "rewards": [],
        "requirement": {"type": "gate", "hasObjective": True, "dissolveToLab": False},
    }
    if kind == "rank":
        result.update({"repeatable": True, "rescueCamp": False, "retired": False, "test": "", "inTest": True})
    elif kind == "prestige":
        result.update({"daily": True, "onceOnly": False, "restrictions": []})
    elif kind == "hidden":
        result["restrictions"] = []
    elif kind == "expansion":
        result["tier"] = "TIER_1"

    for child in implementation:
        tag = child.tag
        if tag == "id":
            continue
        if tag == "name":
            continue
        if tag == "briefing":
            result["briefing"] = text_of(child, where + ".briefing")
        elif tag == "cardImage":
            result["cardImage"] = text_of(child, where + ".cardImage")
        elif tag == "priority":
            result["priority"] = number(child, where + ".priority")
        elif tag == "showMissionsOnComplete":
            result["showMissionsOnComplete"] = boolean(child, where + ".showMissionsOnComplete")
        elif tag == "prestige":
            value = text_of(child, where + ".prestige")
            if value not in PRESTIGE:
                fail(where, f"unsupported prestige type {value!r}")
            result["prestige"] = value
        elif tag == "missionProps":
            result["missionProps"] = name_ref(child, where + ".missionProps")
        elif tag == "rewards":
            result["rewards"] = rewards(child, where + ".rewards")
        elif tag == "deployment":
            value = text_of(child, where + ".deployment")
            if value not in DEPLOYMENTS:
                fail(where, f"unsupported deployment {value!r}")
            result["deployment"] = value
        elif tag == "support":
            result["support"] = boolean(child, where + ".support")
        elif tag == "requirement":
            result["requirement"] = requirement(child, where + ".requirement")
        elif tag == "rank":
            for field in child:
                if field.tag in ("rank", "subrank"):
                    result[field.tag] = number(field, where + ".rank." + field.tag)
                else:
                    fail(where, f"unexpected rank field {field.tag!r}")
        elif tag == "repeatable" and kind == "rank":
            result["repeatable"] = boolean(child, where + ".repeatable")
        elif tag == "rescueCamp" and kind == "rank":
            result["rescueCamp"] = boolean(child, where + ".rescueCamp")
        elif tag == "retired" and kind == "rank":
            result["retired"] = boolean(child, where + ".retired")
        elif tag == "test" and kind == "rank":
            result["test"] = text_of(child, where + ".test")
        elif tag == "inTest" and kind == "rank":
            result["inTest"] = boolean(child, where + ".inTest")
        elif tag == "needMission" and kind == "rank":
            result["needMission"] = text_of(child.find("missionId"), where + ".needMission")
        elif tag == "daily" and kind == "prestige":
            result["daily"] = boolean(child, where + ".daily")
        elif tag == "onceOnly" and kind == "prestige":
            result["onceOnly"] = boolean(child, where + ".onceOnly")
        elif tag == "restrictions" and kind in ("prestige", "hidden"):
            result["restrictions"] = restrictions(child, where + ".restrictions")
        elif tag == "group" and kind == "expansion":
            result["group"] = name_ref(child, where + ".group")
        elif tag == "tier" and kind == "expansion":
            result["tier"] = text_of(child, where + ".tier")
        else:
            fail(where, f"unexpected {kind} field {tag!r}")

    if kind == "rank" and ("rank" not in result or "subrank" not in result):
        fail(where, "rank mission has no rank pair")
    if kind == "prestige" and "restrictions" not in result:
        fail(where, "prestige mission has no restrictions")
    return mission_id, result


def load(path):
    if not path.is_file():
        raise SystemExit(f"missing mission catalogue: {path}")
    root = ET.parse(path).getroot()
    if root.tag != "java":
        fail(str(path), "not an exported client catalogue")
    obj = root.find("object")
    if obj is None:
        fail(str(path), "catalogue has no object array")
    return list(obj)


def group(entry, where):
    result = {"deployment": "DEV", "purchase": False}
    result["name"] = text_of(entry.find("name"), where + ".name")
    result["id"] = text_of(entry.find("id"), where + ".id")
    for child in entry:
        if child.tag in ("name", "id"):
            continue
        if child.tag == "cardImage":
            result["cardImage"] = text_of(child, where + ".cardImage")
        elif child.tag == "deployment":
            result["deployment"] = text_of(child, where + ".deployment")
        elif child.tag == "purchase":
            result["purchase"] = boolean(child, where + ".purchase")
        else:
            fail(where, f"unexpected mission group field {child.tag!r}")
    return result


def property_row(entry, where):
    result = {"name": text_of(entry.find("name"), where + ".name")}
    for child in entry:
        if child.tag == "name":
            continue
        if child.tag in ("crownCost", "prestigeLow", "prestigeAvg", "prestigeHigh", "prestigeMax"):
            result[child.tag] = number(child, where + "." + child.tag)
        else:
            fail(where, f"unexpected mission property field {child.tag!r}")
    return result


def dump(path, payload, header):
    path.parent.mkdir(parents=True, exist_ok=True)
    body = yaml.safe_dump(payload, sort_keys=False, allow_unicode=False, width=1000, default_flow_style=False)
    text = header.strip() + "\n\n" + body
    return path, text


def build(source, content):
    missions = {}
    for entry in load(source / "mission.xml"):
        where = f"mission.xml:{entry.findtext('name') or '?'}"
        mission_id, row = mission(entry, where)
        if mission_id in missions:
            fail(where, f"duplicate mission id {mission_id!r}")
        missions[mission_id] = row
    groups = {}
    for entry in load(source / "mission_group.xml"):
        row = group(entry, "mission_group.xml:" + (entry.findtext("name") or "?"))
        groups[row["id"]] = row
    groups_by_name = {row["name"]: key for key, row in groups.items()}
    properties = {}
    for entry in load(source / "mission_property.xml"):
        row = property_row(entry, "mission_property.xml:" + (entry.findtext("name") or "?"))
        properties[row["name"]] = row

    # Cross-file integrity is checked here as well as at server startup so a
    # hand-edited catalogue cannot be published with dangling references.
    for mission_id, row in missions.items():
        where = f"mission {mission_id}"
        if row.get("missionProps") is not None and row["missionProps"] not in properties:
            fail(where, f"unknown missionProps {row['missionProps']!r}")
        if row.get("needMission") is not None and row["needMission"] not in missions:
            fail(where, f"unknown needMission {row['needMission']!r}")
        if row.get("group") is not None:
            if row["group"] not in groups_by_name:
                fail(where, f"unknown mission group {row['group']!r}")
            row["group"] = groups_by_name[row["group"]]
        for restriction in row.get("restrictions", []):
            if restriction["type"] == "mission" and restriction["missionId"] not in missions:
                fail(where, f"unknown restricted mission {restriction['missionId']!r}")

    outputs = []
    for kind in ("rank", "prestige", "expansion", "hidden"):
        rows = {key: row for key, row in sorted(missions.items()) if row["type"] == kind}
        outputs.append(dump(content / "missions" / f"{kind}.yml", {"missions": rows},
                            f"# Imported {kind} missions; regenerate with scripts/content/import_missions.py."))
    outputs.append(dump(content / "missions" / "groups.yml", {"groups": dict(sorted(groups.items()))},
                        "# Imported mission groups; regenerate with scripts/content/import_missions.py."))
    outputs.append(dump(content / "missions" / "properties.yml", {"properties": dict(sorted(properties.items()))},
                        "# Imported mission prestige/crown properties; regenerate with scripts/content/import_missions.py."))
    return missions, groups, properties, outputs


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=pathlib.Path, required=True,
                        help="directory holding mission.xml, mission_group.xml and mission_property.xml")
    parser.add_argument("--content", type=pathlib.Path, default=ROOT / "content")
    parser.add_argument("--check", action="store_true", help="compare with the checked-in tree instead of writing")
    args = parser.parse_args()
    missions, groups, properties, outputs = build(args.source, args.content)
    if args.check:
        bad = []
        for path, text in outputs:
            if not path.is_file() or path.read_text() != text:
                bad.append(str(path))
        if bad:
            raise SystemExit("mission catalogue differs: " + ", ".join(bad))
        print(f"PASS: {len(missions)} missions, {len(groups)} groups, {len(properties)} properties match")
        return
    for path, text in outputs:
        path.write_text(text)
    print(f"Imported {len(missions)} missions, {len(groups)} groups, {len(properties)} properties into {args.content / 'missions'}")


if __name__ == "__main__":
    main()
