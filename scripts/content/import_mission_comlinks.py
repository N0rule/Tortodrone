#!/usr/bin/env python3
"""Resolve imported rank conversation missions into comlink dialogue graphs.

`content/missions/rank.yml` records the stock client's own conversation config name
for each conversation mission.  That config is not shipped with the server, so this
converter reads an exported `conversation.xml` and writes
`content/missions/comlinks.yml` with the message keys, response keys, jumps and
per-node comlink interface each mission's dialogue uses.

Only deterministic dialogue shapes are accepted: `Derived` conversations built from
`Mission/Base/Simple Images`, and `Original` conversations made of `SimpleNode`
entries whose responses either jump to another node or run a plain `Compound`
close.  FireAction launches, conditional starts, station commands and every other
action shape are refused instead of being flattened into invented dialogue.
"""
import argparse
import pathlib
import sys
import xml.etree.ElementTree as ET

import yaml

import mission_layout

ROOT = pathlib.Path(__file__).resolve().parents[2]
CONVERSATION = "com.threerings.projectx.config.ConversationConfig$"
ACTION = "com.threerings.projectx.config.ProjectXActionConfig$"
PLAIN_ACTION = "com.threerings.tudey.config.ActionConfig$"
BASE = "Mission/Base/Simple Images"
SKIP = {"securing_camp"}  # scene-bound Rescue Camp briefing, not a comlink


class Skip(Exception):
    pass


def fail(where, message):
    raise SystemExit(f"{where}: {message}")


def text_of(element, where):
    if element is None:
        fail(where, "missing element")
    if list(element):
        fail(where, "unexpected nested element")
    return element.text or ""


def model(element, where):
    if element is None:
        return None
    if not list(element):
        return None
    name = text_of(element.find("name"), where + ".name")
    arguments = {}
    pending = None
    raw_arguments = element.find("arguments")
    for child in (() if raw_arguments is None else raw_arguments):
        if child.tag == "key":
            pending = text_of(child, where + ".key")
        elif child.tag == "value":
            if pending is None:
                fail(where, "argument value without a key")
            value = (child.text or "").strip()
            if value:
                arguments[pending] = value
            pending = None
        else:
            fail(where, f"unexpected model field {child.tag!r}")
    if pending is not None:
        fail(where, f"argument key {pending!r} has no value")
    result = {"name": name}
    if arguments:
        result["arguments"] = arguments
    return result


def action(element, where):
    """Return (destination, terminal) for a conversation response action."""
    if element is None:
        return "", False
    cls = element.get("class")
    if cls == ACTION + "ConversationJump":
        return text_of(element.find("node"), where + ".node"), False
    if cls == PLAIN_ACTION + "Compound":
        if len(element) == 0:
            return "", True
        child = element.find("actions/entry")
        if child is not None and child.get("class", "").endswith("$FireAction"):
            raise Skip(f"{where}: response fires a town action")
    fail(where, f"unsupported response action {cls!r}")


def derived(implementation, where):
    # `Mission/Base/Simple Images` starts at node_1 for multi-screen conversations and
    # jumps straight to node_end when the Messages option is 1.
    # Values are resolved by the stock parameter substitution in the referenced config;
    # this export carries the concrete option in the using entry's arguments.
    arguments = {}
    pending = None
    for child in implementation.find("conversation/arguments"):
        if child.tag == "key":
            pending = text_of(child, where + ".key")
        elif child.tag == "value":
            if pending is None:
                fail(where, "argument value without a key")
            arguments[pending] = child
            pending = None
        else:
            fail(where, f"unexpected argument field {child.tag!r}")
    if pending is not None:
        fail(where, f"argument key {pending!r} has no value")

    def keyed(kind):
        result = {}
        for key, element in arguments.items():
            parts = key.split(" ", 1)
            if len(parts) != 2:
                continue
            # The stock base config misspells the ninth response option as "Reponse".
            actual = "Response" if parts[1] == "Reponse" else parts[1]
            if actual != kind:
                continue
            index, value = parts[0], element
            if index == "End":
                result["End"] = value
            elif index.isdigit():
                result[int(index)] = value
        return result

    messages = keyed("Message")
    responses = keyed("Response")
    images = keyed("Image")
    count_text = arguments.get("Messages")
    if count_text is None:
        fail(where, "derived conversation has no Messages option")
    count = int(text_of(count_text, where + ".Messages"))
    if count < 1 or count > 64:
        fail(where, f"derived Messages out of range: {count}")
    initial = "node_end" if count == 1 else "node_1"

    nodes = {}
    for index in range(1, count):
        message = text_of(messages.get(index), f"{where}.Message {index}").strip()
        response = text_of(responses.get(index), f"{where}.Response {index}").strip()
        if not message or not response:
            fail(where, f"derived message {index} is incomplete")
        destination = "node_%d" % (index + 1) if index + 1 < count else "node_end"
        nodes["node_%d" % index] = {
            "message": message,
            "responses": [{"text": response, "destination": destination}],
        }
        interface = image_model(images.get(index), f"{where}.Image {index}")
        if interface is not None:
            nodes["node_%d" % index]["interface"] = interface
    end_message = text_of(arguments.get("End Message"), where + ".End Message").strip()
    end_response = text_of(arguments.get("End Response"), where + ".End Response").strip()
    if not end_message or not end_response:
        fail(where, "derived closing message is incomplete")
    nodes["node_end"] = {"message": end_message, "responses": [{"text": end_response, "terminal": True}]}
    interface = image_model(arguments.get("End Image"), where + ".End Image")
    if interface is not None:
        nodes["node_end"]["interface"] = interface
    return initial, nodes


def image_model(element, where):
    return model(element, where)


def original(implementation, where):
    initial_action = implementation.find("initialAction")
    if initial_action is None or initial_action.get("class") != ACTION + "ConversationJump":
        fail(where, "original conversation has no plain initial jump")
    initial = text_of(initial_action.find("node"), where + ".initialAction.node")
    nodes = {}
    for node in implementation.findall("nodes/entry"):
        if node.get("class") != CONVERSATION + "SimpleNode":
            fail(where, f"unsupported node {node.get('class')!r}")
        for field in node:
            if field.tag not in ("name", "message", "userInterface", "responses"):
                fail(where, f"unsupported node field {field.tag!r}")
        name = text_of(node.find("name"), where + ".node")
        message = text_of(node.find("message"), where + f".{name}.message").strip()
        if not message:
            fail(where, f"node {name!r} has no message key")
        responses = []
        for index, response in enumerate(node.findall("responses/entry")):
            if response.get("class") != CONVERSATION + "SimpleResponse":
                fail(where, f"unsupported response {response.get('class')!r}")
            for field in response:
                if field.tag not in ("text", "action"):
                    fail(where, f"unsupported response field {field.tag!r}")
            response_text = text_of(response.find("text"), where + f".{name}.response[{index}]").strip()
            if not response_text:
                fail(where, f"node {name!r} response has no text key")
            destination, terminal = action(response.find("action"), where + f".{name}.response[{index}].action")
            row = {"text": response_text}
            if destination:
                row["destination"] = destination
            elif terminal:
                row["terminal"] = True
            responses.append(row)
        if not responses:
            fail(where, f"node {name!r} has no responses")
        row = {"message": message, "responses": responses}
        interface = model(node.find("userInterface"), where + f".{name}.userInterface")
        if interface is not None:
            row["interface"] = interface
        nodes[name] = row
    if initial not in nodes:
        fail(where, "initial jump is absent")
    for name, row in nodes.items():
        for response in row["responses"]:
            destination = response.get("destination")
            if destination and destination not in nodes:
                fail(where, f"node {name!r} jumps to missing node {destination!r}")
    return initial, nodes


def load_conversations(path):
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
            fail(str(path), f"duplicate conversation {name!r}")
        entries[name] = entry
    return entries


def load_missions(path):
    root = yaml.safe_load(path.read_text())
    if not isinstance(root, dict) or set(root) != {"missions"}:
        fail(str(path), "expected a single missions section")
    return root["missions"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--conversations", required=True, type=pathlib.Path)
    parser.add_argument("--content", type=pathlib.Path, default=ROOT / "content",
                        help="mission catalogue root; layout 2 writes one comlink.yml per mission")
    parser.add_argument("--missions", type=pathlib.Path,
                        help="flat v1 missions file to read instead of the catalogue root")
    parser.add_argument("--output", type=pathlib.Path,
                        help="flat v1 output file; defaults to the catalogue layout")
    parser.add_argument("--check", action="store_true", help="compare with the checked-in tree instead of writing")
    args = parser.parse_args()

    conversations = load_conversations(args.conversations)
    missions = load_missions(args.missions) if args.missions is not None else mission_layout.mission_rows(args.content)
    output = {}
    skipped = []
    for mission_id, mission in sorted(missions.items()):
        requirement = mission.get("requirement") or {}
        if requirement.get("type") not in ("conversation", "gate_conversation"):
            continue
        if mission_id in SKIP:
            continue
        setting = requirement.get("conversation") or {}
        name = setting.get("conversation") or setting.get("name")
        if not name:
            skipped.append((mission_id, "no conversation reference"))
            continue
        entry = conversations.get(name)
        if entry is None:
            fail(mission_id, f"missing conversation {name!r}")
        implementation = entry.find("implementation")
        if implementation is None:
            fail(name, "conversation has no implementation")
        cls = implementation.get("class")
        try:
            if cls == CONVERSATION + "Derived":
                initial, nodes = derived(implementation, name)
            elif cls == CONVERSATION + "Original":
                if implementation.find("nodes") is None:
                    raise Skip("original conversation has no plain dialogue")
                initial, nodes = original(implementation, name)
            else:
                fail(name, f"unsupported conversation implementation {cls!r}")
        except Skip as reason:
            skipped.append((mission_id, str(reason)))
            continue
        row = {"name": name, "initial": initial, "nodes": nodes}
        if setting.get("model"):
            fixed = setting["model"]
            model_row = {"name": fixed["name"]}
            if fixed.get("arguments"):
                model_row["arguments"] = fixed["arguments"]
            row["model"] = model_row
        music = setting.get("music")
        if isinstance(music, dict):
            music = music.get("name")
        if music:
            row["music"] = music
        output[mission_id] = row

    for mission_id, reason in skipped:
        print(f"skip {mission_id}: {reason}", file=sys.stderr)
    header = "# Imported mission comlink dialogue; regenerate with scripts/content/import_mission_comlinks.py."
    if args.output is not None:
        outputs = [(args.output, mission_layout.yaml_text("comlinks", output, header, sort_keys=True, allow_unicode=True))]
    else:
        outputs = [(args.content / "missions" / mission_id / "comlink.yml",
                    mission_layout.yaml_text("comlinks", {mission_id: row}, header, sort_keys=True, allow_unicode=True))
                   for mission_id, row in sorted(output.items())]
        stale = [path for path in (args.content / "missions").glob("*/comlink.yml")
                 if path.parent.name not in output]
        if stale and args.check:
            fail("stale comlink files: " + ", ".join(str(path) for path in stale))
        for path in stale:
            path.unlink()
    if not mission_layout.compare(outputs, args.check):
        mission_layout.write_all(outputs)
    print(f"Wrote {len(output)} comlinks, {sum(len(row['nodes']) for row in output.values())} nodes")

if __name__ == "__main__":
    main()
