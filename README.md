# SigEye

Experiments for the radio signals around you. Phyphox, but for Bluetooth and Wi-Fi.

Every experiment is a self-contained module over one shared spine:

```
sampler  ->  aggregator  ->  live chart  ->  CSV  ->  share
```

`docs/EXPERIMENTS.md` holds the roadmap of twenty planned experiments and the three
Android constraints that shape them.

No network code. No accounts. No analytics. Everything stays on the phone.

---

## Experiment 1: Train Spotter

Counts **newly seen Bluetooth LE addresses** per time bin and tells you when they burst.

Built to answer one question: *is a train going past my apartment right now?* A passenger
train is a hundred phones, earbuds and fitness trackers moving through your radio horizon in
under a minute. That shows up as a sharp spike in previously-unseen BLE addresses.

## How it decides something is a train

1. Scan BLE continuously, unfiltered, in a foreground service.
2. An address counts as **new** only if it has not been seen in the last 10 minutes.
3. New-device events are bucketed into 5-second bins.
4. A **rolling baseline** is the median of the last 60 non-spiking bins (~5 minutes).
5. A bin **spikes** when it is both at least 3x the baseline and at least 4 devices.
6. A spike fires a heads-up notification, rate-limited to one per 2 minutes.

Why a baseline instead of a fixed threshold: modern phones rotate their BLE address roughly
every 15 minutes, so even a stationary neighbour mints a steady drip of "new" addresses.
That drip *is* the baseline. A train is a sharp multiple of it. A fixed threshold would need
retuning for every location and time of day; a baseline retunes itself.

A two-minute warm-up after Start keeps the first few bins from alerting against an empty
baseline.

## Building

Requires nothing but a JDK 17 and this repo:

```
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

CI builds the same APK on every push. Grab it from the **Actions** tab of this repo -
open the newest green run and download the `SigEye-apk` artifact.

## CSV log

Every closed bin is appended to app-specific external storage, one file per day:

```
/sdcard/Android/data/com.sigeye/files/sigeye-YYYY-MM-DD.csv
```

Columns: `timestamp,epoch_ms,new_count,active_unique,baseline,spike,label`

`label` is `TRAIN` for bins where you pressed the **Train now** button. Press it when you
actually hear a train go by; after a few days you can check the `spike` column against the
`TRAIN` column and tune the threshold against real ground truth instead of guessing.

Pull it with:

```
adb pull /sdcard/Android/data/com.sigeye/files/ ./sigeye-logs
```

Or use the in-app **Export CSV** button, which shares every log file.

## Settings

| Setting | Default | What it does |
|---|---|---|
| Bin length | 5 s | Width of one chart bar / one CSV row |
| New-device window | 10 min | How long before an address counts as new again |
| Signal floor | -85 dBm | Ignore advertisements weaker than this |
| Chart history | 30 min | How far back the chart reaches |
| Burst threshold | 3.0x | Multiple of baseline that counts as a burst |
| Minimum burst size | 4 | Never alert below this, however quiet it is |
| Alert cooldown | 120 s | One train, one buzz |

If trains get missed, lower the burst threshold or raise the signal floor toward -70 dBm so
distant clutter stops padding the baseline. If it cries wolf, raise the threshold.

## Permissions

- `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` - Android 12+ scanning
- `BLUETOOTH` / `BLUETOOTH_ADMIN` - Android 11 and below
- `ACCESS_FINE_LOCATION` - required for unfiltered scan results on every supported API level
- `FOREGROUND_SERVICE_LOCATION` - to keep scanning with the screen off
- `POST_NOTIFICATIONS` - the ongoing scan and the burst alerts

`BLUETOOTH_SCAN` is declared **without** `neverForLocation`. That flag is an assertion that
the app will not derive location from scan results; dropping it is what lets the same code
path work unchanged on API 26-30, where location permission is mandatory regardless.

## Known limits

- Android throttles BLE scans. A watchdog restarts the scan if results stop arriving for 90
  seconds, and refreshes it every 25 minutes regardless.
- Aggressive OEM battery managers (Samsung, Xiaomi, OnePlus) may kill the foreground service
  anyway. Exempt SigEye from battery optimisation if the log has gaps.
- Bin counts are wall-clock, not monotonic. A manual clock change mid-run will distort one bin.
