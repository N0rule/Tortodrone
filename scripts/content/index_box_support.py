#!/usr/bin/env python3
"""Audit indexed boxes and refresh only Lockbox rows in the shared item support index."""
import argparse
import csv
import json
from pathlib import Path
import yaml

parser = argparse.ArgumentParser()
parser.add_argument('--content', type=Path, default=Path('content'))
parser.add_argument('--docs', type=Path, default=Path('docs'))
args = parser.parse_args()
bindings = json.loads((args.content / '_internal/item-bindings.json').read_text())['items']
by_config = {v['config']: v for v in bindings.values()}
native = json.loads((args.content / '_internal/box-bindings.json').read_text())['boxes']
names = {}
for file in (args.content / 'items').glob('*.yml'):
    names.update(yaml.safe_load(file.read_text())['items'])
boxes = {}
for file in sorted((args.content / 'loot/boxes').rglob('*.yml')):
    for key, row in yaml.safe_load(file.read_text())['boxes'].items():
        if key in boxes:
            raise ValueError('Duplicate box definition: ' + key)
        boxes[key] = row
rows = []
for key, binding in sorted(bindings.items()):
    if binding['type'] != 'Lockbox':
        continue
    row = boxes[key]
    keys = native[key]['keyOptions']
    if row['acceptedKeys'] != keys or row['kind'] != ('keyed' if keys else 'keyless'):
        raise ValueError('Stock key mismatch: ' + key)
    status = {'incomplete': 'Protocol supported but pool incomplete',
              'unsupported': 'Unsupported reward representation',
              'unresearched': 'Not yet researched',
              'approximate': 'Supported with estimated distribution'}.get(row['status'])
    if status is None:
        raise ValueError('Review enabled definitions and client/pool evidence before updating coverage: ' + key)
    verified = ('Shipped pool; isolated native Arsenal scenario' if key in {'lockbox', 'item_000', 'surge_prize_box', 'almire_furniture_box', 'disguise_prize_box'} else 'Native key config only')
    if binding['stockId'] == 0:
        status = 'Disabled internal stock template'
    elif key == 'mystery':
        status = 'Disabled empty reward placeholder'
    rows.append([key, names[key]['name'], row['kind'], '; '.join(f'{n}: {value}' for n, value in enumerate(keys)) or 'None (-1)',
                 'No player pool (internal template)' if binding['stockId'] == 0 else 'Empty native reward graph' if key == 'mystery' else ('Archived graph; historical current-server parity unverified' if row['evidence'].startswith('Archived stock') else 'Documented candidates; estimated distribution') if row['status'] == 'approximate' else 'Unknown' if row['status'] == 'unresearched' else 'Incomplete', verified, status, row['evidence']])
args.docs.mkdir(parents=True, exist_ok=True)
with (args.docs / 'box-support.csv').open('w', newline='') as output:
    writer = csv.writer(output, lineterminator='\n')
    writer.writerow(['item_id', 'box', 'kind', 'key_options', 'pool_completeness', 'client_verified', 'status', 'evidence'])
    writer.writerows(rows)
index = args.docs / 'item-support.csv'
with index.open(newline='') as source:
    reader = csv.reader(source)
    header = next(reader)
    rows = list(reader)
configs = {bindings[key]['config']: value for key, value in boxes.items()}
for row in rows:
    if row[1] == 'Lockbox':
        if row[0] not in configs:
            raise ValueError('Unclassified indexed box: ' + row[0])
        row[2:] = (['partial', 'native opening with documented candidates and explicit estimated odds; see box-support.csv and content.md']
                   if configs[row[0]]['status'] == 'approximate' else
                   ['disabled', 'native method 12 supported; reward pool unavailable or incomplete; see box-support.csv'])
    elif by_config[row[0]]['variant'] == 'FURNITURE_ITEM' and by_config[row[0]]['slot'] == 'OTHER':
        row[2:] = ['partial', 'native undeployed FurniItem inventory/grant/persistence; furniture placement unavailable']
    elif by_config[row[0]].get('expireDays', 0) > 0 and by_config[row[0]]['slot'] is not None:
        row[2:] = ['partial', 'native timed costume with persistent absolute expiry, inventory/equip/trade; see content.md']
    elif row[1] == 'Sword' and row[0] == bindings['untimely_scythe']['config']:
        row[2:] = ['partial', 'native inventory/equip/custom color model and existing sword combat; see content.md']
    elif row[1] == 'Upgrade' and by_config[row[0]]['slot'] == 'OTHER' and by_config[row[0]]['variant'] == 'UPGRADE_ITEM':
        row[2:] = ['partial', 'native inactive UpgradingItem inventory/grant/persistence; timed activation unavailable']
with index.open('w', newline='') as output:
    writer = csv.writer(output, lineterminator='\n')
    writer.writerow(header)
    writer.writerows(rows)
print(f'Audited {len(boxes)} boxes; {sum(b["status"] == "approximate" for b in boxes.values())} explicit approximation pools enabled; incomplete pools remain disabled')
