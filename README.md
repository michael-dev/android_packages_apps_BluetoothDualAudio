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
│  Quick Settings tile          │BROADCAST│  SET_PEER_VOLUME receiver     │
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

The fork also ships a companion product makefile
(`packages/modules/Bluetooth/dual-a2dp.mk`) that seeds the
`persist.bluetooth.a2dp.dup_active=true` sysprop — the native-side
arming flag (see [Sysprops](#sysprops)). Setting the default there
means the prop is `true` iff the patched APEX is actually installed,
which is the exact semantic `isNativeSideArmed()` checks for.

## Device integration

Wire it in from your device tree:

```makefile
# device.mk
PRODUCT_PACKAGES += BluetoothDualAudio
$(call inherit-product, packages/modules/Bluetooth/dual-a2dp.mk)
```

```makefile
# BoardConfig.mk
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += packages/apps/BluetoothDualAudio/sepolicy
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
work. Originally developed on Samsung Tab A8 (UWE5622 controller).

## Settings.Global keys

| Key | Type | Meaning |
|---|---|---|
| `a2dp_dup_active` | int 0 / 1 / -1 | Master switch (tri-state: 1=on, 0=off, -1=unset→sysprop fallback) |
| `a2dp_dup_members` | string CSV | Uppercase MAC list of peers explicitly included as secondaries. Null = "promote all". Empty "" = "promote none". |
| `a2dp_dup_coerce_codec` | int 0 / 1 | When 1: reconfigure all mismatched peers to SBC on enable; restore on disable. |
| `a2dp_dup_peer_volumes` | string CSV | Published by the coordinator: `MAC:volume,...`, volume 0..15. Read by the app for slider initial position. |

## Sysprops

| Prop | Type | Meaning |
|---|---|---|
| `persist.bluetooth.a2dp.dup_active` | bool | Native-side arming flag. Fluoride's dual-writer in the Bluetooth APEX gates on this directly from the stream thread; `Settings.Global.a2dp_dup_active` is the UI-facing master switch, this is the platform capability gate. Default seeded to `true` by the Bluetooth fork's `dual-a2dp.mk`, since the fork's presence is exactly what the flag is claiming. Persisted across reboots; can be toggled via `adb shell setprop` (see SELinux section). |

## SELinux

The `sepolicy/` directory declares `bluetooth_dup_active_prop` (via
`system_public_prop`) and labels the sysprop above with it. Without the
dedicated label the prop falls under the upstream `persist.bluetooth.`
→ `bluetooth_prop` prefix rule, which `platform_app` cannot read — the
settings UI would then always display "native side disarmed" even when
the stack is armed and audio is duplicating. Using a purpose-named
public type fixes the UI without broadening access to the full
`bluetooth_prop` bucket.

`system_public_prop` only exempts the type from the cross-partition
neverallow; read access is granted explicitly with `get_prop` for the
two consumers (`bluetooth` — native coordinator; `platform_app` — the
UI). The `shell` domain also gets `set_prop` so the escape-hatch
instruction shown by the disarmed warning (`setprop
persist.bluetooth.a2dp.dup_active true`) actually works from adb on
the rare case the default from `dual-a2dp.mk` isn't present.

Wire in via `SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS` as shown in
[Device integration](#device-integration). Keep it co-located with the
app so any device that picks up the app picks up the label too.

## Broadcast actions

| Action | Extras | Purpose |
|---|---|---|
| `org.lineageos.dualaudio.SET_PEER_VOLUME` | `mac` (string), `volume` (int 0..15) | From app → coordinator: push AVRCP absolute volume to a specific peer |
| `org.lineageos.dualaudio.DUMP_STATE` | — | Log a coordinator state snapshot at Info level. `adb shell am broadcast -a org.lineageos.dualaudio.DUMP_STATE` |
| `org.lineageos.dualaudio.SET_CODEC` | `mac`, `codec` (SBC\|AAC\|…) | Developer helper: set stored codec preference (only renegotiates when the device is active) |
| `org.lineageos.dualaudio.SET_ACTIVE` | `mac` | Developer helper: make a specific peer the A2DP active device |

## Related docs

Architecture, security model, and upstreaming notes for the
Bluetooth-APEX side live in the fork:

- [DUAL_A2DP.md](https://github.com/HamelinPorts/android_packages_modules_Bluetooth/blob/dual-a2dp/docs/DUAL_A2DP.md) — overall architecture + patch surface
- [REBASE.md](https://github.com/HamelinPorts/android_packages_modules_Bluetooth/blob/dual-a2dp/docs/REBASE.md) — keeping the fork in sync with upstream LineageOS
- [UPSTREAM.md](https://github.com/HamelinPorts/android_packages_modules_Bluetooth/blob/dual-a2dp/docs/UPSTREAM.md) — notes for submitting to AOSP Gerrit

## License

Apache-2.0.
