# SigEye

Experiments for the radio signals around you. Phyphox, but for Bluetooth and Wi-Fi.

Thirty-eight of them, from *how many dB does a crisp packet block* to *follow one phone
through the address changes designed to stop you*. Everything runs on the phone. No
network code, no accounts, no analytics, nothing leaves the device.

**Version 0.1** — the first one anybody other than its author has been asked to use.
[What is in it, and what to try first](docs/RELEASE_NOTES.md).

**Install:** [latest release](https://github.com/Reconnaishawnce/SigEye/releases/latest) —
a plain public APK download, no login and no zip. See [INSTALL.md](INSTALL.md).

Every experiment carries one of three states, and the home screen shows it.

- **Active** — run on real hardware, in the real world, and behaved.
  28 of them are.
- **Beta** — complete and tested, but never yet run anywhere but a build server.
  6 of them are, marked *(beta)* below.
- **In development** — described and not built, and tapping one does nothing.
  4 of them are, marked *(soon)* below. They are listed on purpose: a list of what is
  planned is more honest than a list that pretends the app is finished.

---

## What is in it

Starred favorites sit at the top of the home screen and can be rearranged; everything
else is grouped by what it is for. `app/src/main/java/com/sigeye/core/Experiment.kt` is
the authoritative list — the table below is a snapshot of it, and a test fails the build
if the two stop agreeing. *Beta* and *soon* below carry the same meaning they do on the
home screen.

### Tools — see and name what is around you

| | |
|---|---|
| **Device Inspector** | Everything broadcasting around you, decoded. |
| **Discovery** | Learn what is normally here, then watch for what is not. |
| **Proximity Radar** | Everything around you, arranged by how close it sounds. |
| **Signal Watch** | Alerts when something you care about comes into range. |
| **Forensics** | Record everything, then work out afterwards what mattered. |

### Counting and sensing — who and what is moving past

| | |
|---|---|
| **Train Spotter** | Counts new Bluetooth devices nearby and flags the bursts. |
| **Speed Estimator** | How fast was that, from the shape of the signal. |
| **Crowd Counter** | Roughly how many people are around you. |
| **Dwell Time** | Who is passing through, and who lives here. |
| **RF Motion Detector** | Notices someone crossing a radio path. |
| **Place Profiler** | Leave the phone somewhere for hours and see the shape of the place. |
| **Daily Rhythm** *(soon)* | The same corner, every day, and what changes. |

### RF physics — how radio actually behaves in your rooms

| | |
|---|---|
| **Doppler Walk** *(beta)* | Walk away counting your steps, and measure the number everything guesses. |
| **Rotational Polarization** | Roll the phone over and watch the signal die. |
| **Channel Congestion** | Who is using the 2.4 GHz band, and whether Bluetooth has room to shout. |
| **Wall Penetration** | Measure how much more the building takes from 5 GHz than from 2.4. |
| **Multipath Fading** | Stand still and watch the signal move anyway. |
| **Body Absorption** | Turn slowly in a circle and find your own shadow. |
| **Faraday Cage Test** | How many dB does a tin, a fridge or a crisp packet really block? |
| **Microwave Interference** | Watch a microwave flatten the 2.4 GHz band. |
| **True Ranging** *(beta)* | Distance by timing light, rather than by guessing from loudness. |
| **Satellites Overhead** *(beta)* | Every satellite your phone can hear, and which ones it trusts. |
| **Radio Weather** *(beta)* | How much radio is landing on this phone, and what it is coming from. |
| **Blink** *(beta)* | Send a message between two phones using only silence and its absence. |
| **Two-Phone Link Budget** *(soon)* | Two phones, a known distance, and what the link actually costs. |

### What is being broadcast

| | |
|---|---|
| **Identity** | Recognize a device after it changes the address meant to hide it. One screen, four modes: |
| &nbsp;&nbsp;↳ **Defeating Randomization** | Follow one phone through its address changes, and check whether it worked. |
| &nbsp;&nbsp;↳ **Rotation Lab** | A whole room's address privacy, grouped by maker and measured by clock. |
| &nbsp;&nbsp;↳ **Persistent Tracking** | Keep a device's name attached to it after it changes address. |
| **Exposure Scan** | Insecure and over-sharing things in range, from what they broadcast. |
| **Wi-Fi Survey** | Every network around you, and what its address gives away. |
| **Bluetooth Explorer** | Ask a device what it has, and learn what the answers mean. |
| **Beacon Decoder** | Reads the beacon formats hiding in the noise. |
| **Traveling Companions** | Record here, then there, then somewhere else. What appears in all of it? |
| &nbsp;&nbsp;↳ **Follow Me** *(beta)* | Narrow a room down to the device travelling with somebody. |

### Mapping

| | |
|---|---|
| **Cell Handovers** | How often your phone changes tower. |
| **Dead Zone Heatmap** *(soon)* | Walk a building and map where the signal dies. |
| **Route Logger** *(soon)* | A journey, and what was in range along it. |

---

## The house style

Two rules shape almost every screen.

**Be honest about limits.** Every experiment carries a `limits` field, and most carry a
diagnostics panel saying what the thing is actually seeing and why it might be showing
nothing. An empty screen with no explanation is treated as a bug. Where a measurement
cannot be made — Android never reports which advertising channel a packet arrived on, a
phone cannot measure airtime, a resolvable address cannot be attributed to a vendor — the
screen says so rather than producing a confident wrong answer.

**Refuse rather than guess.** Where an inference would put a name on a stranger's device,
the threshold is deliberately obstructive: strongest confidence only, a refusal when two
candidates match equally well, and every automatic decision logged and reversible.

## Architecture

```
BleScanHub / WifiScanHub   one radio, refcounted, shared by every screen
core/analysis/*            pure Kotlin, no Android imports, unit tested
core/ScanService           long recordings that survive the screen going off
experiments/<name>/        one Compose screen each
ui/                        shared components: charts, radar, diagnostics, permissions
```

Anything non-trivial is a pure class in `core/` with JUnit tests — currently around 580 of
them. A screen with logic buried in it cannot be tested and will not be trusted.

Registry data (OUIs, company IDs, service and characteristic UUIDs) is generated by script
into `VendorData.kt` and `assets/oui.bin`, never hand-typed.

## Building

JDK 17 and this repo:

```
./gradlew assembleDebug
```

CI runs the unit tests, builds the APK and publishes it to the releases page on every push
to `main`.

## Permissions, and why

| Permission | Why |
|---|---|
| `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` | Scanning on Android 12+ |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | Android 11 and below |
| `ACCESS_FINE_LOCATION` | Android returns no scan results without it, on any version |
| `FOREGROUND_SERVICE_LOCATION` | Long recordings with the screen off |
| `POST_NOTIFICATIONS` | Ongoing recordings and alerts |
| `ACTIVITY_RECOGNITION` | The step counter, for Doppler Walk only |
| `VIBRATE` | Alerts |

`BLUETOOTH_SCAN` is declared **without** `neverForLocation`. That flag is an assertion that
the app will not derive location from scan results; dropping it is what lets the same code
path work unchanged on API 26-30, where location permission is mandatory regardless.

Each experiment asks only for what it needs, at the point it needs it. A user who only
wants the Wi-Fi channel analyzer is never asked for Bluetooth.

## Known limits

- Android throttles Wi-Fi scans hard by default. Developer options can turn that off, and
  the preflight check on the home screen says when it is biting.
- Aggressive OEM battery managers may kill a foreground service anyway. Exempt SigEye from
  battery optimisation if a long recording has gaps.
- Original-equipment tyre pressure sensors transmit at 315/433 MHz. No phone has a receiver
  for that, and no amount of software will change it — that needs an SDR.
- `docs/ROADMAP.md` carries what is still to come, and `docs/EXPERIMENTS.md` the
  constraints that shape all of it.
