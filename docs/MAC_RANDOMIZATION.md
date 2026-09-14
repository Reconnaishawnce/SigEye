# MAC randomization: what it is, where it fails, and what SigEye can measure

Working notes for a paper. Everything here is either (a) specification, (b) something the
app already measures, or (c) a lead to verify. Section (c) is marked, and nothing in it
should reach a paper without checking the source.

---

## 1. What the specification actually says

**The four BLE address types**, encoded in the top two bits of the most significant octet
of a random address:

| Top two bits | Type | Behaviour |
|---|---|---|
| `11` | Random static | Fixed until the device reboots. For something never turned off, fixed forever. |
| `01` | Resolvable private (RPA) | Rotates on a timer. A device holding the Identity Resolving Key can resolve it. |
| `00` | Non-resolvable private (NRPA) | Rotates, resolvable by nobody. Rare in practice. |
| n/a | Public | IEEE-assigned, permanent, identifies the manufacturer. |

**RPA structure**: 24 bits of `prand` (whose top two bits are the `01` marker) and 24 bits
of `hash = ah(IRK, prand)`. A peer with the IRK recomputes the hash and recognises the
device; anybody else sees 48 random-looking bits.

**The timeout.** `HCI_LE_Set_Resolvable_Private_Address_Timeout`, value range `0x0001` to
`0x0E10` — one second to one hour — default `0x0384`, which is 900 seconds. **Fifteen
minutes is a default that most stacks inherit, not a rule.** Any paper claiming devices
rotate every fifteen minutes is quoting a default and should say so.

**The timer is free-running.** It counts from the moment the current address was generated,
not from any wall clock. This is the single most important consequence in this document and
section 3 is about it.

---

## 2. What survives a rotation

The address changes. These do not, because firmware and the advertising parameters set
them rather than the privacy scheme:

**Link-layer traits** (all available from Android's `ScanResult`, all API 26):
- advertising interval — a firmware constant in 0.625 ms slots, plus 0–10 ms of random delay
- `isLegacy` — whether the device uses Bluetooth 5 extended advertising
- `isConnectable`
- `primaryPhy` / `secondaryPhy`
- `advertisingSid` — the advertising set id
- TX power, when declared

**Payload structure**:
- company identifier in manufacturer-specific data
- the set and order of service UUIDs
- service data keys
- GAP appearance
- the *length* of the manufacturer payload, and often its first two bytes (the message type)
- the advertised name, when present

SigEye combines these in `core/analysis/identity/Fingerprint.kt` as `AdvertShape`, and
scores a link with four independent signals: structure match, interval match, RSSI
continuity across the swap, and the timing of the handover. See `LinkScore`.

**Distinctiveness matters and is measured.** `AdvertShape.distinctiveness` counts how many
identifying fields are present; below two the shape is shared by thousands of devices and
the app refuses to match on it. A paper should report the distribution of distinctiveness
in a real room, because it is the thing that decides whether fingerprinting works there.

---

## 3. The phase argument (checked: not original, and that is better)

**Checked 2026-09-13. This is not an original contribution and should not be presented as
one.** The Bluetooth SIG says it themselves, in the announcement of Randomized RPA Updates
in Core 6.1: *"Attackers may be able to model device behavior by observing RPA update
patterns. Even with the maximum 15-minute update interval, RPA addresses can become
predictable."* Core 6.1 adds a v2 of the HCI command with a randomized window, defaulting
to 480 to 900 seconds.

That is a stronger position than novelty. The standards body has already conceded the
weakness and shipped the fix. The useful work is measuring what it looks like on hardware
that does not have the fix, which is almost all of it.

Because the RPA timer is free-running rather than clock-aligned, **two devices on the same
900-second timeout change at different moments and keep doing so.** The offset within the
cycle — the phase — is a property of the device that every rotation preserves. It is
precisely the continuity that rotation exists to destroy.

What this gives an observer:
- **interval**: is this device on 900 s, or 600, or something unusual?
- **phase**: where in the cycle does it change?

SigEye measures both in `core/analysis/identity/RotationRhythm.kt`. Notes on the
implementation that matter for a paper:

- Phase is computed **on the circle**, not as an arithmetic mean. A device changing at 500 ms
  into the cycle and one at 500 ms before the end of it are one second apart, not fifteen
  minutes. An arithmetic mean puts the phase on the opposite side of the cycle from every
  reading it was built from.
- **Nothing is claimed from two changes.** The second change is what *defines* a phase; the
  third is the first that tests it. `MIN_CHANGES_FOR_PHASE = 3`.
- Phase tolerance is 45 s, because a change is only observed to the nearest advertising
  interval and only after the old address has been silent a while.
- **What it is worth is stated as a slot count**: a 900 s cycle known to 45 s is one slot in
  twenty. Useful alongside other evidence, useless alone. `RotationRhythm.phaseSlots()`.

**What resets the phase**: Bluetooth toggled off and on, airplane mode, a reboot, and
possibly a stack restart. This is testable and worth a controlled experiment.

**Open question worth measuring**: does the phase actually hold over hours on a real iPhone,
or does something re-seed it? I do not know. This is the first thing to check with real
captures.

---

## 4. Where randomization fails in practice

Ranked by how badly, from what the app can already see:

1. **Devices that never rotate.** A public or random-static address is a permanent
   identifier. `Cohorts` groups a room by manufacturer and reports what fraction of each
   maker's fleet rotates at all. Expect beacons, tags, car kits, headsets and medical
   devices to be entirely fixed.
2. **Asynchronous identifier changes.** If the address changes but a payload token does
   not — or they change at different moments — the two can be stitched together across the
   boundary. This is the known attack (see section 6) and it is the strongest one.
3. **Interval fingerprinting.** A firmware constant in 0.625 ms slots is a surprisingly
   narrow identifier, and it survives every rotation.
4. **Phase.** Section 3.
5. **RSSI continuity.** A device does not teleport when it changes address. Useful only at
   the moment of handover, but that is exactly when it is needed.
6. **Co-presence over time and motion.** The `Follow Me` experiment: walk a mile with
   somebody and almost nothing else stays in range. This needs no fingerprinting at all,
   which is what makes it the most alarming demonstration.

---

## 5. What SigEye can already produce for a paper

| Question | Where | Output |
|---|---|---|
| What fraction of a room rotates at all, by maker | Rotation Lab, cohort tab | Counts and a per-vendor rotating/fixed split |
| Measured rotation period, not assumed | Rotation Lab, rhythm tab | Median period, histogram, deviation callouts |
| Phase stability | `RotationRhythm` | Phase, spread, slot count, usable/not |
| Whether two addresses are one device, with evidence | `Fingerprint.score` | Four weighted signals and a confidence |
| Raw packet capture for offline analysis | Settings → Capture and replay | Plain-text CSV, one advertisement per line |
| Co-presence across legs and travel | Follow Me | Survivor list with a denominator |

**Capture format** is `core/ble/AdvertCodec.kt`: relative timestamp, address, RSSI, name,
company id, manufacturer hex, service UUIDs, service data, TX power, appearance, legacy,
connectable, both PHYs, advertising SID. That is everything needed for offline
fingerprinting, and it is a text file anybody can open.

---

## 6. Prior work — checked 2026-09-13, safe to cite

Verified against the publishers. The earlier version of this section was written from
memory and got the Becker mechanism wrong, which is recorded here so the mistake is not
repeated: I had been attributing the rotation-timing argument to them and they do not make
it.

- **Becker, J. K., Li, D., Starobinski, D. "Tracking Anonymized Bluetooth Devices."**
  *PoPETs* 2019(3):50-65. doi:10.2478/popets-2019-0036. The **address-carryover algorithm**,
  which exploits, in their words, "the asynchronous nature of payload and address changes."
  Identifying tokens in the payload change on a different schedule from the address, so an
  observer bridges the boundary. **This is about payload asynchrony and not about timing.**
- **Martin, J. et al. "Handoff All Your Privacy: A Review of Apple's Bluetooth Low Energy
  Continuity Protocol."** *PoPETs* 2019(4). Continuity messages fingerprint device type and
  OS version, and they found **predictable sequence numbers** usable to track across
  randomization. Stronger than what SigEye reads, which is only the set of message types.
- **Celosia, G., Cunche, M. "Discontinued Privacy: Personal Data Leaks in Apple
  Bluetooth-Low-Energy Continuity Protocols."** *PoPETs* 2020(1):26-46.
- **Gagnon, G., Gambs, S., Cunche, M. "RSSI-based attacks for identification of BLE
  devices."** *Computers & Security*, 2024. doi:10.1016/j.cose.2024.104080. Links traces
  across randomization using RSSI. **This is the closest published work to Follow Me and to
  the RSSI-continuity signal, and nothing in this project should imply that idea is new.**
- **Bluetooth SIG, "Enhancing device privacy and energy efficiency with Bluetooth
  Randomized RPA Updates."** The Core 6.1 feature and the concession quoted in section 3.
  v1 RPA timeout: default 0x0384 (900 s), range 0x0001 to 0x0E10 (1 s to 3600 s). v2
  defaults: 0x01E0 to 0x0384 (480 to 900 s).

**Where this project sits relative to those.** Not on mechanism. Every individual signal
SigEye uses is published. What is not in the literature, as far as this check went, is the
composition: these signals running together on a stock unrooted phone, in real time, for
one person walking, with the co-presence elimination in section 4.6 doing the work that
fingerprinting does in the papers. Claim the cheapness, not the cleverness.

---

## 7. The experiment to run: ten iPhones

The plan as discussed. Recording it here so the design survives.

**Setup**: ten iPhones, ideally mixed models and iOS versions, in a room with the phone
capturing. Bluetooth on, screens in a known state (this matters — advertising behaviour
changes when a phone is locked and when it is in use).

**Capture**: SigEye's Settings → Capture and replay, running for at least ninety minutes so
each device has three or more rotations and a phase can be tested.

**Controls worth building in**:
- Note each phone's model and iOS version, by hand, against its starting address.
- Toggle Bluetooth on one phone mid-capture, to test whether the phase re-seeds.
- Lock and unlock one phone, to see whether the advertising interval changes with state.
- Leave one phone in a known state throughout as a reference.

**What to measure from the capture**:
1. Rotation period per device. Is it 900 s? Does it vary by model or iOS version?
2. Phase stability per device over the whole capture.
3. Whether the Continuity payload changes at the same instant as the address, or at a
   different one. **This is the carryover question and it is the most valuable measurement
   in the list.**
4. Advertising interval per device, in slots, and how narrowly it is held.
5. Whether any field at all persists unchanged across a rotation.
6. How distinctive the advertisement shape is: how many of the ten are separable on shape
   alone?

**What would make a finding**: any field that survives a rotation, any timing relationship
that survives, or a phase that holds for hours. Any of those is a demonstrable weakness.

**What would be a negative result worth publishing too**: if Apple rotates every identifier
atomically and re-seeds the phase, that is a well-implemented privacy scheme and saying so
is useful.

---

## 8. Honest limits, which the paper must state

- **A phone cannot see Wi-Fi probe requests.** Those are management frames from client
  devices and capturing them needs monitor mode: a chipset, a driver and root. Anything
  about Wi-Fi MAC randomization needs different hardware.
- **A phone cannot tell which advertising channel a packet arrived on.** Android's
  `ScanResult` has no channel index.
- **Android suppresses unfiltered scan results when the screen is off** (8.1+). SigEye scans
  with a broad filter set for this reason, and that filter set is *not* "everything" — a
  device with no service UUID from an uncommon manufacturer is missed. Any count must state
  what the filter was. See `core/ble/ScanFilters.kt`.
- **RSSI is uncalibrated** and varies by several dB with nothing moving.
- **One observer, one location.** Everything here is single-vantage-point. A real adversary
  with several receivers is strictly more capable.

---

## 9. The argument the paper is for

The point is not that tracking is possible in a laboratory. It is that **a stock phone with
no special hardware, in an afternoon, can follow a person by the device in their pocket** —
and that the privacy mechanism designed to prevent exactly this has gaps that are
measurable with the same phone.

The strongest demonstration is probably the least technical one: the Follow Me experiment,
where co-presence across a mile of travel narrows a street to a single device without any
fingerprinting at all. The fingerprinting results are what show that the countermeasure is
imperfect; the co-presence result is what shows the countermeasure is insufficient even
when it works.
