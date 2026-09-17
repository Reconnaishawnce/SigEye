# Notice

SigEye is licensed under the GNU General Public License v3.0. See `LICENSE`.

## Why GPL-3.0

SigEye is an instrument. Anyone asked to believe a measurement it produced should be able
to read how it was produced, and anyone who improves it should have to pass that on. The
license is chosen to match what the app is for rather than as a default.

It is also what lets this project learn from its neighbors. GPL-3.0 code cannot be taken
into a differently licensed project, and being under the same license means work can move
in both directions.

## Third-party work this project draws on

**Spectre** by thomasbuilds, GPL-3.0, <https://github.com/thomasbuilds/Spectre>.

An Android wireless observation app with far broader radio coverage than SigEye had.
Reviewed 2026-09-17 and used as the reference for capabilities SigEye was missing
entirely. Where an implementation here follows theirs closely the file says so in its own
header, with what was taken and what was changed.

Taken as design, rewritten here: 802.11mc round-trip ranging, GNSS constellation
observation, combined received-power accounting across radios, and local network
discovery over mDNS and SSDP.
