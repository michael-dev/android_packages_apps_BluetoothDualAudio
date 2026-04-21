# BluetoothDualAudio

User-facing controller for simultaneous A2DP playback to multiple
Bluetooth sinks on LineageOS / AOSP.

## What it does

- Master switch to enable / disable dual-audio mode.
- Per-device include list (which non-primary peers to promote as
  secondaries).
- Per-peer codec display (AAC 44.1k 320kbps, SBC 44.1k, etc.).
- Per-peer volume slider driven by AVRCP.
- Optional codec-coercion mode: when the active set has mismatched
  codecs, push everyone to SBC so stock `bta_av_dup_audio_buf` works.

## How it fits together

This app writes to a handful of `Settings.Global` keys and broadcasts
a couple of `org.lineageos.dualaudio.*` intents. The real work happens
in the Bluetooth APEX, in an overlay called `DualAudioCoordinator`
(branch `dual-a2dp-phase2` of `packages/modules/Bluetooth`). Without
that overlay, this app's toggles do nothing — the app is the UI, the
overlay is the engine.

```
┌───────────────────────────────┐         ┌───────────────────────────────┐
│ BluetoothDualAudio (this app) │         │   Bluetooth APEX overlay      │
│                               │         │  (DualAudioCoordinator)       │
│  Settings Activity            │  WRITE  │                               │
│  ──────────────────────       ├────────▶│  ContentObserver on           │
│  Master switch                │         │    a2dp_dup_active            │
│  Per-device toggles           │         │    a2dp_dup_members           │
│  Volume sliders               │         │    a2dp_dup_coerce_codec      │
│                               │         │                               │
│  Quick Settings tile          │ BROADCAST│  SET_PEER_VOLUME receiver     │
│                               ├────────▶│  SET_CODEC / SET_ACTIVE       │
│                               │         │    (dev helpers)              │
│                               │         │  DUMP_STATE receiver          │
│                               │         │                               │
│                               │  READ   │  writes a2dp_dup_peer_volumes │
│                               │◀────────┤  to Settings.Global           │
└───────────────────────────────┘         └───────────────────────────────┘
```

## Dependencies

The app by itself does **nothing useful**. It writes `Settings.Global`
keys and fires broadcasts that are only interpreted by the
`DualAudioCoordinator` class inside a patched Bluetooth APEX. Without
that patched APEX, flipping the master switch has no effect on audio
routing.

You need **both** of these:

**1. This app** (the UI) — this repo.

**2. A patched Bluetooth module** carrying the Phase-2 dual-audio
overlay. We maintain a fork at
[`HamelinPorts/android_packages_modules_Bluetooth`](https://github.com/HamelinPorts/android_packages_modules_Bluetooth),
branch `dual-a2dp`. It adds ~10 hook-point lines to AOSP-tracked
files plus ~5 new overlay files for the coordinator, per-peer Tx
registry, codec coercion, and AVRCP per-peer volume dispatch.

## Device integration

Three entries in your device tree wire it in:

```makefile
# device.mk
PRODUCT_PACKAGES += BluetoothDualAudio
```

```json
// lineage.dependencies
{
  "repository": "HamelinPorts/android_packages_apps_BluetoothDualAudio",
  "target_path": "packages/apps/BluetoothDualAudio"
},
{
  "repository": "HamelinPorts/android_packages_modules_Bluetooth",
  "target_path": "packages/modules/Bluetooth",
  "remote": "github",
  "revision": "dual-a2dp"
}
```

After that, `breakfast <device> && repo sync` pulls both repos into
your tree, and `m` builds the app + the patched Bluetooth APEX.

The app is platform-signed (needs `BLUETOOTH_PRIVILEGED`). It makes
no device-specific assumptions — any device that handles a minimum
of two simultaneous A2DP connections at the controller level should
work. Tested on SM-X205 (UWE5622 controller).

## Settings.Global keys

| Key | Type | Meaning |
|---|---|---|
| `a2dp_dup_active` | int 0 / 1 / -1 | Master switch (tri-state: 1=on, 0=off, -1=unset→sysprop fallback) |
| `a2dp_dup_members` | string CSV | Uppercase MAC list of peers explicitly included as secondaries. Null = "promote all". Empty "" = "promote none". |
| `a2dp_dup_coerce_codec` | int 0 / 1 | When 1: reconfigure all mismatched peers to SBC on enable; restore on disable. |
| `a2dp_dup_peer_volumes` | string CSV | Published by the coordinator: `MAC:volume,...`, volume 0..15. Read by the app for slider initial position. |

## Broadcast actions

| Action | Extras | Purpose |
|---|---|---|
| `org.lineageos.dualaudio.SET_PEER_VOLUME` | `mac` (string), `volume` (int 0..15) | From app → coordinator: push AVRCP absolute volume to a specific peer |
| `org.lineageos.dualaudio.DUMP_STATE` | — | Log a coordinator state snapshot at Info level. `adb shell am broadcast -a org.lineageos.dualaudio.DUMP_STATE` |
| `org.lineageos.dualaudio.SET_CODEC` | `mac`, `codec` (SBC\|AAC\|…) | Developer helper: set stored codec preference (only renegotiates when the device is active) |
| `org.lineageos.dualaudio.SET_ACTIVE` | `mac` | Developer helper: make a specific peer the A2DP active device |

## License

Apache-2.0.
