# Generates AssignedNumbers.kt from the Bluetooth SIG and Nordic registries.
# Hand-typing these was tried once and five of twenty-three were wrong.
import json, re, io

def parse_appearance(path):
    cats, subs = {}, {}
    cat = None
    for line in io.open(path, encoding='utf-8'):
        if line.lstrip().startswith('#'):
            continue
        m = re.match(r'\s*-\s*category:\s*(0x[0-9A-Fa-f]+)', line)
        if m:
            cat = int(m.group(1), 16); subs.setdefault(cat, {}); continue
        m = re.match(r'\s*-?\s*name:\s*(.+?)\s*$', line)
        if m and cat is not None and cat not in cats:
            cats[cat] = m.group(1).strip().strip('"'); continue
        m = re.match(r'\s*-\s*value:\s*(0x[0-9A-Fa-f]+)', line)
        if m:
            sub_val = int(m.group(1), 16); pending = sub_val; last_sub = sub_val
            subs[cat]['__pending__'] = sub_val; continue
        m = re.match(r'\s*name:\s*(.+?)\s*$', line)
        if m and cat is not None and '__pending__' in subs.get(cat, {}):
            v = subs[cat].pop('__pending__')
            subs[cat][v] = m.group(1).strip().strip('"')
    for c in subs:
        subs[c].pop('__pending__', None)
    return cats, subs

cats, subs = parse_appearance('appearance_values.yaml')

# Member (0xFDxx/0xFExx) service UUIDs: vendor-claimed, so they name a company.
members = {}
cur = None
for line in io.open('member_uuids.yaml', encoding='utf-8'):
    if line.lstrip().startswith('#'):
        continue
    m = re.match(r'\s*-\s*uuid:\s*(0x[0-9A-Fa-f]+)', line)
    if m:
        cur = int(m.group(1), 16); continue
    m = re.match(r'\s*name:\s*(.+?)\s*$', line)
    if m and cur is not None:
        members[cur] = m.group(1).strip().strip('"'); cur = None

sig = {}
for e in json.load(open('service_uuids.json')):
    try:
        u = int(e['uuid'], 16)
    except ValueError:
        continue
    if u <= 0xFFFF:
        sig[u] = e['name']

print('appearance categories', len(cats), 'with subcategory sets', sum(1 for c in subs if subs[c]))
print('member uuids', len(members), 'sig services', len(sig))
for probe in (0x001, 0x003, 0x00F, 0x025, 0x01C):
    print(hex(probe), cats.get(probe), dict(list(subs.get(probe, {}).items())[:4]))
for probe in (0xFEAA, 0xFE2C, 0xFD6F, 0xFE59, 0xFE95):
    print(hex(probe), members.get(probe))
json.dump({'cats': cats, 'subs': subs, 'members': members, 'sig': sig},
          open('tables.json', 'w'))
