/*
 * SM-X205 dual-A2DP app — Bluetooth state reader.
 *
 * Queries which bonded devices support A2DP sink role, whether each is
 * currently connected, and whether each is currently playing audio
 * (via BluetoothA2dp.isA2dpPlaying — @SystemApi, available since the
 * app is platform-signed).
 */

package org.lineageos.dualaudio;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.content.Context;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BtStateProvider {
    private static final String TAG = "BtStateProvider";

    public static final class DeviceInfo {
        public final BluetoothDevice device;
        public final String name;
        public final String mac;
        public final boolean connected;
        public final boolean playing;
        public final boolean activePrimary;
        /** Negotiated A2DP codec name, or empty string when unknown/disconnected. */
        public final String codec;
        /**
         * Current system-level volume (0..15 typical) published by the
         * DualAudioCoordinator via Settings.Global.a2dp_dup_peer_volumes.
         * -1 when unknown.
         */
        public final int volume;

        DeviceInfo(BluetoothDevice d, String n, String m,
                   boolean connected, boolean playing, boolean active,
                   String codec, int volume) {
            this.device = d;
            this.name = n;
            this.mac = m;
            this.connected = connected;
            this.playing = playing;
            this.activePrimary = active;
            this.codec = codec;
            this.volume = volume;
        }
    }

    public interface Callback {
        /** Called on the main thread with the current device list. */
        void onDevicesUpdated(List<DeviceInfo> devices);
    }

    private final Context mCtx;
    private BluetoothA2dp mA2dp;
    private Callback mCallback;

    public BtStateProvider(Context ctx) {
        mCtx = ctx.getApplicationContext();
    }

    public void start(Callback cb) {
        mCallback = cb;
        BluetoothManager bm = mCtx.getSystemService(BluetoothManager.class);
        if (bm == null) {
            Log.w(TAG, "No BluetoothManager");
            cb.onDevicesUpdated(new ArrayList<>());
            return;
        }
        BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null) {
            Log.w(TAG, "No BluetoothAdapter");
            cb.onDevicesUpdated(new ArrayList<>());
            return;
        }
        adapter.getProfileProxy(mCtx, mProxyListener, BluetoothProfile.A2DP);
    }

    public void stop() {
        if (mA2dp != null) {
            BluetoothManager bm = mCtx.getSystemService(BluetoothManager.class);
            if (bm != null && bm.getAdapter() != null) {
                bm.getAdapter().closeProfileProxy(BluetoothProfile.A2DP, mA2dp);
            }
            mA2dp = null;
        }
        mCallback = null;
    }

    /** Force a refresh and deliver via callback. */
    public void refresh() {
        if (mA2dp == null || mCallback == null) return;
        mCallback.onDevicesUpdated(snapshot());
    }

    private final BluetoothProfile.ServiceListener mProxyListener =
            new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile != BluetoothProfile.A2DP) return;
                    mA2dp = (BluetoothA2dp) proxy;
                    if (mCallback != null) mCallback.onDevicesUpdated(snapshot());
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    if (profile == BluetoothProfile.A2DP) mA2dp = null;
                }
            };

    private List<DeviceInfo> snapshot() {
        List<DeviceInfo> out = new ArrayList<>();
        if (mA2dp == null) return out;

        BluetoothManager bm = mCtx.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) return out;

        Map<String, Integer> volumes = readPublishedVolumes();

        BluetoothDevice activeDevice = null;
        try {
            // getActiveDevices is @SystemApi.
            Method m = BluetoothA2dp.class.getMethod("getActiveDevice");
            activeDevice = (BluetoothDevice) m.invoke(mA2dp);
        } catch (Throwable ignored) {
        }

        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                int connState;
                try {
                    connState = mA2dp.getConnectionState(d);
                } catch (SecurityException se) {
                    connState = BluetoothProfile.STATE_DISCONNECTED;
                }
                // Filter: skip devices that the A2DP service doesn't know
                // about at all (not DISCONNECTED=0 / CONNECTING=1 /
                // CONNECTED=2 / DISCONNECTING=3). Anything else means the
                // profile doesn't apply. In practice getConnectionState()
                // returns DISCONNECTED for non-A2DP devices too — UUID
                // check removed because it depends on SDP cache freshness.
                if (connState < 0) continue;
                if (!isLikelyAudioDevice(d) && connState == BluetoothProfile.STATE_DISCONNECTED) {
                    // Hide clearly non-audio bonded peers (keyboards, mice,
                    // PCs) when they're not connected so the list stays
                    // focused. Connected ones are shown regardless so the
                    // user can see what's using A2DP.
                    continue;
                }

                boolean connected = connState == BluetoothProfile.STATE_CONNECTED;
                boolean playing = false;
                if (connected) {
                    try {
                        Method pm = BluetoothA2dp.class.getMethod("isA2dpPlaying", BluetoothDevice.class);
                        Object b = pm.invoke(mA2dp, d);
                        if (b instanceof Boolean) playing = (Boolean) b;
                    } catch (Throwable ignored) {
                    }
                }
                boolean isActive = activeDevice != null && activeDevice.equals(d);

                String name;
                try {
                    name = d.getAlias();
                    if (name == null || name.isEmpty()) name = d.getName();
                } catch (SecurityException se) {
                    name = d.getAddress();
                }
                if (name == null || name.isEmpty()) name = d.getAddress();

                String codec = connected ? queryCodec(d) : "";
                Integer volBoxed = volumes.get(d.getAddress().toUpperCase(java.util.Locale.US));
                int volume = volBoxed == null ? -1 : volBoxed;

                out.add(new DeviceInfo(d, name, d.getAddress(),
                        connected, playing, isActive, codec, volume));
            }
        } catch (SecurityException se) {
            Log.w(TAG, "Permission denied listing bonded devices", se);
        }

        // Sort: active first, then connected, then rest.
        out.sort((a, b) -> {
            if (a.activePrimary != b.activePrimary) return a.activePrimary ? -1 : 1;
            if (a.connected != b.connected) return a.connected ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return out;
    }

    /**
     * Reflectively call BluetoothA2dp.getCodecStatus(device) (requires
     * BLUETOOTH_PRIVILEGED which we have as a platform-signed app).
     * Returns a short human-readable codec description of the form
     * "AAC 44.1k 320kbps", or "" on any failure.
     */
    private String queryCodec(BluetoothDevice d) {
        if (mA2dp == null) return "";
        try {
            Method m = BluetoothA2dp.class.getMethod("getCodecStatus", BluetoothDevice.class);
            Object status = m.invoke(mA2dp, d);
            if (status == null) return "";
            Method gc = status.getClass().getMethod("getCodecConfig");
            Object cfg = gc.invoke(status);
            return cfg == null ? "" : describeCodec(cfg);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String describeCodec(Object cfg) throws Throwable {
        int type = (int) cfg.getClass().getMethod("getCodecType").invoke(cfg);
        String name = codecName(type);
        if (name.isEmpty()) return "";

        StringBuilder s = new StringBuilder(name);

        int sampleMask = (int) cfg.getClass().getMethod("getSampleRate").invoke(cfg);
        String sr = sampleRateText(sampleMask);
        if (!sr.isEmpty()) s.append(' ').append(sr);

        long codecSpec1 = (long) cfg.getClass().getMethod("getCodecSpecific1").invoke(cfg);
        // AAC: codecSpecific1 = bitrate in bits/sec (bit 31 is VBR flag).
        if (type == 1 && codecSpec1 > 0) {
            long bitrate = codecSpec1 & 0x7FFFFFFFL;
            long kbps = bitrate / 1000;
            if (kbps > 0) s.append(' ').append(kbps).append("kbps");
        }
        // LDAC: codecSpecific1 bits 0-1 encode quality (0=HQ 990k, 1=SQ 660k,
        // 2=MQ 330k, 3=ABR). Best effort; not all devices honour the values.
        if (type == 4) {
            switch ((int) (codecSpec1 & 0x3L)) {
                case 0: s.append(" HQ");  break;
                case 1: s.append(" SQ");  break;
                case 2: s.append(" MQ");  break;
                case 3: s.append(" ABR"); break;
                default: break;
            }
        }
        return s.toString();
    }

    private static String sampleRateText(int mask) {
        // BluetoothCodecConfig.SAMPLE_RATE_* bitmasks. When "selected" only
        // one bit is set; otherwise the mask advertises capabilities.
        if ((mask & (1 << 0)) != 0) return "44.1k";
        if ((mask & (1 << 1)) != 0) return "48k";
        if ((mask & (1 << 2)) != 0) return "88.2k";
        if ((mask & (1 << 3)) != 0) return "96k";
        if ((mask & (1 << 4)) != 0) return "176.4k";
        if ((mask & (1 << 5)) != 0) return "192k";
        return "";
    }

    private static String codecName(int type) {
        // BluetoothCodecConfig.SOURCE_CODEC_TYPE_* constants (stable since API 29).
        switch (type) {
            case 0:  return "SBC";
            case 1:  return "AAC";
            case 2:  return "aptX";
            case 3:  return "aptX HD";
            case 4:  return "LDAC";
            case 5:  return "LC3";
            case 6:  return "Opus";
            default: return "";
        }
    }

    /**
     * Parse Settings.Global.a2dp_dup_peer_volumes into a MAC→volume map.
     * Format written by DualAudioCoordinator: "MAC:vol,MAC:vol,...".
     * Empty map on any parse failure — slider falls back to its XML default.
     */
    private Map<String, Integer> readPublishedVolumes() {
        Map<String, Integer> out = new HashMap<>();
        String csv;
        try {
            csv = Settings.Global.getString(
                    mCtx.getContentResolver(), "a2dp_dup_peer_volumes");
        } catch (Throwable t) {
            return out;
        }
        if (csv == null || csv.isEmpty()) return out;
        for (String entry : csv.split(",")) {
            int colon = entry.lastIndexOf(':');
            if (colon < 0) continue;
            try {
                String mac = entry.substring(0, colon).toUpperCase(java.util.Locale.US);
                int vol = Integer.parseInt(entry.substring(colon + 1));
                out.put(mac, vol);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** Heuristic: peer's Bluetooth Class-of-Device claims audio/video (major class 4). */
    private static boolean isLikelyAudioDevice(BluetoothDevice d) {
        try {
            if (d.getBluetoothClass() == null) return false;
            int major = d.getBluetoothClass().getMajorDeviceClass();
            // Major class 0x0400 = AUDIO_VIDEO.
            return major == 0x0400;
        } catch (SecurityException ignored) {
            return false;
        }
    }
}
