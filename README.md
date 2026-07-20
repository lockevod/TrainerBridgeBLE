# TrainerBridge BLE

Corrects the power your smart trainer (or bike) reports and makes the **corrected** value available to your
apps and head units. It sits between the trainer and everything else, applies a linear calibration to
power, and passes everything else through untouched.

It runs the same on a **phone** and on a **Hammerhead Karoo** — the difference is only in how you use it,
described below.

---

## ⚠️ About the permission prompts (the app does NOT track you)

> TrainerBridge needs to **scan for your trainer over Bluetooth** to connect to it. There is **no GPS, no
> tracking, and no data ever leaves the device** — it looks for exactly one thing, your trainer.
>
> - **On the Karoo (Android 12) and modern phones:** the app asks for the **"Nearby devices"** permission
>   (Bluetooth). Android may file this under a location-related category, but it is **not** location — the app
>   declares `neverForLocation` and holds **no** location permission at all.
> - **On older phones (Android 11 and earlier) only:** Android itself refuses to let *any* app scan for
>   Bluetooth without the **Location** permission (a BLE scan could in theory reveal position from nearby
>   beacons). So on those phones the prompt says "Location" — again, only to scan for the trainer.
>
> Either prompt is safe to grant. The app requests nothing beyond Bluetooth (+ a foreground service so it
> keeps running with the screen off during a ride).

---

## What it does

The app connects to your trainer over Bluetooth (as the "receiver") and can then make the corrected data
available three ways:

1. **To training apps over Bluetooth** (e.g. Bestcycling): it mirrors the trainer 1:1 — same name, same
   controls (ERG / resistance, buttons) — but reports corrected power. The app behaves exactly as if
   talking to the trainer directly, and records corrected watts.
2. **To a head unit over ANT+** (e.g. a Garmin): it broadcasts an ANT+ smart trainer with corrected power.
3. **To a Karoo, natively**: it publishes a **virtual sensor** called *TrainerBridge* that the Karoo pairs
   like any other sensor and records for indoor training — no ANT, no cables, no second device.

---

## The three controls

The Monitor screen has three independent switches — this is the key to understanding the app:

| Control | What it does |
|---|---|
| **App active** (master) | The on/off of the whole app. **Off = nothing runs, zero battery.** Turn it on to use the app. |
| **Trainer enabled** | Connect to your trainer (the "receiving" side). On by default. While on, the app is connected and the tiles show live power/cadence/speed/resistance — **you do not need to press Broadcast for this**. |
| **Broadcast** (the big button) | Start/stop **emitting to the outside**: the Bluetooth mirror for training apps + the ANT+ output. |

**Receiving is always available** (whenever *App active* + *Trainer enabled* are on). **Broadcast is only
for sending to external apps / ANT+.** So the Karoo virtual sensor works without ever pressing Broadcast.

---

## Using it on a Karoo (indoor training)

This is where it's most useful: **indoor training on the Karoo**. Indoors you have no GPS and rely on the
trainer for power, speed and cadence — so recording the *corrected* power matters. The Karoo runs the app
**and** records the ride from it, all on the one device, with no ANT dongle.

1. Install the app and open it. Turn **App active** on. Make sure **Trainer enabled** is on.
2. The app connects to your trainer; the Monitor tiles show corrected power/cadence/speed.
3. On the Karoo, go to **Sensors → Add sensor** and pair **"TrainerBridge"**. The Karoo now records the
   **corrected** power, cadence and speed for your indoor ride.
4. That's it — you don't need to press Broadcast. Turn **App active** off when you're done so it stops
   consuming battery.

> The Karoo shows speed in km/h or mph according to your profile automatically.
>
> Note: Karoo extensions auto-start, so the app is always loaded on the Karoo — the **App active** switch
> is how you make sure it isn't consuming anything when you're not using it.

---

## Using it on a phone

The phone acts as the bridge between the trainer and your training app (and, optionally, a Garmin).

1. Install and open the app. Turn **App active** on; keep **Trainer enabled** on — it connects to the
   trainer and the tiles show corrected power.
2. Press **Broadcast**. The phone now advertises the mirrored trainer over Bluetooth. In your training app
   (e.g. Bestcycling) pair the trainer as usual — it works exactly as before, but records corrected power,
   and you can control it (ERG / resistance) normally.
3. **For a Garmin over ANT+** (optional): plug in an ANT USB dongle (with the ANT Radio Service installed),
   enable **ANT+ output** in Config, and pair the trainer on the Garmin. Give the phone and a Karoo
   different ANT+ ids (in Config) if you run both, so they don't collide.
4. Keep the phone exempt from battery optimisation (the app asks once) so it keeps running screen-off.

---

## Configuration

Open **Configuración** from the Monitor:

- **Correction** — `corrected = raw × (1 + scale%/100) + offset`. Enter the scale (%) and offset (W) from
  your calibration.
- **ERG floor (W, 0 = off)** — below this ERG target the inverse correction over-corrects downward, so the
  command is held at the floor's raw target instead. Targets within the offset (≈ a stop) still command 0.
  Default 50 W.
- **Trainer (BLE)** — scan and pick your trainer to pair it.
- **Advertised name** — the name apps see (defaults to match the trainer so it's recognised).
- **Options** — save diagnostic log (CSV), simulation mode (a fake trainer for testing with no hardware),
  ANT+ output + its device id.

---

## Notes & limitations

- **Simulation mode** feeds a synthetic trainer through the whole pipeline (UI, correction, mirror, ANT+,
  virtual sensor) with no hardware — useful for testing.
- **A Garmin watch over Bluetooth**: watches discover sensors by service UUID (which the mirror
  advertises), but Android can't set the advertising *Appearance* and uses a rotating random address —
  either can stop a picky watch from listing the Bluetooth mirror. The reliable path to a Garmin is the
  **ANT+** output; on a Karoo, the native **virtual sensor**.
- The UI is bilingual (English / Spanish), following the device language.
