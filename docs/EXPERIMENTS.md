# SigEye experiment roadmap

Phyphox for wireless. Each experiment is a self-contained module over one shared spine:

```
sampler  ->  aggregator  ->  live chart  ->  CSV  ->  share
```

The train spotter is experiment 1. It already contains most of that spine.

---

## Three constraints that shape everything

Worth knowing before picking what to build, because they rule out a whole class of
otherwise-obvious ideas.

**1. You cannot see other people's Wi-Fi devices.** Stock Android exposes *access points*
via `WifiManager.getScanResults()`, never client devices. Sniffing probe requests needs
monitor mode, which needs root. So: **Wi-Fi tells you about infrastructure, BLE tells you
about people.** Every people-counting experiment below is BLE-based for that reason.

**2. Wi-Fi scans are throttled to 4 per 2 minutes** on Android 9+. Fine for mapping and
survey work, useless for anything needing sub-second resolution. Developer Options has a
"Wi-Fi scan throttling" toggle that lifts it — worth a one-line prompt in any Wi-Fi module.

**3. MAC randomization is the physics of this domain, not a nuisance.** Phones rotate BLE
addresses every ~15 min. That breaks naive device tracking and *enables* burst detection —
a passing crowd looks like a flood of brand-new addresses. Several experiments below study
the rotation itself.

---

## A. RF physics — the Phyphox heartland

**1. Path Loss Exponent**
Walk away from a fixed source in measured steps; the app fits RSSI against log(distance)
and reports the exponent *n*. Free space gives ~2.0, an indoor corridor 1.5-1.8 (waveguide
effect), through walls 3-5. One number that captures an entire environment, and the single
most useful calibration for every other experiment.

**2. Multipath Fading Histogram**
Hold still, sample one device's RSSI as fast as it advertises for 60 s, histogram the
result. The signal swings several dB with *nothing moving* — that's Rayleigh fading from
reflections. Then wave a hand near the phone and watch the variance jump. Best single demo
of why RSSI ranging is unreliable.

**3. Body Absorption Polar Plot**
Rotate 360° slowly holding the phone at chest height; plot RSSI against compass heading
(magnetometer). A deep null appears when your torso sits between phone and source — water
absorbs 2.4 GHz. Beautiful output, and explains why phone placement dominates every result.

**4. 2.4 vs 5 GHz Wall Penetration**
Most APs broadcast both bands under one SSID with two BSSIDs. Stand in line of sight,
record both; move behind a wall, record again. The 5 GHz drop is consistently larger.
Frequency-dependent attenuation, measured in your own hallway.

**5. Faraday Cage Test**
Baseline, then put the phone in a microwave (off!), foil, a biscuit tin, a lift, a car.
Reports attenuation in dB and how much survives. Instantly satisfying, genuinely
instructive about apertures and seams — the microwave door mesh is the fun one.

**6. Microwave Oven Interference**
Run a microwave, watch 2.4 GHz RSSI and packet loss degrade while 5 GHz stays clean. A
consumer oven leaks around 2.45 GHz, right in the ISM band. Sample fast enough and you can
see the mains-frequency duty cycle. The clearest "why is my Wi-Fi bad in the kitchen"
answer ever produced.

**7. Wi-Fi RTT True Ranging** *(needs 802.11mc on both phone and AP — check first)*
`WifiRttManager` measures actual time-of-flight distance in centimetres. Put it side by
side with the RSSI-estimated distance from experiment 1. Timing light versus guessing from
loudness; the gap is the whole lesson.

**8. Two-Phone Link Budget** *(needs a second Android device)*
One phone advertises via `BluetoothLeAdvertiser` at a known TX power, the other measures.
Step the transmit power through its four levels and watch RSSI track it one-for-one. This
is the calibration rig for everything else — the only experiment where you control both
ends.

---

## B. Counting and sensing

**9. Train Spotter** — shipped. Burst of new BLE addresses against a rolling baseline.

**10. Speed Estimator**
As a vehicle passes, RSSI rises and falls; the width of that envelope plus a known track
distance gives speed. Direct extension of the train spotter — turns "a train went by" into
"a train went by at 60 km/h." The most natural next build.

**11. Crowd Counter**
Unique BLE addresses in a rolling window as an occupancy proxy, calibrated against a known
headcount so it reports *people*, not addresses. Meeting rooms, cafés, gyms, queues. The
calibration step is what makes it honest.

**12. Dwell Time Classifier**
Split addresses by how long they persist: under a minute is a passer-by, over twenty is a
resident. Answers "how much foot traffic does my street actually get" in a way a raw count
never can.

**13. RF Motion Detector**
Hold a fixed link — a second phone advertising, or a stable AP — and watch RSSI variance.
Someone crossing the path perturbs it measurably. This is real Wi-Fi sensing, and it
doubles as a room-occupancy alarm with no camera.

**14. Daily Rhythm**
Long-run ambient device count, bucketed by hour and weekday. Produces a weekly heartbeat
chart: commute peaks, quiet Sundays, the pub emptying. Pairs with the train spotter to show
schedule adherence over weeks.

---

## C. Mapping

**15. Dead Zone Heatmap**
Walk a building tapping a button in each room; get a grid heatmap of signal strength. The
practical answer to where the router should go. Manual position entry avoids needing indoor
positioning.

**16. Channel Congestion Analyzer**
Histogram of APs per 2.4 GHz channel with the 1/6/11 overlap drawn in, plus 5 GHz. Ends
with a recommended channel. The most immediately *useful* utility in the list, and it
teaches why only three 2.4 GHz channels are non-overlapping.

**17. Route Logger**
BLE and Wi-Fi density along a GPS track. Reveals tunnels, station clusters, dead zones,
and — on a train — the exact platform geometry. Exports map-ready CSV.

**18. Cell Handover Counter**
Cell ID, RSRP and RSRQ from `getAllCellInfo()` along a route; counts handovers per km.
Shows network density and where the operator has gaps. Nothing else in the list sees the
macro network.

---

## D. What is actually being broadcast

**19. Beacon Decoder** — shipped.
Parse and display what is in the air: iBeacon UUID/major/minor, Eddystone frames,
manufacturer IDs resolved to company names, service UUIDs, TX power. Turns an anonymous
count into "that's a Tile, that's a supermarket beacon, that's a car." The gateway
experiment — it makes the invisible legible.

**20. MAC Randomization Observatory**
Measure how often addresses actually rotate, and which devices don't rotate at all. Fixed
addresses across hours mean a tracker, a headset, a car, or a fitness band. Produces a
distribution of rotation periods and a list of persistent identifiers.

*Defensive variant:* flag fixed-address devices that appear with you across separated times
**and** places. That is the anti-stalking check Apple and Google both ship, and it falls out
of this module for free.

---

## Shipped so far

| Experiment | Notes |
|---|---|
| Device Inspector | Everything broadcasting, decoded. Nicknames and user lists live here. |
| Signal Watch | Rule-based alerts, including Axon's IEEE block. Runs in the background. |
| Train Spotter | Roadmap 9. Enrollment, rolling baseline, CSV with a phase column. |
| Beacon Decoder | Roadmap 19. iBeacon, Eddystone, AltBeacon, Apple Continuity. |

All four share one scan through `BleScanHub`, so running several at once costs one radio.
The full list below now also appears inside the app, greyed out until built.

## Suggested build order

Not the order above. This one front-loads shared infrastructure and quick wins.

| Order | Experiment | Why here |
|---|---|---|
| 1 | **19. Beacon Decoder** | Makes every later BLE experiment debuggable, and it's the best demo |
| 2 | **16. Channel Congestion** | First Wi-Fi module; builds the Wi-Fi sampler + throttling handling |
| 3 | **1. Path Loss Exponent** | Builds the guided-procedure UI that experiments 2-8 all reuse |
| 4 | **10. Speed Estimator** | Highest personal payoff, reuses the train spotter wholesale |
| 5 | **5. Faraday Cage** | Nearly free once 3 exists; the one people show their friends |
| 6 | **3. Body Absorption** | Adds the sensor-fusion spine (magnetometer) for anything directional |
| 7 | **15. Dead Zone Heatmap** | First mapping module; adds position tagging |
| 8 | **20. MAC Observatory** | Needs long-run data, so it wants the logging spine mature |

Experiments 7, 8 and 18 are hardware-dependent and should be gated behind a capability
check with an honest "your phone cannot do this" message rather than a silent empty chart.

---

## What every module needs

Making these consistent is what separates a toolkit from twenty one-off apps:

- **Declared capability check** up front — fails loudly, never silently.
- **A guided procedure** for the physics ones: "stand at 1 m, hold still, tap Record."
- **A control condition**. Airplane mode, or the same measurement with the source off.
  Phyphox teaches method as much as content, and a measurement without a control is a
  number without a claim.
- **Raw CSV with a header block** recording phone model, Android version, settings and
  timestamp, so a result is reproducible six months later.
- **An uncertainty figure**, not a bare number. RSSI-derived distance especially should
  never be shown without its error bars — see experiment 2 for why.
