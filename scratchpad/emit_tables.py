import json, io

t = json.load(open('tables.json'))

BS = chr(92)      # backslash, kept out of literals so shell heredocs cannot mangle it
DQ = chr(34)
NL = chr(10)


def q(s):
    s = s.replace(BS, BS + BS).replace(DQ, BS + DQ).replace('$', BS + '$')
    return DQ + s + DQ


def table(out, doc, name, entries, width=4):
    for line in doc:
        out.write('    ' + line + NL)
    out.write('    val ' + name + ': Map<Int, String> = mapOf(' + NL)
    fmt = '        0x%0' + str(width) + 'X to %s,' + NL
    for k in sorted(entries):
        out.write(fmt % (k, q(entries[k])))
    out.write('    )' + NL + NL)


out = io.StringIO()
out.write('''package com.sigeye.core.ble

// GENERATED FILE - do not edit by hand.
// Source: Bluetooth SIG assigned numbers (appearance_values.yaml, member_uuids.yaml) and
// the Nordic bluetooth-numbers-database (service_uuids.json, characteristic_uuids.json).
// Regenerate with scratchpad/gen_ble_tables.py then scratchpad/emit_tables.py.

/**
 * The Bluetooth SIG's registries, as lookup tables.
 *
 * Transcribed by a script rather than by hand: an earlier hand-typed vendor table had five
 * wrong entries out of twenty-three, and a confidently wrong label is worse than none.
 */
object AssignedNumbers {

''')

cats = {int(k): v for k, v in t['cats'].items()}
table(
    out,
    ['/** GAP Appearance categories - the top ten bits of the appearance value. */'],
    'APPEARANCE_CATEGORIES',
    cats,
    width=3,
)

subs = {int(k): {int(a): b for a, b in v.items()} for k, v in t['subs'].items()}
subs = {k: v for k, v in subs.items() if v}
out.write('    /** Subcategories, keyed by category then by the low six bits. */' + NL)
out.write('    val APPEARANCE_SUBCATEGORIES: Map<Int, Map<Int, String>> = mapOf(' + NL)
for k in sorted(subs):
    inner = ', '.join('%d to %s' % (a, q(subs[k][a])) for a in sorted(subs[k]))
    out.write('        0x%03X to mapOf(%s),%s' % (k, inner, NL))
out.write('    )' + NL + NL)

sig = {int(k): v for k, v in t['sig'].items()}
table(
    out,
    ['/** 16-bit services defined by the SIG: what the device can do. */'],
    'SIG_SERVICES',
    sig,
)

mem = {int(k): v for k, v in t['members'].items()}
table(
    out,
    [
        '/**',
        ' * Member service UUIDs. These are allocated to one company, so seeing one names',
        ' * the maker even when the device advertises no manufacturer data at all.',
        ' */',
    ],
    'MEMBER_SERVICES',
    mem,
)

chars = {int(k): v for k, v in t['chars'].items()}
table(
    out,
    [
        '/**',
        ' * 16-bit characteristics: the individual readable or writable values inside a',
        ' * service. This is what turns a connection into a sentence - "it has a Battery',
        ' * Service holding a Battery Level" - rather than a list of hex.',
        ' */',
    ],
    'CHARACTERISTICS',
    chars,
)

out.write('}' + NL)

io.open('AssignedNumbers.kt', 'w', encoding='utf-8').write(out.getvalue())
print('lines', out.getvalue().count(NL))
