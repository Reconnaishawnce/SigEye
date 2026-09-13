# Thirty ways to make SigEye better

A deep read of the app as it stands at v74: 28 experiments, 620 tests, lint enforced.
Ordered by what I would actually do first, not by area. Every item says what is wrong, why
it matters, and roughly what fixing it looks like.

The short version: the physics and the honesty are in good shape. The weaknesses are
(a) nothing has been checked against reality, (b) the app quietly breaks its own privacy
promise in one place, (c) rotating the phone destroys a measurement, and (d) the shared
machinery has been copy-pasted often enough that the copies have started to diverge.

---

## The six that matter most

### 1. Seven experiments have never run on a phone

Polarization, Channel Congestion, Wall Penetration, Exposure Scan, Persistent Tracking,
Rotation Lab and the favorites screen all shipped without once being run on hardware. CI
proves they compile and that the arithmetic is right; it proves nothing about whether
`RollSensor` reads the axis it claims, whether dual-band pairing finds a real router, or
whether the Rotation Lab's chains survive a real cafe.

This is the single biggest risk in the project, and the only one I cannot close. A
half-hour with the phone in a busy room would close most of it. **Polarization first** —
its geometry got shipped backwards once already and was caught by reading, not by testing.

### 2. `allowBackup="true"` sends the device book to Google — *done*

The manifest allows backup and there is no `backup_rules.xml` or
`data_extraction_rules.xml`. So every `SharedPreferences` file — including `devicebook`,
which holds the names a user has given to devices, and `following`, which holds the log of
which addresses were linked to which — is backed up to the user's Google account and
restored onto their next phone.

The app says, on the home screen and in the README, *everything runs on this phone; no
accounts, no network*. That is currently not true. Either set
`android:allowBackup="false"`, or add rules that exclude the stores that contain
observations about other people and keep only the harmless ones (favorites, first-run).
The second is better behavior and takes ten lines.

### 3. Rotating the phone destroys an in-progress measurement — *done*

No screen uses `rememberSaveable`, the activity declares no `configChanges`, and the
manifest does not lock orientation. So turning the phone sideways during a Body Absorption
sweep, a Doppler walk, a polarization roll or a Faraday comparison destroys the activity
and every `remember`ed value with it — the sweep, the stage, the picked source, all of it.

Three of those experiments explicitly ask the user to move the phone about while
recording. This is not a theoretical bug.

The honest fix is to hoist each screen's measurement state into a `ViewModel` so it
survives configuration change. The cheap fix, which is defensible for a measurement app,
is `android:configChanges="orientation|screenSize|keyboardHidden"` on the activity so
Android stops recreating it. Do the cheap one now and the proper one per screen over time.

### 4. The device picker is copy-pasted into seven screens — *done*

Absorption, Doppler, Explorer, Fading, Faraday, Motion and Polarization each declare their
own `data class Candidate`, their own advert-collection loop, their own freshness filter
and their own rate sort. They have already diverged: some show a vendor fallback for the
name and some do not, some require three sightings and some two, some sort by rate and some
by signal.

This is the most-touched code in the app and the least shared. It wants to be one
`ui/SourcePicker.kt` with one `Candidate` type, one freshness rule and one "the rate
matters more than the strength" explanation. It would delete something like four hundred
lines and make every picker behave the same way, which is what the user asked for about the
radar and is just as true here.

### 5. Twenty `BleScanHub.acquire` sites and no leak guard — *done*

Every screen acquires and releases the radio by a string tag in a `DisposableEffect`. That
is the right shape, but nothing detects the failure mode: a tag acquired and never
released leaves the radio on forever, and the symptom is a flat battery rather than a
crash.

Add a debug-only assertion that logs when a tag is acquired twice without a release, and
put the live claim tags into the home screen's radio card so a leak is visible rather than
silent. `BleScanHub` already tracks `subscribers` — it just does not say who.

### 6. Twelve screens have no diagnostics panel — *mostly, 17 of 30 screens have one*

The house rule is that an empty screen with no explanation is a bug. Beacons, Cells,
Fading, Faraday, Inspector, Locate, Microwave, Motion, Population, Radar, Train Spotter and
Watchlist do not have a `DiagnosticsPanel`. Several of them are the ones most likely to
show nothing at all — Microwave needs a microwave, Cells needs an OEM that reports cell
identity, Locate needs a device that is still advertising.

These are the screens where a user concludes the app is broken. The panel already exists
and takes a list of `Diagnostic`.

---

## Shared machinery

### 7. `core/analysis/` is thirty-one flat files — *done*

The `Track` / `RotationTrack` collision was a symptom, not an accident. The package now
holds beacon decoding, path loss fitting, convoy detection, forensic recording, fingerprint
scoring and spectrum arithmetic side by side. Subpackages — `analysis/rf`,
`analysis/identity`, `analysis/recording` — would make the next collision impossible and
make it obvious where a new file goes.

### 8. Seven `SharedPreferences` files and no settings screen

`devicebook`, `favorites`, `firstrun`, `following`, `ignorelist`, `trainspotter`,
`watchlist`. Each store invented its own file and its own key names. There is nowhere to
see what the app is holding, nowhere to change a default, and no way to back any of it up
deliberately (see item 2).

One settings screen listing every store, what is in it, and a way to clear each. Plus one
export-everything button, which is also the honest counterpart to turning Android's backup
off.

### 9. Twenty-three per-packet writes to Compose state

Several screens assign to a `mutableStateOf` inside `BleScanHub.adverts.collect`, which
means a recomposition per packet — hundreds a second in a busy room. Most screens already
avoid this by buffering and publishing on a ticker; the rest should. It is the difference
between a screen that costs battery and one that costs a lot of battery.

### 10. Two core classes have no tests

*Corrected after the fact: this said four. `AbComparison` and `SweepSession` are both
tested in `AbAndSweepSessionTest.kt`, and I missed them because I looked for a file named
after each class. The finding below is what was actually true.*

`LiveAddress` and `RotationLab`. `LiveAddress` is now shared by two trackers and is where
every identity in the app is built — it is the worse of the two to have left untested,
because if the interval it reports were wrong, Defeating Randomization, Rotation Lab and
Persistent Tracking would all be wrong together and in the same direction. `RotationLab`
does the cohort and rhythm aggregation the whole Rotation Lab screen rests on.

### 11. No test covers a screen at all

620 unit tests, none of which instantiate a composable. A Compose UI test that opens each
experiment, grants nothing, and asserts the permission gate renders would catch a whole
class of mistake — an unregistered id, a crash in a preview path, a stage machine that
cannot reach its own result screen — that currently only a human with a phone can catch.

### 12. No way to replay a capture — *done*

Every experiment consumes `BleScanHub.adverts` live. There is no way to record a raw
advert stream and feed it back in, which means a bug that happens on a train can only be
debugged on that train. A `RecordedSource` that writes every advert to a file and a debug
setting that reads one back would make every field bug reproducible at a desk, and would
let the analysis classes be tested against real captures rather than only against
synthetic ones.

### 13. `Experiments.featuredReasons` only covers five

Star anything else and the favorites card falls back to `blurb`. The blurbs are good, but
the five hand-written reasons are noticeably better, and the inconsistency is visible the
moment a user stars a sixth thing. Either write one for every ready experiment or drop the
mechanism and use blurbs throughout.

---

## Reach and usability

### 14. The app is not accessible — *done*

One `onClickLabel` in the entire codebase, on the favorites glyphs I added an hour ago.
Sixteen `Canvas` charts with no semantics at all: to a screen reader the polar plot, the
spectrum chart and the radar are blank rectangles. Every chart should carry a
`contentDescription` that states the finding in words — "peak at 40 degrees, notch at 210,
6.2 dB between them" — which the code already computes for the text underneath.

### 15. Every string is hard-coded

`strings.xml` has three lines; there are roughly 578 inline `Text(` calls. No translation
is possible, and the `supportsRtl="true"` in the manifest is untested and almost certainly
wrong in places. This is a big mechanical job and it does not have to happen at once — but
new screens should stop adding to the pile.

### 16. The explanatory text assumes reading, not scanning

The writing is the best thing about this app, and there is a lot of it. Someone standing in
a corridor holding a phone reads the heading and the number and nothing else. Every screen
would benefit from a one-line "what you are looking at" that is true before any measurement
exists, and the long-form explanation moved behind the `Section` collapse it already has.

### 17. Nothing tells a user what to do when a reading is bad

Diagnostics say what is being seen. Verdicts say what is wrong. Very little says *do this
instead*. "Only 12% coverage" should be followed by "keep turning"; "5 GHz too weak" by
"move closer or accept 2.4"; "no candidate matched" by "stand still for a minute". Some
screens do this well — Doppler and Wall Penetration — and the rest could copy them.

### 18. There is no way to compare two runs of the same experiment — *done for six of them*

Forensics has history and comparison. Wall Penetration has saved spots. Everything else
throws the previous run away. The generic version — save a run, name it, list past runs,
diff two — would turn eight experiments from a live readout into a record, and it is one
store plus one screen.

### 19. No home-screen search — *done*

Twenty-eight experiments in five categories plus a favorites list is now enough that
finding "the one about the microwave" means scrolling. A filter field at the top matching
title, blurb and `teaches` would cost twenty lines.

### 20. The first-run experience still starts with permissions — *done*

The welcome card is good, but the first thing a new user meets after it is a permission
wall on whichever experiment they tap. A "run this one now" button on the welcome card that
opens Proximity Radar, asks for exactly what it needs, and shows something moving within
ten seconds would be a much better first sixty seconds.

---

## Per-experiment depth

### 21. Crowd Counter's `devicesPerPerson = 2.0` is a guess with no way to improve it

The doc comment says "calibrate it against a headcount you actually know" — and then the
app gives no way to do that. Add a calibration mode: enter a real headcount, and the app
solves for the factor from what it can see and remembers it per place. That turns the
weakest number in the app into a measured one, and it is exactly the Doppler Walk trick
applied to people.

### 22. Faraday Cage Test should measure the noise floor first

The measurement is a before-and-after on one source. It has no idea what the room's own
variance is, so a 4 dB "blocking" result from a crisp packet is indistinguishable from
multipath. Take thirty seconds of baseline variance before the first reading and report the
block depth against it — the Fading machinery already computes exactly this.

### 23. Microwave Interference needs a control band — *done*

A microwave flattening 2.4 GHz is dramatic and unfalsifiable as presented. Watching a
5 GHz access point at the same time gives a control: 2.4 collapses, 5 does not, and the
difference is the finding rather than the drop. `WifiScanHub` already provides both bands
and Wall Penetration already pairs them.

### 24. Train Spotter still uses a hand-tuned threshold

It has a ground-truth button — press it when a train actually passes — and it does nothing
with the labels beyond writing them to CSV. The app could fit the threshold to the labels
after a few days and tell the user what it learned. The data is already being collected for
a step that was never built.

### 25. Rotation Lab cannot be left running

Its most interesting output needs half an hour of a busy room, and it only runs while its
screen is open with `KeepScreenOn`. It should be a `ScanService.Mode` like Forensics and
Journey are, so a user can start it on a train and read it later. The house rule already
says recordings belong in the service.

### 26. Speed Estimator has an available cross-check it does not use

The phone knows its own speed from GPS. Comparing the RSSI-derived estimate against a known
speed, on a walk or a drive, would turn a plausible number into a calibrated one — and
would show the user how good the method is, which is more interesting than the number.

### 27. Bluetooth Explorer connects without a written-down rule about writes — *done*

It performs GATT reads on a stranger's device on request. Reads are benign; the code should
state plainly, in the file and on the screen, that it never writes and never pairs —
because the next person to extend it will otherwise add a write, and "it is only a test
device" is how that goes wrong. Exposure Scan already models the right tone.

### 28. Wall Penetration wants a floor plan, not a list of spots

It currently produces "Spot 1, Spot 2, Spot 3". Letting the user name a spot ("kitchen",
"upstairs back") and showing them ordered by excess loss turns a list of numbers into a
survey of a building, which is what someone actually walks around to get.

---

## Engineering

### 29. The release build is not minified and the shipped artifact is a debug build

`isMinifyEnabled = false` on release, and CI ships `app-debug.apk`. That is a deliberate,
reasonable choice for a tool distributed by direct download — debug builds are easier to
attach a debugger to and there is no Play listing to satisfy. But it means the APK carries
full symbols and no shrinking, and the release variant is effectively dead code. Either
make release the shipped artifact with R8 on, or delete the release block so nobody assumes
it is used.

### 30. There is no way to learn about a crash in the field — *done*

No crash reporting, deliberately and correctly — but the consequence is that a crash on
someone else's phone is invisible forever. A local, opt-in crash log written to
app-specific storage with a share button would keep the no-network promise intact and still
let a user send back a stack trace. `Thread.setDefaultUncaughtExceptionHandler` plus the
existing `CsvExport` plumbing is most of it.

---

## What I would do in what order

1. Items 2 and 3 — a broken privacy promise and a lost measurement, both small fixes.
2. Item 1 — take the phone somewhere busy and run the seven untested experiments.
3. Items 4 and 6 — the shared picker and the missing diagnostics panels, together about a
   day and they touch nineteen screens between them.
4. Item 12 — record-and-replay, because it makes everything after it cheaper to get right.
5. The rest as appetite allows; items 21 to 28 are each a satisfying evening.

---

## Re-review, after turns 8 to 12

Checked against the code rather than against memory. Items 21 to 30 were the per-experiment
and engineering half of the list, and almost none of them have been touched - the work since
went into the shared machinery instead, which was the right order but leaves this half
exactly where it was.

| # | Where it stands |
|---|---|
| 21. Crowd Counter's people factor | **Open.** `Environment.devicesPerPerson` is still a constant per environment, 1.5 to 2.5, with no way to correct it from a headcount somebody actually took. |
| 22. Faraday's noise floor | **Open.** Still a straight before-and-after with no idea what the room's own variance is. `FadingAnalysis` computes exactly the number needed and is not called. |
| 23. Microwave's control band | **Done in turn 13.** Two radios pinned by BSSID and followed through both phases. 2.4 falling alone is the finding; both falling is a failed run and it says so. The unearned "5 GHz would have been untouched" line is gone. |
| 24. Train Spotter's threshold | **Open.** The ground-truth button still only writes labels to CSV. Days of labelled data are being collected for a step nobody built. |
| 25. Rotation Lab left running | **Open.** Not a `ScanService.Mode`, so its best output - half an hour of a busy carriage - still needs the screen awake and in your hand. |
| 26. Speed Estimator's cross-check | **Open.** No GPS comparison. |
| 27. Bluetooth Explorer's rule about writes | **Done in turn 13.** On the screen, in the KDoc, and enforced by `GattWriteRuleTest`, which reads the source and fails the build on a mutating call. |
| 28. Wall Penetration's floor plan | **Half.** Saved runs now carry a name you choose and the excess loss, which is most of the survey. The in-screen spot list still says "Spot 1, Spot 2" and is not ordered by loss. |
| 29. The shipped artifact | **Open.** `isMinifyEnabled = false` and CI still ships `app-debug.apk`. Fine as a decision, but the release block is dead code that reads as if it were used. |
| 30. Crashes in the field | **Done in turn 13.** Traces written to app-private storage, listed in Settings, sent only on a tap. No network anywhere in it. |

The honest summary: the shared work is largely done and the per-experiment work is largely
not. Item 27 and item 23 were the two that bothered me, because one was a safety statement
that cost nothing and the other was the app claiming something it had not measured. Both
are done, with item 30, in turn 13. What is left of this half - 21, 22, 24, 25, 26, 28 and
29 - is a satisfying evening each, and none of it is load-bearing for a first release.

---

## Six more, about being easy to use and worth filming

The app is now for two audiences: somebody trying an experiment, and somebody recording a
demonstration to show other people how exposed they are. These serve both.

### 31. A tutorial that runs on a recording rather than on the room

Every experiment needs a room with something in it, and a first try in an empty flat at
eleven at night shows nothing and teaches nothing. The replay machinery already exists.
Bundle one ninety-second capture with the app, and let the first run of any experiment
offer "watch this work on a recording first". No permissions, no waiting, no luck involved,
and the result is real data rather than a mockup. It is also the only way a demo can be
rehearsed before it is filmed.

### 32. A presentation mode, because screen recordings are the deliverable

People will record their screen. What the app should provide is a frame worth recording: a
full-bleed result card with the headline number in enormous type, the denominator under it,
no navigation, no diagnostics, no permissions banner. One tap from any finished experiment.
Right now the most striking result in the app - a room narrowed to three devices - is a
paragraph in the middle of a scrolling page.

### 33. Animate the number when the number is the finding

`CountUp` exists and is used on exactly one screen. The survivor count in Follow Me going
from two hundred and fourteen to three is the most watchable thing this app does and it
currently teleports between frames, which reads as a glitch rather than as an elimination.
Animate every count that is a finding, and let eliminated rows fall out of the list rather
than vanish between recompositions.

### 34. One true sentence at the top of every screen

Review item 16, still open and now across thirty screens. Every screen needs a line that is
true before any measurement exists - "this measures how much of 2.4 GHz your building is
eating" - above the fold, before the long-form explanation. Somebody standing in a corridor
reads the heading, the number, and nothing else.

### 35. Say what to do when a reading is bad, everywhere

Review item 17, still open. Follow Me's guided flow now proves the pattern: it says "the
target probably changed address partway through, walk another leg" instead of showing an
empty list. One shared component that turns a verdict into a next action, and every screen
with a verdict gets one.

### 36. Build the caveat into the share, not next to it

A screenshot of "3 devices still with you" with the denominator cropped off is exactly the
overclaim this app's whole tone is written to avoid, and cropping is what happens to
screenshots. Anything shareable should carry its denominator and its one-line limit baked
into the image - "3 of 214, after two legs and 1.4 km" - so the honest version is the one
that travels. This matters more than the other five, because the share is the part other
people see.
