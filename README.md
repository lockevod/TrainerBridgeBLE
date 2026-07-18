# TrainerBridge BLE

A transparent Bluetooth Low Energy proxy for a Zycle smart trainer. The app sits **between the trainer
and your training apps**: it connects to the trainer as a BLE *central*, mirrors the trainer's entire GATT
as a BLE *peripheral*, and re-broadcasts everything **byte-for-byte** — changing only two things:

1. the **advertised name** (so an app that keys capabilities off the name still recognises it), and
2. the **power** value, which is corrected with a linear calibration.

Everything else — control writes (ERG / resistance), the proprietary button/telemetry channels, speed,
cadence, resistance — passes through untouched, so the training app behaves exactly as if it were talking
to the trainer directly, but records **corrected** power. Optionally it also re-broadcasts the corrected
power over **ANT+** (FE-C smart trainer) for a head unit that pairs sensors over ANT+.

## How it works

```
        BLE central                         BLE peripheral (mirror)
Zycle ────────────────►  TrainerBridge BLE  ────────────────►  Training app (e.g. Bestcycling)
  ▲   notifications          │  corrects power                     │
  └──────────────────────────┘  relays control writes back         │
        control (ERG/resistance)                                   │
                                   │  ANT+ FE-C (optional)          ▼
                                   └──────────────────────────►  Head unit (Garmin, …)
```

- **Central**: scans for the trainer (by paired address, else by name prefix), connects, discovers the
  full GATT, subscribes to every notify/indicate characteristic and reads every readable one.
- **Peripheral (mirror)**: rebuilds an identical GATT server, re-advertises the trainer's own service
  UUIDs + manufacturer data, corrects the power field in Indoor Bike Data (0x2AD2) and Cycling Power
  (0x2A63), and inverse-corrects ERG target-power writes so the delivered power matches what the app asked.
- **ANT+ output (optional)**: a raw ANT+ FE-C master broadcasting the corrected power + cadence + speed.

### Power correction

```
corrected = raw × scale + offset        (raw 0 → 0, no phantom watts when coasting)
ergTarget = (target − offset) / scale   (inverse, so a commanded ERG target lands correct after correction)
```

`scale` is entered as a percentage adjustment (e.g. +6 → ×1.06) and `offset` in watts, both in the config.

## Two deployment cases

The same APK runs on a **phone** and on a **Hammerhead Karoo**; the only difference is where ANT+ comes from.

### Phone (e.g. Samsung Galaxy)

- **BLE proxy**: works out of the box — the phone is BLE central to the trainer and BLE peripheral to the app.
- **ANT+ output**: needs an **ANT USB dongle** (OTG) plus the *ANT Radio Service* / *ANT USB Service* apps
  installed. Without a dongle the BLE proxy still works; the ANT output just reports "sin canal ANT".
- Keep the app exempt from battery optimisation (it asks once) so it keeps running with the screen off.

### Karoo (Karoo 2 / Karoo 3)

- **BLE proxy**: works — the Karoo supports BLE peripheral advertising.
- **ANT+ output**: uses the Karoo's **native ANT radio — no dongle**. The ANT channels are shared with the
  Karoo's own sensor system, so:
  - If all channels are busy the app **waits** for one to free (it listens for the channel-available
    broadcast and grabs one the instant it frees) instead of fighting the shared service.
  - If ANT gets stuck after many app restarts, **reboot the Karoo** once to clear any leaked channels.
- Install via adb: `adb install -r app-debug.apk`, launch `com.enderthor.trainerbridgeble/.MonitorActivity`.

### ANT+ device id — set a different one per device

Both builds default to ANT+ device number **44252 (0xACDC)**. If you run the phone and the Karoo at the
same time, give each a **different ANT+ id** in the config (e.g. Karoo 44253) so a head unit lists them as
two separate trainers instead of colliding.

## Screens

**Monitor** — Start/Stop, a status banner that states the trainer link plainly (`Buscando trainer…` /
`Trainer conectado ✓` / `Trainer conectado · sin datos`), a separate alert line for ANT/BLE problems, and
live tiles: power, corrected power, speed, cadence, resistance, and the last app→trainer control command.
Resistance ▲/▼ buttons emulate the trainer's buttons (send a Set Target Resistance to the trainer).

**Config** — pair the trainer over BLE (scan + pick), the correction (scale % / offset W), the advertised
name, and toggles: save log (CSV), simulation mode (a synthetic trainer, no hardware), ANT+ output + its
device id.

## Building

Requires **JDK 17** (Gradle itself must run on 17):

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The raw ANT channel API comes from `app/libs/android_antlib_4-16-0.aar` (TX-only; the trainer itself is
read over BLE).

## Diagnostics

Enable the log toggle in Config to write `trainerbridgeble.csv` to the app's external files dir:

```bash
adb pull /sdcard/Android/data/com.enderthor.trainerbridgeble/files/trainerbridgeble.csv
```

It records the advertising blueprint, GATT profile, notifications/relays (power corrected), control writes,
ANT open/transmit state, and reconnection events.

## Notes & limitations

- **Simulation mode** feeds a synthetic trainer through the whole pipeline so the UI, correction, mirror and
  ANT output can be exercised with no hardware.
- **BLE to a Garmin watch**: a Fenix discovers sensors by service UUID (which the mirror advertises), but
  Android cannot set the advertising *Appearance* and uses a rotating random address — either can stop a
  picky watch from listing the BLE mirror. The reliable path to a Garmin is the **ANT+ FE-C** output.
- The app is currently Spanish-only in the UI.
