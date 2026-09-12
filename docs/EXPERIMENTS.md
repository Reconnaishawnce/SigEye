# What shapes the experiments

This was a roadmap for twenty planned experiments. Twenty-eight of them now exist, and the
list of them lives in two places that cannot go stale: `core/Experiment.kt`, which is what
the app reads, and the README, which is a snapshot of it. `docs/ROADMAP.md` has what is
still to come.

What is worth keeping in one place is the set of facts about Android and about radio that
rule things in and out — the constraints that decide what an experiment can honestly claim.

---

## What Android will and will not let you see

**You cannot see other people's Wi-Fi devices.** `WifiManager.getScanResults()` returns
*access points* and nothing else. Sniffing probe requests, which is how you would see
clients, needs monitor mode, which needs root and a chipset that supports it. So Wi-Fi
tells you about infrastructure and BLE tells you about people, and every people-counting
experiment here is BLE for that reason.

That also answers the question about a phone probing for an SSID it joined years ago: it
does, several times a minute, and a stock Android phone cannot hear it. That needs monitor
mode on a USB adapter, or an SDR.

**You cannot see which advertising channel a packet arrived on.** BLE advertises on 37, 38
and 39, and Android's `ScanResult` carries no channel index. Channel Congestion therefore
measures the Wi-Fi occupancy sitting on top of those three frequencies and the reception
ratio per device, and says plainly that it cannot do it per channel.

**You cannot measure airtime.** A phone can tell you who is present and how loud, not how
much of the time the channel is busy. A loud idle access point reads as congestion. Said
on the screen, not in a footnote.

**Wi-Fi scans are throttled to four every two minutes** on Android 9 and up. Developer
options has a toggle that lifts it, and the preflight check on the home screen says when
it is biting. Fine for survey work regardless; useless for anything needing sub-second
resolution.

**`BLUETOOTH_SCAN` is declared without `neverForLocation`.** That flag is an assertion
that the app will not derive location from scan results. Dropping it is what lets one code
path work unchanged on API 26-30, where `ACCESS_FINE_LOCATION` is mandatory for any scan
results at all.

**minSdk is 26.** Anything newer needs a version gate. This is not theoretical: an
unguarded call to `CellIdentity.mccString`, which is API 28, crashed on Android 8 for weeks
because the lint step was `continue-on-error`. It no longer is.

---

## What radio will and will not let you claim

**MAC randomization is the physics of this domain, not a nuisance.** Phones rotate BLE
addresses on a timer, and that both breaks naive tracking and *enables* burst detection — a
passing crowd is a flood of brand-new addresses, which is exactly how Train Spotter works.
Three experiments study the rotation itself.

Two things about that timer are worth stating precisely, because almost every write-up gets
them wrong. The timeout is settable anywhere from one second to an hour, with a default of
nine hundred; fifteen minutes is a convention most stacks inherit, not a rule. And it runs
from the last address change rather than from a clock, so the phase is a per-device offset
that every rotation preserves — worth about one slot in twenty, which is useful alongside
other evidence and useless alone.

**RSSI is uncalibrated and moves several dB with nothing moving.** Multipath Fading exists
to demonstrate exactly that, and it is why:

- differences are measured against a baseline taken by the same phone in the same minute,
  rather than read off as absolutes (Wall Penetration, Body Absorption, Faraday);
- readings are averaged, and averaged in decibels rather than in power where the quantity
  being compared is shadowing, which is close to Gaussian in dB;
- anything under about 3 dB is treated as noise rather than as a finding.

**Adding decibels is not adding.** Two equal signals are 3 dB together, not twice the
number. Power is summed linearly and converted back, everywhere it matters.

**Advertising intervals are a firmware constant, not a payload field.** They survive an
address change intact, which is what makes fingerprinting work at all — along with the
advertisement's structure, the link-layer traits, and the fact that a device does not
teleport when it changes name.

**Original-equipment tyre sensors are at 315 and 433 MHz.** No phone has a receiver for
that. The aftermarket Bluetooth ones broadcast in the clear at 2.4 GHz and are decoded.

---

## The rules the code follows

**Pure logic lives in `core/`, with tests.** Anything non-trivial is a plain Kotlin class
with no Android imports and real JUnit tests — around 610 of them. A screen with logic
buried in it cannot be tested and will not be trusted.

**Be honest about limits.** Every experiment carries a `limits` field and most carry a
diagnostics panel saying what is actually being seen and why it might be showing nothing.
An empty screen with no explanation is a bug.

**Refuse rather than guess.** Where an inference would attach a name to a stranger's
device, the threshold is deliberately obstructive: strongest confidence only, a refusal
when two candidates match equally well, and every automatic decision logged and reversible.
Losing a device is an inconvenience; naming the wrong one is a false statement that four
other screens then repeat.

**Never invent a signature.** Registry data — OUIs, company identifiers, service and
characteristic UUIDs — is generated by script, never hand-typed, after a hand-written table
turned out to have five wrong entries out of twenty-three. Exposure Scan names no CVEs,
because matching one needs a model *and* a firmware version and neither is in a beacon.

**Every measurement can leave the phone.** Sampler, aggregator, live chart, CSV, share. A
result that can only be photographed is a claim rather than a result.
