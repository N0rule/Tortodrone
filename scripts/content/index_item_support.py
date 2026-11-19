#!/usr/bin/env python3
"""Index missing/partial item gameplay from the served catalogues and runtime capabilities."""
import csv,json,pathlib,argparse,collections,re
import yaml
p=argparse.ArgumentParser();p.add_argument('--content',type=pathlib.Path,default=pathlib.Path('content'));p.add_argument('--output',type=pathlib.Path,default=pathlib.Path('docs/item-support.csv'));a=p.parse_args()
bindings=json.loads((a.content/'_internal/item-bindings.json').read_text())['items']
items=[]
for file in sorted((a.content/'items').glob('*.yml')):
    for item_id,row in yaml.safe_load(file.read_text())['items'].items():items.append(dict(row,**bindings[item_id]))
town=json.loads((a.content/'_internal/town-definitions.json').read_text())
for file in sorted((a.content/'town').glob('*.yml')):town.update(yaml.safe_load(file.read_text()))
fires=json.loads((a.content/'_internal/fires.json').read_text())['entries']
metadata={x['config']:x for x in town['items']};recipes={x['config'] for x in town['recipes']};accessories={x['config'] for x in town['accessories']};sprites={x['config'] for x in town['sprites']};weapons=collections.defaultdict(set);reconstructions=collections.defaultdict(set)
def unsupported(action,reasons,reconstructed):
    if not action:return
    if action['kind']=='UNSUPPORTED':reasons.add('unimplemented action '+action['unsupportedType'].split('$')[-1])
    if action['kind'] in ['SPAWN_ACTOR','SPAWN_FACING','RANDOM_TRANSLATED']:reasons.add('unimplemented summoned actor')
    if action['kind']=='CONDITIONAL' and 'ConditionConfig$Tagged' in str(action.get('config')):reasons.add('unrestored action condition')
    if action['kind']=='EMIT_PROJECTILE' and action['facingType']:reasons.add('unresolved projectile target')
    for child in action['children']:unsupported(child,reasons,reconstructed)
    if action.get('actor'):
        actor=action['actor'];unsupported(actor.get('detonation'),reasons,reconstructed);unsupported(actor.get('shutdown'),reasons,reconstructed)
        unsupported(actor.get('startup'),reasons,reconstructed);unsupported(actor.get('activation'),reasons,reconstructed)
        if actor.get('attachment'):
            unsupported({'kind':'EMIT_PROJECTILE','actor':actor['attachment'],'children':[],'facingType':''},reasons,reconstructed)
        if actor['nativeType']=='AGENT':reconstructed.add('native startup/orbitals restored; stripped carrier flight/cleanup uses a reconstructed 1700 ms bound; original homing/timing unverified')
        if actor.get('activation') or actor.get('activatesAttachments'):reconstructed.add('tag activation/geometry reconstructed; exact original blast growth/propagation unverified; see content.md')
        if actor['nativeType'] in {'ORBITAL_BULLET','COMPOUND_ORBITAL_BULLET'} and actor.get('shutdown'):reconstructed.add('disk shutdown guards and swept damage region reconstructed; see content.md')
        for timer in actor.get('timers',[]):unsupported(timer['action'],reasons,reconstructed);unsupported(timer.get('endAction'),reasons,reconstructed)
for attack in fires:
    for ref in attack['fireRefs']:unsupported(ref['action'],weapons[attack['weapon']],reconstructions[attack['weapon']])
use_file=a.content/'_internal/item-use.json'
uses=json.loads(use_file.read_text())['items'] if use_file.exists() else {}
use_kinds={name:definition.get('kind','') for name,definition in uses.items()}
prepared={}
action_file=a.content/'_internal/item-actions.json'
capsule_actions=set(json.loads(action_file.read_text())['actions']) if action_file.exists() else set()
for scene in (a.content/'maps').glob('*/native/bindings.json'):
    props=json.loads(scene.read_text())['mechanics']
    for key,name in props.items():
        if re.fullmatch(r'item\.\d+\.config',key):prepared[name]=float(props.get(key[:-6]+'heal','0'))
rows=[];equipment={'Sword','Handgun','SwingingHandgun','Bomb','BlastNetworkBomb','Helm','Armor','Shield','HelmCostume','ArmorCostume','ShieldCostume','Trinket','Dungeon'}
resources={'Craft','Token','Mineral','MetaMineral','Artifact','TokenArtifact','SanctuaryArtifact'}
seeds=pathlib.Path(__file__).resolve().parents[2]/'src/main/resources/seeds'
def mapped(file):return {line.split('\t')[0] for line in (seeds/file).read_text().splitlines() if line and not line.startswith('#')}
animated=mapped('animated-items.tsv');styles=mapped('style-kits.tsv')
boxes={}
for file in sorted((a.content/'loot/boxes').rglob('*.yml')):
    for item_id,box in yaml.safe_load(file.read_text())['boxes'].items():boxes[bindings[item_id]['config']]=box
upgrades={'Upgrade/Weapon Slot','Upgrade/Trinket Slot','Upgrade/Trinket Slot (2 days)','Upgrade/Heat Bonus','Upgrade/Heat Bonus (1 day)','Upgrade/Heat Bonus (7 days)','Upgrade/Heat Bonus (30 days)','Upgrade/Coin Bonus'}
for item in items:
    name=item['config'];kind=item['type'];data=metadata.get(name,{});reasons=[];state='unusable'
    # Abstract authoring bases and scene-only configurations are not missing bag actions.
    if not item.get('nameKey') or not item.get('stockId') or '/Parts/' in name:continue
    if kind in equipment:
        reasons=sorted(weapons.get(name,set()));state='partial'
        if reconstructions.get(name):
            if not reasons:state='reconstructed'
            reasons.extend(sorted(reconstructions[name]))
        if kind in {'Sword','Handgun','SwingingHandgun','Bomb','BlastNetworkBomb'} and name not in weapons:reasons.append('no attack graph')
    elif kind=='Recipe':
        if data.get('recipe') not in recipes:reasons.append('no resolved crafting recipe')
    elif kind=='AccessoryTicket':
        if data.get('accessory') not in accessories:reasons.append('no resolved accessory')
    elif kind=='SpriteEgg':
        if data.get('sprite') not in sprites:reasons.append('no resolved sprite')
        else:state='partial';reasons.append('quill attachments, advanced pet triggers and some perk effects remain incomplete')
    elif kind=='Harness':pass
    elif name in styles:pass
    elif kind=='Upgrade' and name in upgrades:
        if name=='Upgrade/Coin Bonus':state='partial';reasons.append('native timed activation/extension and bonus synchronization; Coliseum rewards unavailable')
    elif kind in {'RenameKnight','RenameGuild'}:pass
    elif kind=='WrappingPaper':
        if not data.get('giftBox'):reasons.append('no resolved gift box')
    elif kind=='GiftBox':pass  # Filled boxes are created by wrapping, then transferred/unwrapped natively.
    elif kind in {'Lockbox','Pack'}:
        state='partial' if boxes.get(name,{}).get('status')=='approximate' else 'disabled'
        reasons.append('native opening with documented candidates and explicit estimated odds; see box-support.csv and content.md' if state=='partial' else 'native method 12 supported; reward pool unavailable or incomplete; see box-support.csv')
    elif kind=='VariantTicket':
        if not data.get('variant'):reasons.append('no resolved variant')
    elif kind=='Ticket':
        if name.startswith('Ticket/Guild/'):reasons.append('requires guild services')
        elif not name.startswith(('Ticket/Keys/','Ticket/Variants/','Ticket/Unbind/','Rarity/')):reasons.append('no supported ticket consumer')
    elif kind in {'Prop','Actor','SpecialProp','ModelProp','KnightStatue','Placeable'}:
        if item['variant']=='FURNITURE_ITEM' and item['slot']=='OTHER':
            state='partial';reasons.append('native inventory/transfer; requires design-room placement/removal, no supported non-guild design scene')
        else:reasons.append('no supported furniture inventory/design-room representation')
    elif kind=='AnimatedAction' and name in animated:pass
    elif kind=='AnimatedAction' and re.fullmatch(r'Usable/Confetti(?:, (?:Bubbles|Dark Harvest|Equinox|Fall Equinox|Flare Popper|Frenzy|Leaf|Rage|Silver|Slime|Storm|Summer|Winter))?',name):pass
    elif kind in resources:pass  # Materials/tokens are consumed by crafting/shops, not used directly.
    elif kind in {'Vial','Capsule'}:
        if name not in prepared:reasons.append('no prepared scene item-use definition')
        elif kind=='Capsule' and prepared[name]<=0 and name not in capsule_actions:state='partial';reasons.append('native action graph is incomplete or unsupported in the supported scenes')
    elif kind in {'SpawnActor','Lift'}:
        expected='lift' if kind=='Lift' else 'spawn-actor'
        if name not in use_kinds:reasons.append('no prepared item-use definition')
        elif use_kinds[name]!=expected:reasons.append('prepared item-use kind mismatch')
    elif kind=='InstantAction':
        prepared_kind=use_kinds.get(name)
        if prepared_kind=='heal':pass
        elif prepared_kind=='lethal':
            state='partial';reasons.append('native self-damage supported; UnlockAchievement CASINO_WHAMMIE is unavailable')
        elif name.startswith('Instant/Crown/'):
            state='disabled';reasons.append('native use action is a no-op FireEffect; crowns are pickup/drop debug items')
        elif name=='Instant/Arena Autotarget Off':
            state='disabled';reasons.append('native ActionConfig.None; no arena autotarget state exists on this server')
        else:reasons.append('no item-use handler for InstantAction')
    elif kind=='LockdownShield':
        state='disabled';reasons.append('requires Lockdown PvP mode and arena classes, unavailable on this server')
    else:reasons.append('no item-use handler for '+kind)
    if reasons:rows.append([name,kind,state,'; '.join(reasons)])
a.output.parent.mkdir(parents=True,exist_ok=True)
with a.output.open('w',newline='') as f:
    w=csv.writer(f,lineterminator='\n');w.writerow(['config','type','support','remaining_behavior']);w.writerows(sorted(rows))
print('Indexed',len(rows),'items with missing or partial gameplay in',a.output)
