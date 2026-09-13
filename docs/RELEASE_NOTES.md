# SigEye 0.1

The first version anybody other than its author has been asked to use. It is a box of
experiments about the radio signals already going past you, and it runs entirely on the
phone: no accounts, no network code, nothing leaves the device unless you press a share
button.

If you are here to try it rather than to read about it, the next two sections are the only
ones you need.

---

## The first ten minutes

**Start with Proximity Radar.** It needs nothing set up and shows something moving within a
few seconds. Everything in range, arranged by how close it sounds. Walk away from a speaker
or a pair of earbuds and watch the blip slide outward.

**Then Exposure Scan.** This is the one that makes the point of the whole app. It reports
what the devices around you are broadcasting about themselves and about the room, in the
open, to anybody who listens. Tap **Show this big** at the end and you have a card you can
send somebody.

**Then Crowd Counter, in a cafe or on a platform.** Roughly how many people are around you,
worked out from how many devices are. It is an estimate and it says so.

**When you have twenty minutes and somebody willing:** Follow Me. It narrows a street down
to the device travelling with a person, without knowing anything about it in advance.

Take a baseline where you are, say whether they are with you, and then walk. The number
falls on its own as everything that did not come with you drops out - there is nothing to
press while you are walking, and the phone can be in a pocket. Two things are worth doing
while you are stopped: walk a slow circle around them, or walk past them and tap as you draw
level. Both pick devices out rather than ruling them out, and both can be repeated.

A word about the first time you try it. Walk a few blocks alone and several devices will
come with you, and they will all be yours - your watch, your earbuds, a tag in a coat.
Anything in your own bag survives every test by construction, because it goes everywhere you
go. The app flags what it thinks is yours and there is a button to say so; once you have,
it is gone from every follow after that.

Run it on a phone you own, carried by somebody who knows you are running it. That is not
legal boilerplate, it is the actual condition under which this is a demonstration rather
than something else.

## What to expect

**It asks for Location permission and never uses your position.** Android will not return
any Bluetooth or Wi-Fi scan results at all without it. That is Android's rule, not a hint
about what the app does, and there is no code in here that reads where you are.

**Some experiments need a few minutes before they say anything.** Rotation Lab wants five
before it will claim anything about how many devices never change their address, because a
phone on a fifteen minute timer has not "failed to rotate" after ninety seconds. Where a
screen is waiting, it says what it is waiting for.

**Numbers come with a denominator.** "Three devices still with you" is not a result; "three
out of the two hundred and fourteen that were in range when you started" is. Anywhere the
app shows you a number big enough to photograph, it shows you what the number is out of and
one line on what it does not prove.

**The long ones keep running with the screen off.** Follow Me, Rotation Lab, Forensics,
Place Profiler and Journey hand themselves to a background service, so the phone can go in
a pocket and the count carries on in the notification. The short ones - a sweep, a circle,
a walk-by - hold the screen awake while they happen, because you are watching those.

---

## If something goes wrong

**If it crashes**, it writes what happened to a file on the phone. Nothing is sent
anywhere. Open **Settings → If it falls over**, and there is a button to send the log
somewhere - read it first if you like, it is a text file and it is meant to be read.

**If a reading looks wrong**, most screens have a **What this is seeing** panel at the
bottom with the raw counts behind the result. That is usually enough to tell a broken
measurement from a surprising one.

**If Wi-Fi results look stale**, Android throttles Wi-Fi scanning to about four scans every
two minutes unless developer options say otherwise. The app detects this and says so.

---

## What is in this release

Thirty-four experiments. Twenty-seven are marked **Active**, which in this app means they
have been run on real hardware in the real world and behaved.

**Two are marked Beta**, meaning complete and tested but not yet run anywhere but a build
server:

- **Follow Me** has been rebuilt more than once for this release and is still the newest
  thing here. It works, and the numbers it decides on - how long a device may go unheard
  before it counts as gone, how steady something has to be before it is called yours - are
  judgement calls that want checking against real walks. They are all sliders.
- **Doppler Walk** fits a path loss curve to a walk and has not been out on one.

**Five are marked In development.** They are described and not built, and tapping them does
nothing. They are visible on purpose, because a list of what is planned is more honest than
a list that pretends the app is finished.

### New in 0.1

- **Follow Me is one continuous follow.** Start it and walk; the list shrinks on its own and
  the number only falls. Anything unheard for a minute drops out and stays out, because
  something that went quiet while you covered a quarter of a mile did not come with you.
  It runs with the screen off, so the walk is a pocket rather than a lit phone held down a
  street.
- **Two ways to pick a device out rather than rule it out.** Walk a circle around somebody
  and whatever is on them stays the same distance from you the whole way round. Walk past
  them and whatever is on them rises as you draw level and comes back down. Both can be done
  as many times as you like, and every one keeps its own chart so you can see the shape the
  verdict was read from.
- **A device you find leads somewhere.** Name it, put it on a list, open it on the radar,
  watch it change address, walk towards it, or keep following it across address changes
  until you say otherwise.
- **Presentation mode.** One result, alone on a black screen, in type you can read across a
  room, with a card you can share. The denominator and the caveat are drawn into the image
  so they cannot be cropped off.
- **Saved runs.** Nine experiments can save a run with a name and put two of them side by
  side. Two percent of movement reads as "steady" rather than as an improvement, because
  signal strength wanders that much with nothing happening.
- **Microwave Interference has a control band.** It watches a 5 GHz radio as well, so "2.4
  collapsed and 5 did not" is the finding rather than the drop. If both fall, the experiment
  failed and it tells you so.
- **Train Spotter decides on a sliding window.** A burst landing across a bin boundary used
  to be cut in half and missed silently. The count on screen is the last eight seconds
  rather than a sawtooth that resets, and the baseline has a live chart of what the radio is
  actually finding.
- **Rotation Lab can be left running** with the screen off, which is the measurement it is
  for: a rotation period is only measured rather than assumed once a fifteen minute timer
  has come round at least once.
- **Every chart says what it found out loud** for anybody using a screen reader - the
  finding in words, not a label saying "chart".
- **Crashes leave a file** on the phone, in Settings, sent only if you send it.

### Known rough edges

- Follow Me's thresholds are judgement calls that have not been checked against many real
  walks. If your own kit keeps surviving, the settings are where to say so.
- Crowd Counter's devices-per-person figure is an assumption, not a measurement, and there
  is no way yet to correct it against a headcount you actually took.
- Faraday Cage Test measures a before and after without first measuring how much the room
  wanders on its own, so a shallow result is hard to tell from noise.
- Train Spotter collects ground-truth taps and does not yet fit its threshold to them.
- Speed Estimator does not check itself against GPS, which it could.
- The shipped APK is a debug build. That is deliberate for a tool distributed by direct
  download, but it means it is larger than it needs to be.

---

## What it does with what it hears

It records what devices around you are broadcasting, including things that identify them.
All of it stays on the phone, apart from the device names and settings that Android's own
backup copies to your Google account - the app warns about that on first run and
`Settings → What leaves this phone` says it again.

Recordings and exported files live in the app's own folder and go nowhere until you share
them. Crash logs are the same. There is no analytics of any kind, and no network code to
add any with.
