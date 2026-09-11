# Installing BLEPulse on your phone

Two parts. **Part 1 gets the app running** and needs nothing on your PC. **Part 2 installs
`adb`** so you can pull the CSV and read logs — do it once, it takes five minutes.

---

## Part 1 — Install the app (phone only, no PC needed)

### 1. Let your phone install apps from Chrome

Settings → Apps → Special app access → **Install unknown apps** → Chrome → **Allow from this
source**.

(On Samsung it's Settings → Apps → ⋮ → Special access → Install unknown apps.)

### 2. Download the APK on the phone

Open this on the **phone's** browser and sign in to GitHub:

https://github.com/segalreport/BLEPulse/actions

Tap the newest run with a green check → scroll to **Artifacts** → tap **BLEPulse-apk**.
It downloads a `.zip`.

### 3. Unzip and install

Open the phone's **Files** app → Downloads → tap `BLEPulse-apk.zip` → extract → tap the
`.apk` inside → **Install**. Play Protect will warn that it doesn't recognise the app; tap
**Install anyway**.

### 4. First run

Open BLEPulse. It asks for three permissions in one prompt:

- **Nearby devices** → Allow
- **Location** → choose **While using the app** (this is enough; the foreground service
  keeps the grant alive with the screen off)
- **Notifications** → Allow

Then tap **Start scanning**. Within a few seconds the big number should start moving.

### 5. Exempt it from battery optimisation

This is the difference between logging all night and logging for 20 minutes.

Settings → Apps → BLEPulse → **Battery** → **Unrestricted**.

On Samsung also: Settings → Battery → Background usage limits → make sure BLEPulse is
**not** in "Sleeping apps" or "Deep sleeping apps".

---

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
C:\platform-tools\adb.exe logcat -s BLEPulse
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
C:\platform-tools\adb.exe pull /sdcard/Android/data/com.blepulse/files/ .\blepulse-logs
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
| Big number stays at 0 | Location permission denied | Settings → Apps → BLEPulse → Permissions → Location → Allow |
| "Bluetooth is off" | Bluetooth off | Turn it on; the app resumes by itself |
| Data stops after ~30 min | OEM battery killer | Set Battery to **Unrestricted** (Part 1, step 5) |
| Counts drop to near zero after a while | Android scan throttling | The watchdog restarts automatically; the screen shows how many times |
| `adb: device unauthorized` | USB prompt not accepted | Unplug, replug, accept the dialog on the phone |
| Play Protect blocks the install | Unsigned debug build | Tap **More details** → **Install anyway** |
