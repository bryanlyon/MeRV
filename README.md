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

Menu ▸ **Copy debug log** puts a record of what the app and panel said on the
clipboard, so you can paste it into a bug report.

**Forget this RV** clears the saved panel. Also remove it in Android's Bluetooth
settings, then press CONNECT again to re-pair.

## Credits

MeRV exists because of the reverse-engineering work of **Pete Hurth**
([@phurth](https://github.com/phurth)). He worked out how the Lippert OneControl
Bluetooth gateway authenticates and talks, and published it as open source under
the MIT License. MeRV is a small Android front end built on that work.

### [phurth/ha-onecontrol](https://github.com/phurth/ha-onecontrol)

Home Assistant integration for Lippert/LCI OneControl BLE gateways.
MIT License, Copyright (c) 2026 Pete Hurth. Ported from commit
[`0235555`](https://github.com/phurth/ha-onecontrol/commit/0235555).

Used in MeRV (`app/src/main/java/net/rvremote/protocol/`):
- Gateway unlock math, including the masked key-schedule constants (`Tea.kt`,
  from `protocol/tea.py`)
- CRC8 checksum and COBS frame encoding/decoding (`Framing.kt`, from
  `protocol/crc8.py` and `protocol/cobs.py`)
- Command formats for device lists, names, switches, dimmers and the awning
  motor (`Commands.kt`, from `protocol/commands.py`)
- Event formats for battery, relays, dimmers, tanks, motors and device names
  (`Events.kt`, from `protocol/events.py`)
- The table of 445 device names (`FunctionNames.kt`, generated from
  `protocol/function_names.py`)
- Protocol behavior documented in its
  [technical spec](https://github.com/phurth/ha-onecontrol/blob/main/docs/TECH_SPEC.md):
  press-and-hold awning control, the 200 ms repeat, keep-alive timing, and
  asking for device names only after the device list completes

### [phurth/ble-plugin-bridge](https://github.com/phurth/ble-plugin-bridge)

Android app bridging RV Bluetooth devices to Home Assistant over MQTT.
MIT License, Copyright (c) 2025 phurth. Referenced at commit
[`abb1aef`](https://github.com/phurth/ble-plugin-bridge/commit/abb1aef).

Used in MeRV (`app/src/main/java/net/rvremote/Gateway.kt`):
- The Android connection sequence for OneControl gateways: service discovery,
  MTU request, reading the unlock challenge, writing the key without response,
  confirming "Unlocked", then enabling notifications
- Push-button pairing detection from the gateway's advertisement
- Writing all commands without response

Both licenses are reproduced in full in [LICENSE](LICENSE) and inside the app
under ⋮ ▸ About & credits.

If you're using OneControl with Home Assistant, use
[ha-onecontrol](https://github.com/phurth/ha-onecontrol) directly; it covers far
more hardware than this app.
