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

**When you have twenty minutes and somebody willing:** Follow Me. It narrows a room down to
the device travelling with a person, without knowing anything about it in advance. It walks
you through it a step at a time. Run it on a phone you own, carried by somebody who knows
you are running it - that is not legal boilerplate, it is the actual condition under which
this is a demonstration rather than something else.

## What to expect

**It asks for Location permission and never uses your position.** Android will not return
any Bluetooth or Wi-Fi scan results at all without it. That is Android's rule, not a hint
about what the app does, and there is no code in here that reads where you are.

**Some experiments need a few minutes before they say anything.** Rotation Lab wants five
before it will claim anything about how many devices never change their address, because a
phone on a fifteen minute timer has not "failed to rotate" after ninety seconds. Where a
screen is waiting, it says what it is waiting for.

**Numbers come with a denominator.** "Three devices still with you" is not a result; "three
out of two hundred and fourteen, after two legs" is. Anywhere the app shows you a number
big enough to photograph, it shows you what the number is out of and one line on what it
does not prove.

**Screens stay awake while they record**, so the phone will not lock in the middle of a
measurement. That costs battery. Leave the screen off in your pocket and the recording is
over.

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

- **Follow Me** was rebuilt from scratch for this release, including a new test that works
  by walking a circle around somebody. None of that has been on a phone yet.
- **Doppler Walk** fits a path loss curve to a walk and has not been out on one.

**Five are marked In development.** They are described and not built, and tapping them does
nothing. They are visible on purpose, because a list of what is planned is more honest than
a list that pretends the app is finished.

### New in 0.1

- **Follow Me is a guided sequence** rather than a page of controls: a census, a question
  about whether the person is actually with you, a circle walked around them, then walking
  together. The circle is new and is the interesting part - a device on the person stays the
  same distance from you the whole way round, while a device across the room does not.
- **Presentation mode.** One result, alone on a black screen, in type you can read across a
  room, with a card you can share. The denominator and the caveat are drawn into the image
  so they cannot be cropped off.
- **Saved runs.** Six experiments can save a run with a name and put two of them side by
  side. Two percent of movement reads as "steady" rather than as an improvement, because
  signal strength wanders that much with nothing happening.
- **Microwave Interference has a control band.** It now watches a 5 GHz radio as well, so
  "2.4 collapsed and 5 did not" is the finding rather than the drop. If both fall, the
  experiment failed and it tells you so.
- **Every chart says what it found out loud** for anybody using a screen reader - the
  finding in words, not a label saying "chart".
- **Panels can be rearranged** on screens that have them, under a grey "Arrange panels" at
  the bottom.

### Known rough edges

- Three experiments - Faraday Cage Test, Multipath Fading, Body Absorption - cannot save a
  run yet. The other six can.
- Rotation Lab has to stay on screen to keep recording. Its best output wants half an hour
  of a busy room, which currently means half an hour of holding the phone.
- Crowd Counter's devices-per-person figure is an assumption, not a measurement, and there
  is no way yet to correct it against a headcount you actually took.
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
