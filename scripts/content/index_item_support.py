#!/usr/bin/env python3
"""Index missing/partial item gameplay from the served catalogues and runtime capabilities."""
import csv,json,pathlib,argparse,collections,re
p=argparse.ArgumentParser();p.add_argument('--pack',type=pathlib.Path,default=pathlib.Path('content/packs/vanilla'));p.add_argument('--output',type=pathlib.Path,default=pathlib.Path('docs/item-support.csv'));a=p.parse_args()
items=json.loads((a.pack/'items/catalog.json').read_text())['items'];town=json.loads((a.pack/'town/catalog.json').read_text());fires=json.loads((a.pack/'combat/fires.json').read_text())['entries']
metadata={x['config']:x for x in town['items']};recipes={x['config'] for x in town['recipes']};accessories={x['config'] for x in town['accessories']};sprites={x['config'] for x in town['sprites']};weapons=collections.defaultdict(set)
def unsupported(action,reasons):
    if not action:return
    if action['kind']=='UNSUPPORTED':reasons.add('unimplemented action '+action['unsupportedType'].split('$')[-1])
    if action['kind'] in ['SPAWN_ACTOR','SPAWN_FACING','RANDOM_TRANSLATED']:reasons.add('unimplemented summoned actor')
    if action['kind']=='CONDITIONAL' and 'ConditionConfig$Tagged' in str(action.get('config')):reasons.add('unrestored action condition')
    if action['kind']=='EMIT_PROJECTILE' and action['facingType']:reasons.add('unresolved projectile target')
    for child in action['children']:unsupported(child,reasons)
    if action.get('actor'):
        actor=action['actor'];unsupported(actor.get('detonation'),reasons);unsupported(actor.get('shutdown'),reasons)
        for timer in actor.get('timers',[]):unsupported(timer['action'],reasons);unsupported(timer.get('endAction'),reasons)
for attack in fires:
    for ref in attack['fireRefs']:unsupported(ref['action'],weapons[attack['weapon']])
prepared={}
for scene in (a.pack/'maps').glob('*/content.properties'):
    props=dict(line.split('=',1) for line in scene.read_text().splitlines() if '=' in line and not line.startswith('#'))
    for key,name in props.items():
        if re.fullmatch(r'item\.\d+\.config',key):prepared[name]=float(props.get(key[:-6]+'heal','0'))
rows=[];equipment={'Sword','Handgun','SwingingHandgun','Bomb','BlastNetworkBomb','Helm','Armor','Shield','HelmCostume','ArmorCostume','ShieldCostume','Trinket'}
resources={'Craft','Token','Mineral','MetaMineral','Artifact','TokenArtifact','SanctuaryArtifact'}
for item in items:
    name=item['config'];kind=item['type'];data=metadata.get(name,{});reasons=[];state='unusable'
    if kind in equipment:
        reasons=sorted(weapons.get(name,set()));state='partial'
        if kind in {'Sword','Handgun','SwingingHandgun','Bomb','BlastNetworkBomb'} and name not in weapons:reasons.append('no attack graph')
    elif kind=='Recipe':
        if data.get('recipe') not in recipes:reasons.append('no resolved crafting recipe')
    elif kind=='AccessoryTicket':
        if data.get('accessory') not in accessories:reasons.append('no resolved accessory')
    elif kind=='SpriteEgg':
        if data.get('sprite') not in sprites:reasons.append('no resolved sprite')
        else:state='partial';reasons.append('quill attachments, advanced pet triggers and some perk effects remain incomplete')
    elif kind=='Harness':pass
    elif kind in resources:pass  # Materials/tokens are consumed by crafting/shops, not used directly.
    elif kind in {'Vial','Capsule'}:
        if name not in prepared:reasons.append('no prepared scene item-use definition')
        elif kind=='Capsule' and prepared[name]<=0:state='partial';reasons.append('only healing capsules have an item-use handler')
    else:reasons.append('no item-use handler for '+kind)
    if reasons:rows.append([name,kind,state,'; '.join(reasons)])
a.output.parent.mkdir(parents=True,exist_ok=True)
with a.output.open('w',newline='') as f:
    w=csv.writer(f,lineterminator='\n');w.writerow(['config','type','support','remaining_behavior']);w.writerows(sorted(rows))
print('Indexed',len(rows),'items with missing or partial gameplay in',a.output)
