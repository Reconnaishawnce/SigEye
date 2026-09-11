import json, io

t = json.load(open('tables.json'))

BS = chr(92)      # backslash, kept out of literals so shell heredocs cannot mangle it
DQ = chr(34)


def q(s):
    s = s.replace(BS, BS + BS).replace(DQ, BS + DQ).replace('$', BS + '$')
    return DQ + s + DQ


out = io.StringIO()
out.write('''package com.sigeye.core.ble

// GENERATED FILE - do not edit by hand.
// Source: Bluetooth SIG assigned numbers (appearance_values.yaml, member_uuids.yaml) and
// the Nordic bluetooth-numbers-database (service_uuids.json).
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
out.write('    /** GAP Appearance categories - the top ten bits of the appearance value. */\n')
out.write('    val APPEARANCE_CATEGORIES: Map<Int, String> = mapOf(\n')
for k in sorted(cats):
    out.write('        0x%03X to %s,\n' % (k, q(cats[k])))
out.write('    )\n\n')

subs = {int(k): {int(a): b for a, b in v.items()} for k, v in t['subs'].items()}
subs = {k: v for k, v in subs.items() if v}
out.write('    /** Subcategories, keyed by category then by the low six bits. */\n')
out.write('    val APPEARANCE_SUBCATEGORIES: Map<Int, Map<Int, String>> = mapOf(\n')
for k in sorted(subs):
    inner = ', '.join('%d to %s' % (a, q(subs[k][a])) for a in sorted(subs[k]))
    out.write('        0x%03X to mapOf(%s),\n' % (k, inner))
out.write('    )\n\n')

sig = {int(k): v for k, v in t['sig'].items()}
out.write('    /** 16-bit services defined by the SIG: what the device can do. */\n')
out.write('    val SIG_SERVICES: Map<Int, String> = mapOf(\n')
for k in sorted(sig):
    out.write('        0x%04X to %s,\n' % (k, q(sig[k])))
out.write('    )\n\n')

mem = {int(k): v for k, v in t['members'].items()}
out.write('''    /**
     * Member service UUIDs. These are allocated to one company, so seeing one names the
     * maker even when the device advertises no manufacturer data at all.
     */
''')
out.write('    val MEMBER_SERVICES: Map<Int, String> = mapOf(\n')
for k in sorted(mem):
    out.write('        0x%04X to %s,\n' % (k, q(mem[k])))
out.write('    )\n}\n')

io.open('AssignedNumbers.kt', 'w', encoding='utf-8').write(out.getvalue())
print('lines', out.getvalue().count('\n'))
