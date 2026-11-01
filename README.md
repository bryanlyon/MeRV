# MeRV

A small Android remote for Lippert OneControl / WeRV push-button gateways (the panel
with a **CONNECT** button). No account, no cloud, no VIN. It talks to the panel over
Bluetooth directly.

Shows: tank levels, battery voltage, lights, switched systems (fridge, water heater,
pump, tank heater…), and the awning (press-and-hold, like the panel).

## Build the APK

### Option A: GitHub builds it (no Android tools needed)

1. Create a new **private** repository on GitHub and push this folder to it:
   ```
   git init && git add . && git commit -m "RV Remote"
   git branch -M main
   git remote add origin git@github.com:YOURNAME/rv-remote.git
   git push -u origin main
   ```
2. On GitHub, open the repo's **Actions** tab. The "Build APK" run takes ~3 minutes.
3. Open the finished run, download **merv-apk** (a zip), and unzip it to get
   `app-debug.apk`.

If the build fails, copy the red error text from the failed step and send it back.

### Option B: build locally (Linux Mint or macOS)

Install Android Studio, open this folder, let it sync, then **Build ▸ Build APK(s)**.

## Install and pair

1. Copy `app-debug.apk` to the phone and open it. Android will ask you to allow
   installs from that source.
2. Open **RV Remote** and grant the Bluetooth permission.
3. Within Bluetooth range, press **CONNECT** on the panel. Accept the pairing prompt.
4. Within a few seconds the screen fills with your tanks, lights, and systems.

The phone remembers the RV after that; just open the app.

If you also keep using WeRV on another phone, the gateway may only allow one active
connection at a time. Close the other app if this one won't connect.

## Awning safety

The awning only moves while you hold the button, matching the panel. Releasing the
button, leaving the app, or losing the connection sends STOP. A single press-and-hold
is capped at 60 seconds. Watch the awning while it moves.

## When something doesn't work

Menu ▸ **Debug log** shows what the app and panel are saying. Menu ▸ **Copy log**
puts it on the clipboard so you can send it back for a fix.

**Forget this RV** clears the saved panel. Also remove it in Android's Bluetooth
settings, then press CONNECT again to re-pair.

## Credits

The protocol work (unlock, framing, commands, event formats) is ported from
Pete Hurth's MIT-licensed [ha-onecontrol](https://github.com/phurth/ha-onecontrol)
and [ble-plugin-bridge](https://github.com/phurth/ble-plugin-bridge). See LICENSE.
