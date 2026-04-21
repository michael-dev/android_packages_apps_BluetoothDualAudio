/*
 * SM-X205 dual-A2DP app — test helpers to force codec and active device.
 *
 * Broadcast actions (must target component with -n):
 *
 *   org.lineageos.dualaudio.SET_CODEC   --es mac XX:XX --es codec SBC
 *     Sets stored codec preference. Renegotiation only happens while the
 *     device is active (AOSP limitation); pair with SET_ACTIVE first.
 *
 *   org.lineageos.dualaudio.SET_ACTIVE  --es mac XX:XX
 *     Sets the A2DP active device. Use to make a peer primary before
 *     issuing SET_CODEC so the codec actually renegotiates.
 *
 * Accepted codec strings: SBC, AAC, aptX, aptX_HD, LDAC, LC3, Opus.
 * Needs BLUETOOTH_PRIVILEGED, which the platform-signed app has.
 *
 * Purpose: reproduce codec-mismatch dual-A2DP scenarios (Wk 7/8) without
 * having to pair a non-AAC device.
 */

package org.lineageos.dualaudio;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.lang.reflect.Method;

public class SetCodecReceiver extends BroadcastReceiver {
    private static final String TAG = "DualAudio.SetCodec";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        final String action = intent.getAction();
        final String mac = intent.getStringExtra("mac");
        if (mac == null) {
            Log.w(TAG, "missing --es mac");
            return;
        }

        BluetoothManager bm = ctx.getSystemService(BluetoothManager.class);
        if (bm == null) { Log.e(TAG, "no BluetoothManager"); return; }
        BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null) { Log.e(TAG, "no BluetoothAdapter"); return; }

        final BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(mac.toUpperCase());
        } catch (IllegalArgumentException iae) {
            Log.w(TAG, "invalid mac: " + mac);
            return;
        }

        final int codecType;
        if ("org.lineageos.dualaudio.SET_CODEC".equals(action)) {
            String codecStr = intent.getStringExtra("codec");
            if (codecStr == null) { Log.w(TAG, "SET_CODEC needs --es codec"); return; }
            codecType = codecTypeFromString(codecStr);
            if (codecType < 0) { Log.w(TAG, "unknown codec: " + codecStr); return; }
        } else {
            codecType = -1;  // SET_ACTIVE only
        }

        final PendingResult pr = goAsync();
        adapter.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile != BluetoothProfile.A2DP) { pr.finish(); return; }
                BluetoothA2dp a2dp = (BluetoothA2dp) proxy;
                try {
                    if ("org.lineageos.dualaudio.SET_ACTIVE".equals(action)) {
                        Method m = BluetoothA2dp.class.getMethod(
                                "setActiveDevice", BluetoothDevice.class);
                        Object ok = m.invoke(a2dp, device);
                        Log.i(TAG, "setActiveDevice(" + mac + ") -> " + ok);
                    } else if (codecType >= 0) {
                        BluetoothCodecConfig cfg = new BluetoothCodecConfig.Builder()
                                .setCodecType(codecType)
                                .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
                                .build();
                        Method m = BluetoothA2dp.class.getMethod(
                                "setCodecConfigPreference",
                                BluetoothDevice.class,
                                BluetoothCodecConfig.class);
                        m.invoke(a2dp, device, cfg);
                        Log.i(TAG, "setCodecConfigPreference(" + mac + ", "
                                + intent.getStringExtra("codec") + ") dispatched");
                    }
                } catch (Throwable t) {
                    Log.e(TAG, action + " failed", t);
                } finally {
                    adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
                    pr.finish();
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {}
        }, BluetoothProfile.A2DP);
    }

    private static int codecTypeFromString(String s) {
        switch (s.toUpperCase()) {
            case "SBC":     return BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC;
            case "AAC":     return BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC;
            case "APTX":    return BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX;
            case "APTX_HD": return BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD;
            case "LDAC":    return BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC;
            case "LC3":     return BluetoothCodecConfig.SOURCE_CODEC_TYPE_LC3;
            case "OPUS":    return BluetoothCodecConfig.SOURCE_CODEC_TYPE_OPUS;
            default: return -1;
        }
    }
}
