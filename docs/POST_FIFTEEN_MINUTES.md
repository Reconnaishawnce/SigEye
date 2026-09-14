# Fifteen Minutes Is Not a Privacy Feature

*Draft. Two sections need capture data before this ships. They are marked.*

Your phone changes its Bluetooth address every fifteen minutes so that nobody can follow
you around by it. The Bluetooth Special Interest Group has already published a fix for the
fact that this does not work, in Core 6.1, and almost nothing in anybody's pocket has that
fix yet.

Here is their own description of the problem, from the announcement of a feature called
Randomized RPA Updates:

> Attackers may be able to model device behavior by observing RPA update patterns. Even
> with the maximum 15-minute update interval, RPA addresses can become predictable.

I want to show you what that sentence looks like on a sidewalk, using a phone you already
own and an app you can install in a minute. And I want to show you a second problem that
the Core 6.1 fix does nothing about, because it does not attack the randomization at all.

## What the address rotation is supposed to do

Bluetooth Low Energy devices shout into the air constantly. A phone, a watch, a pair of
earbuds, a tire pressure sensor, a fridge. Each shout carries an address, and if that
address never changed it would be a tracking number that follows you between buildings.

So the specification defines a Resolvable Private Address. Twenty four bits of random data
and twenty four bits of a hash computed with a key only your own paired devices hold. Your
watch recognizes your phone. Everybody else sees forty eight bits of noise that get
replaced on a timer.

The timer is `HCI_LE_Set_Resolvable_Private_Address_Timeout`. It runs from one second to
one hour, and its default is 900 seconds. Fifteen minutes is a default that most stacks
inherit, not a rule, and any article telling you your phone rotates every fifteen minutes
is quoting a factory setting.

That is a real privacy mechanism and it does real work. The problem is what it does not
cover.

## Way one: the address was never the identity

Rotating the address changes the name on the packet. It changes nothing else about the
packet, and nothing else about the device.

The advertisement still carries the same fields in the same order at the same lengths. It
still declares the same company, the same services, the same transmit power. It still uses
legacy or extended advertising, still says whether it will accept a connection, still uses
the same physical layer and the same advertising set. Firmware decides every one of those,
and the privacy scheme does not touch any of them.

It also still talks on the same heartbeat. A BLE device advertises on an interval set in
whole numbers of 0.625 millisecond slots. Apple uses 244 of them on a lot of things, which
is 152.5 milliseconds. Renaming yourself does not change how often you speak.

None of this is my discovery, and I want to be clear about that before going further,
because this field has a bad habit of rediscovering 2019.

**Becker, Li and Starobinski** published the address-carryover attack in 2019. Their
insight was that the address and the identifying tokens inside the payload change on
different schedules, so an observer watching the boundary can stitch the two sides
together. **Martin and colleagues** took apart Apple's Continuity protocol the same year
and found that its messages fingerprint device type and OS version, with sequence numbers
predictable enough to track across randomization. **Celosia and Cunche** extended that in
2020. **Gagnon, Gambs and Cunche** showed in 2024 that signal strength alone can link
traces across a rotation.

Every individual signal in what I am about to describe is published, most of it years ago,
in venues where the vendors read the proceedings.

## Way two: you do not have to defeat it at all

This is the part that gets less attention, and it is the part that would worry me if I
carried an iPhone.

Randomization stops you recognizing a device you lost. It does nothing about a device you
never lost.

Stand on a street and your phone can hear a few hundred Bluetooth devices. Shop beacons,
televisions through walls, parked cars, headphones, tags, laptops. Now walk. Everything
fixed to the street falls out of range behind you. People walking the other way separate
from you at twice walking pace and are gone in under a minute. What is left after ten
minutes is a very short list: things traveling with you.

Subtract your own pockets and you have things traveling with the person in front of you.

No fingerprinting. No payload parsing. No cleverness of any kind. Just the observation that
a radio you can still hear is a radio you never had to re-identify, and that walking is an
extremely cheap way to eliminate several hundred candidates.

The countermeasure is aimed at a different attack.

## The clock is the flaw, and the SIG said so first

Rotation does not happen on a wall clock. The timer starts when the current address was
generated and runs free. So two phones on identical 900 second timeouts change at different
moments, and they keep changing at those different moments, indefinitely.

That offset is a property of the device that every rotation preserves. It is exactly the
continuity that rotating is supposed to destroy.

I spent a while thinking this was an original observation and it is not. The quote at the
top of this post is the standards body describing the same thing and shipping a fix for it:
Core 6.1 adds a version of that HCI command that randomizes the window rather than holding
a fixed period, defaulting to a range of 480 to 900 seconds.

Which leaves an awkward question for anybody selling a phone. The fix exists. It is in the
specification. Is it in the device you shipped this year?

There is a second edge to this. A rotation is a moment where you can lose a device, and it
is also a moment that produces evidence, because something that had been keeping pace with
you by coincidence does not put on a new address in the same instant and carry on keeping
pace. Every rotation a device survives makes the next claim about it stronger.

## Two signals nobody can take away

When my app decides whether a new address is an old device, it scores six things. Four of
them a manufacturer could fix tomorrow: the packet shape, the interval, the steadiness of
that interval, and the Apple message types. Clean those up and the attack gets harder.

The other two are free, and this is the part I would put in front of a vendor.

**A device cannot move in the instant it renames itself.** If the signal was at -62 dBm a
moment ago and the new address is at -63 dBm, that is not a coincidence to be engineered
away. It is a fact about physics.

**A new name cannot have been talking a minute ago.** A rotation produces an address that
did not exist an instant earlier. Anything already advertising is a different device,
however alike it looks. That is a fact about time.

Apple can randomize the rotation interval, strip the Continuity payloads, jitter the
advertising rate, and both of those signals still work. Any privacy scheme built on
changing a name has to live with the fact that the thing wearing the name stayed where it
was.

## What this costs an attacker

⚠️ **NEEDS DATA.** This section gets the numbers from a real walk: how many devices at the
start, how long to narrow, how many address changes survived, and what the target turned out
to be. Do not publish this section until the capture exists.

One phone. No root, no jailbreak, no software defined radio, no second receiver. A normal
Android handset, an app, and a walk.

## The controlled run

⚠️ **NEEDS DATA.** Ten iPhones with known addresses, mixed models and iOS versions, ninety
minutes of capture so each device rotates three or more times. Then the app's claims checked
against the truth: measured rotation period per device, whether the phase holds, whether the
Continuity payload changes at the same instant as the address, and the false link rate at
each confidence band.

A negative result is worth publishing here too. If Apple rotates every identifier atomically
and re-seeds the timer, that is a well built privacy scheme and it should be said out loud.

## What would actually fix it

**Ship Core 6.1 randomized RPA updates.** The specification already has it. A fixed period
is a pattern and the SIG has written down that it is a pattern.

**Rotate every identifier at the same instant.** This is the 2019 finding and it is seven
years old. Anything in the payload that outlives the address is a bridge across the
boundary.

**Add slop to the advertising interval.** The rate is a firmware constant that survives
every rotation. It is coarse, and it still separates a phone from a fridge at a glance.

**Say what your device does.** No phone I know of tells its owner how often its Bluetooth
address rotates or whether the payload rotates with it. That is a number people could
reasonably be told.

None of this makes following somebody impossible. The walk still works. What it does is
make the difference between an app anybody can install and an attack that needs effort, and
that difference is most of security.

## Check my work

The app is SigEye, it is free, and it is on GitHub. It exports a case file for every run:
what happened minute by minute, what it found, what argues against what it found, and the
count every five seconds. The whole point of that format is that somebody who was not there
can disagree with it.

If you run this and the count does not fall, or it lands on the wrong device, or the
rotation stitching links two phones that were never the same phone, I want to hear about
it. A tracking method that only works for the person who built it is not a finding.

---

### Sources

- Becker, J. K., Li, D., Starobinski, D. "Tracking Anonymized Bluetooth Devices."
  *Proceedings on Privacy Enhancing Technologies* 2019(3):50-65.
  doi:10.2478/popets-2019-0036
- Martin, J. et al. "Handoff All Your Privacy: A Review of Apple's Bluetooth Low Energy
  Continuity Protocol." *PoPETs* 2019(4).
- Celosia, G., Cunche, M. "Discontinued Privacy: Personal Data Leaks in Apple
  Bluetooth-Low-Energy Continuity Protocols." *PoPETs* 2020(1):26-46.
- Gagnon, G., Gambs, S., Cunche, M. "RSSI-based attacks for identification of BLE devices."
  *Computers & Security*, 2024. doi:10.1016/j.cose.2024.104080
- Bluetooth SIG. "Enhancing device privacy and energy efficiency with Bluetooth Randomized
  RPA Updates."
