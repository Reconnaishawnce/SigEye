# Surveillance hardware signatures

What SigEye can recognize on the air, where each signature came from, and what it does not
prove. Everything here is implemented in
`core/analysis/surveillance/Surveillance.kt` and tested against captured bytes in
`SurveillanceTest.kt`.

The rule this file follows: a device that broadcasts a structure nothing else broadcasts
has identified itself. An IEEE block registered to the company that sells the product is a
real claim about who built the hardware. A network name somebody typed is a reason to go
and look. A MAC prefix belonging to whoever made the radio module inside the product is
none of those things, and is not used.

## Flock Safety Falcon

Falcon is a pole-mounted automatic license plate reader. The camera reaches Flock over
cellular and is close to silent on the bands a phone can hear. The external battery bolted
to the same pole is not: it reports its health to the camera over BLE, which makes the
battery the thing that gives the installation away.

Signatures, strongest first.

| What | Evidence | Certainty |
|---|---|---|
| Manufacturer data, company `0x09C8` (XUNTONG), payload opening with the device's own address, then a printable serial | structural, three independent facts | Confirmed |
| BLE name `FS Ext Battery` | Flock Safety, spelled out | Confirmed |
| BLE name `Penguin-` plus ten digits | pre-March-2025 firmware | Confirmed |
| BLE name of ten bare digits **and** company `0x09C8` | post-March-2025 firmware | Likely |
| Company `0x09C8` alone | shared with tyre pressure sensors | Possible |
| Wi-Fi SSID `Flock-XXXXXX` where the hex is part of the BSSID | installer access point | Confirmed |
| Wi-Fi SSID `Flock-XXXXXX` with no corroboration | Flock's naming scheme | Likely |

### The part worth writing about

**The battery randomizes its Bluetooth address and broadcasts its serial number in the
clear.** The advertising address is random, so the ordinary way of recognizing fixed
hardware — a manufacturer prefix on a stable MAC — finds nothing. Meanwhile the payload
carries the serial printed on the unit, in plain ASCII, unchanged across every rotation.

That serial identifies one physical battery on one physical pole indefinitely. Careful
about the MAC and careless about the payload is precisely the failure mode the rest of this
app documents in consumer phones, committed here by a company that sells surveillance.

The structural check that makes this trustworthy rather than a guess about a company
identifier: the payload opens by repeating the device's own advertising address. A tyre
sensor sharing the XUNTONG number does not do that.

### Source

ryanohoro, "Spotting Flock Safety's Falcon Cameras", 11 November 2024, updated 20 March
2025. <https://www.ryanohoro.com/post/spotting-flock-safety-s-falcon-cameras>

The article decodes QR labels from units sold secondhand, publishes a packet capture of the
battery advertisement, and notes the March 2025 firmware change that removed the word
`Penguin` from the BLE name. The captured scan response
`1dffc809d8a0d89f4a5e2030502a544e3732303233303232303030373731` from
`d8:a0:d8:9f:4a:5e` is used verbatim as a test fixture, so these tests fail if the matcher
stops recognizing a real battery.

Falcon V2 hardware, per the same article: Lantronix Open-Q 624A SOM, Android 8.1, Qualcomm
Snapdragon 624, Sierra Wireless RC7611 LTE modem (FCC ID N7NRC76B), LiteOn 802.11
a/b/g/n/ac + BT 4.2 LE (FCC ID WCBN3510A). The LiteOn prefix is deliberately **not** used
as a signature — that module is inside an enormous number of ordinary devices.

## Axon Enterprise

One IEEE block, `00:25:DF`, registered originally as TASER International. It covers body
cameras, docks, TASERs and fleet gear. SigEye now checks it on **both** radios; before, it
was a Wi-Fi check only, so an Axon device advertising over Bluetooth from a fixed address
went unremarked.

### Why a quiet result means very little

At DEF CON 31 in August 2023, Alan "Nullagent" Meekins and Roger "RekcahDam" Hicks
demonstrated locating police equipment and inferring its activity from Bluetooth alone,
using the Axon OUI they found in Axon's own documentation. Body cameras emit when they
start recording; some holsters emit when a sidearm is drawn.

In October 2023 an Axon spokesperson told Engadget the company was working on "rotation of
unique BLE device addresses (known as MAC addresses) that can specifically identify our
devices, and removing the need for including serial numbers in Bluetooth broadcasts to
reduce the ability to track a specific device over time."

So OUI matching now finds only hardware old enough, or un-updated enough, to still use a
fixed address. Hearing nothing is not evidence that nothing is there, and the app says so
next to every Axon hit rather than leaving that caveat in a file like this one.

Source: Katie Malone, "How hackers are using Bluetooth to track police activity", Engadget,
9 October 2023.
<https://www.engadget.com/how-hackers-are-using-bluetooth-to-track-police-activity-140012717.html>

### The symmetry worth noting

Axon's response to being detectable was to rotate addresses and drop serials from
broadcasts — the same two mitigations this project argues phone vendors have not finished
implementing properly. Flock did half of it: rotating addresses, serial still in the clear.
Two surveillance vendors, the same decision, opposite outcomes, and a phone can measure the
difference. That contrast belongs in the blog post.

## What is deliberately absent

- **Component-vendor MAC prefixes.** Liteon, Silicon Labs, Espressif, Universal Global
  Scientific. Their parts sit inside laptops, televisions and smart plugs. Matching on them
  fires constantly, and a warning that cries wolf teaches people to ignore the one that
  matters. Tools that use these prefixes also fingerprint probe requests, which needs
  monitor mode, a chipset, a driver and root. A phone cannot do that half.
- **Cellular signatures.** The Sierra Wireless modem in a Falcon is the loudest radio on the
  pole and a phone cannot see it at all.
- **Anything unverified.** No signature goes in this file without a capture or a registry
  entry behind it.
