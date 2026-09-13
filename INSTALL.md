# Installing SigEye on your phone

Two parts. **Part 1 gets the app running** and needs nothing on your PC. **Part 2 installs
`adb`** so you can pull the CSV and read logs — do it once, it takes five minutes.

---

## Part 1 — Install the app (phone only, no PC needed)

### 1. Let your phone install apps from Chrome

Settings → Apps → Special app access → **Install unknown apps** → Chrome → **Allow from this
source**.

(On Samsung it's Settings → Apps → ⋮ → Special access → Install unknown apps.)

### 2. Download the APK on the phone

Open this in the phone's browser:

**https://github.com/Reconnaishawnce/SigEye/releases/latest**

Tap **SigEye.apk** under Assets. It downloads directly — no login, no zip.

> Don't use the Actions tab for this. Actions artifacts are always zipped and need an
> authenticated desktop browser session, so on a phone they show up but refuse to download.
> The release asset exists to avoid exactly that.

### 3. Install

Open the phone's **Files** app → Downloads → tap `SigEye.apk` → **Install**. Play Protect will warn that it doesn't recognize the app; tap
**Install anyway**.

### 4. First run

Open SigEye. You land on the experiment list — tap **Train Spotter**. It then asks for
three permissions in one prompt:

- **Nearby devices** → Allow
- **Location** → choose **While using the app** (this is enough; the foreground service
  keeps the grant alive with the screen off)
- **Notifications** → Allow

Then tap **Start scanning**. Within a few seconds the big number should start moving.

Burst alerts stay disarmed for the first two minutes while the baseline builds — the screen
tells you how many bins are left.

### 5. Exempt it from battery optimisation

This is the difference between logging all night and logging for 20 minutes.

Settings → Apps → SigEye → **Battery** → **Unrestricted**.

On Samsung also: Settings → Battery → Background usage limits → make sure SigEye is
**not** in "Sleeping apps" or "Deep sleeping apps".

---

## Updating later

Once the signing key is in place, updates install straight over the top and keep your
settings and CSV logs.

**Easiest: use Obtainium.** It watches the GitHub releases page, notifies you when a new
SigEye build lands, and installs it in two taps.

Install it straight from GitHub - no F-Droid needed:

    https://github.com/ImranR98/Obtainium/releases/latest

Take `app-arm64-v8a-release.apk` (24 MB, right for any modern phone). If that will not
install, `app-release.apk` is universal but 67 MB. Avoid anything with `-fdroid-` in the
name: those builds have Obtainium's own self-update disabled, because F-Droid would
normally handle it.

Then in Obtainium: **Add App**, paste the SigEye repo URL, and turn on background update
checks.

    https://github.com/Reconnaishawnce/SigEye

**Or by hand:** open https://github.com/Reconnaishawnce/SigEye/releases/latest on the
phone, tap SigEye.apk, and install over the existing app.

**Or over adb**, if the phone is plugged in:

```powershell
cd C:\platform-tools
./adb.exe install -r SigEye.apk
```

> If an update refuses to install with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the build was
> signed with a different key than the copy on your phone. That happens for any build made
> before the signing key was set up, and for builds from a fork. Uninstall first - and pull
> your CSV logs before you do.

## Part 2 — Install adb on your PC (optional but worth it)

You have no Android tooling installed. This is the 10 MB version, not the 1.5 GB one.

### 1. Download platform-tools

https://dl.google.com/android/repository/platform-tools-latest-windows.zip

Unzip it to `C:\platform-tools`. That's it — no installer.

### 2. Enable USB debugging on the phone

Settings → About phone → tap **Build number** seven times → back out to Settings →
System → **Developer options** → turn on **USB debugging**.

### 3. Plug the phone in and authorise

Open PowerShell and run:

```powershell
C:\platform-tools\adb.exe devices
```

The phone shows an "Allow USB debugging?" dialog. Tick **Always allow** and accept.
Run it again — you should see your device with `device` next to it, not `unauthorized`.

---

## Verifying it actually works

Each of these is one command. Run them from PowerShell.

### Is the service scanning?

```powershell
C:\platform-tools\adb.exe logcat -s SigEye
```

You should see a line every 5 seconds:

```
bin new=2 active=14 baseline=1.0 spike=false
```

`new` is how many previously-unseen addresses that bin, `active` is how many distinct
addresses are currently inside the 10-minute window, `baseline` is the rolling median.
Press Ctrl+C to stop watching.

### Does it survive the screen going off?

Start scanning, lock the phone, wait five minutes, unlock. The chart should have five
minutes of continuous data with no flat gap.

### Pull the CSV

```powershell
C:\platform-tools\adb.exe pull /sdcard/Android/data/com.sigeye/files/ .\sigeye-logs
```

### Force a test alert without waiting for a train

Set **Burst threshold** to 1.5x and **Minimum burst size** to 2 in Settings, then walk
past a few Bluetooth devices — or just wait for the bus. Set them back afterwards.

---

## Tuning it for your window

Run it for one evening at defaults with the phone where you'll keep it, and press
**Train now** every time you actually hear a train. Then pull the CSV and compare the
`spike` column against the `label` column:

- **Trains you heard but got no spike** → lower Burst threshold toward 2.0x, or raise the
  Signal floor toward -70 dBm so distant background traffic stops padding the baseline.
- **Spikes with no train** → raise Burst threshold toward 4-5x, or raise Minimum burst size.

The phone's position matters more than any setting. A phone on the windowsill facing the
tracks will see a burst four to five times the size of one in the next room.

---

## If something is wrong

| Symptom | Cause | Fix |
|---|---|---|
| Big number stays at 0 | Location permission denied | Settings → Apps → SigEye → Permissions → Location → Allow |
| "Bluetooth is off" | Bluetooth off | Turn it on; the app resumes by itself |
| Data stops after ~30 min | OEM battery killer | Set Battery to **Unrestricted** (Part 1, step 5) |
| Counts drop to near zero after a while | Android scan throttling | The watchdog restarts automatically; the screen shows how many times |
| `adb: device unauthorized` | USB prompt not accepted | Unplug, replug, accept the dialog on the phone |
| Play Protect blocks the install | Unsigned debug build | Tap **More details** → **Install anyway** |
