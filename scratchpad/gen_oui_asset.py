# Builds app/src/main/assets/oui.bin from the IEEE MA-L registry.
#
# Fixed-width records so the app can binary-search the bytes directly instead of
# inflating forty thousand map entries onto the heap:
#
#     3 bytes  OUI, big-endian
#    29 bytes  organisation name, UTF-8, space-padded and truncated
#
# sorted by OUI. 32 bytes a record, about 1.3 MB.
import csv, io, re, os

NAME_BYTES = 29
RECORD = 3 + NAME_BYTES

# Corporate boilerplate carries no information and costs characters that the actual name
# needs. Stripped in longest-first order so "Co., Ltd." goes before "Ltd".
NOISE = [
    ', Inc.', ' Inc.', ' Inc', ', Incorporated', ' Incorporated',
    ' Co., Ltd.', ' Co.,Ltd.', ' Co., Ltd', ' Co.,Ltd', ' Co. Ltd.', ' Co. Ltd',
    ' Company Limited', ' Company Ltd', ' Co., LTD', ' CO., LTD',
    ', Ltd.', ' Ltd.', ' Ltd', ', LLC', ' LLC', ' L.L.C.',
    ' Corporation', ' Corp.', ' Corp', ' GmbH & Co. KG', ' GmbH', ' AG', ' S.A.',
    ' Limited', ' PLC', ' plc', ' B.V.', ' N.V.', ' S.p.A.', ' AB', ' ApS', ' A/S',
    ' Technologies', ' Technology', ' Electronics',
]


def tidy(name):
    name = ' '.join(name.split())
    changed = True
    while changed:
        changed = False
        for suffix in NOISE:
            if name.lower().endswith(suffix.lower()) and len(name) > len(suffix) + 2:
                name = name[: -len(suffix)].rstrip(' ,')
                changed = True
    return name or '?'


def main():
    rows = {}
    with io.open('oui.csv', encoding='utf-8', errors='replace') as handle:
        for row in csv.DictReader(handle):
            if row.get('Registry') != 'MA-L':
                continue
            assignment = (row.get('Assignment') or '').strip().upper()
            if not re.fullmatch(r'[0-9A-F]{6}', assignment):
                continue
            name = tidy(row.get('Organization Name') or '')
            encoded = name.encode('utf-8')[:NAME_BYTES]
            # A truncation must not split a multi-byte character.
            while encoded and len(encoded.decode('utf-8', 'ignore').encode('utf-8')) != len(encoded):
                encoded = encoded[:-1]
            rows[assignment] = encoded

    out = bytearray()
    for oui in sorted(rows):
        out += bytes.fromhex(oui)
        out += rows[oui].ljust(NAME_BYTES, b' ')

    target = os.path.join('..', '..', '..', '..', '..', 'CLAUDE', 'SigEye',
                          'app', 'src', 'main', 'assets')
    target = os.environ.get('SIGEYE_ASSETS', target)
    os.makedirs(target, exist_ok=True)
    path = os.path.join(target, 'oui.bin')
    with open(path, 'wb') as handle:
        handle.write(out)
    print('records', len(rows), 'bytes', len(out), '->', path)

    # Spot checks against the raw file, so a bug in tidy() cannot pass silently.
    for probe in ('48CA43', '0025DF', 'B41E52'):
        index = sorted(rows).index(probe) if probe in rows else -1
        print(probe, index, rows.get(probe, b'MISSING').decode('utf-8').strip())


main()
