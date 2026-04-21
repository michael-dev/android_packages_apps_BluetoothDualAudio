/*
 * BluetoothDualAudio — Settings helpers.
 *
 * Central read/write of Java-side enable flag (Settings.Global) plus
 * per-device inclusion list (SharedPreferences).
 */

package org.lineageos.dualaudio;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class DualAudioPref {
    /** Master Settings.Global key. 1 = on. */
    public static final String KEY_ENABLED = "a2dp_dup_active";

    /**
     * Per-device include list now lives in {@link DualAudioProvider}
     * (signature-protected). The old {@code a2dp_dup_members} Settings.Global
     * key is no longer used.
     */

    /** Legacy sysprop path. Read-only from the app side. */
    public static final String SYSPROP_ENABLED = "persist.bluetooth.a2dp.dup_active";

    /** Recommended max devices simultaneously sharing audio. */
    public static final int RECOMMENDED_MAX = 2;
    /** Hard warning threshold. */
    public static final int WARN_AT = 3;

    private DualAudioPref() {}

    public static boolean isMasterEnabled(ContentResolver cr) {
        // Tri-state, matching DualAudioCoordinator.isEnabled():
        //  1  → on
        //  0  → explicit off (overrides sysprop)
        // -1 / unset → fall back to sysprop.
        int v = Settings.Global.getInt(cr, KEY_ENABLED, -1);
        if (v >= 0) return v == 1;
        return SystemProperties.getBoolean(SYSPROP_ENABLED, false);
    }

    public static void setMasterEnabled(ContentResolver cr, boolean enabled) {
        try {
            boolean ok = Settings.Global.putInt(cr, KEY_ENABLED, enabled ? 1 : 0);
            Log.i("DualAudio", "setMasterEnabled(" + enabled + ") → putInt returned " + ok);
            int readback = Settings.Global.getInt(cr, KEY_ENABLED, -99);
            Log.i("DualAudio", "readback = " + readback);
        } catch (SecurityException se) {
            Log.e("DualAudio", "setMasterEnabled: SecurityException", se);
        } catch (Throwable t) {
            Log.e("DualAudio", "setMasterEnabled: Throwable", t);
        }
    }

    public static boolean isNativeSideArmed() {
        return SystemProperties.getBoolean(SYSPROP_ENABLED, false);
    }

    /**
     * True iff the include-list has never been explicitly set. In that
     * state the coordinator promotes all connected non-active peers.
     */
    public static boolean isFilterUnset(Context ctx) {
        Bundle b = callProvider(ctx, DualAudioProvider.METHOD_GET_MEMBERS, null, null);
        return b != null && b.getBoolean(DualAudioProvider.EXTRA_UNSET, false);
    }

    /**
     * Normalize a MAC to uppercase form — used as the storage/matching key.
     * Full MAC (not the suffix) because the provider is permission-gated.
     */
    public static String normalizeMac(String mac) {
        return mac == null ? "" : mac.toUpperCase(Locale.US);
    }

    /**
     * Returns the set of MACs explicitly in the include list. Returns
     * empty set both when the filter is unset AND when the list is
     * explicitly empty — use {@link #isFilterUnset(Context)} to
     * distinguish.
     */
    public static Set<String> getIncludedDeviceMacs(Context ctx) {
        Bundle b = callProvider(ctx, DualAudioProvider.METHOD_GET_MEMBERS, null, null);
        if (b == null || b.getBoolean(DualAudioProvider.EXTRA_UNSET, false)) {
            return Collections.emptySet();
        }
        ArrayList<String> macs = b.getStringArrayList(DualAudioProvider.EXTRA_MACS);
        if (macs == null || macs.isEmpty()) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (String m : macs) {
            String n = normalizeMac(m);
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    /** Whether device with {@code mac} is currently included for shared audio. */
    public static boolean isDeviceIncluded(Context ctx, String mac) {
        if (isFilterUnset(ctx)) return true;  // unset → all included
        return getIncludedDeviceMacs(ctx).contains(normalizeMac(mac));
    }

    /**
     * Toggle {@code mac} in the include list. Takes an optional
     * {@code allOtherMacs} snapshot so that when the user flips the FIRST
     * switch OFF from the "unset / all included" state, we materialize
     * the list as "all others except this one" (matching UI expectations).
     * The list is persisted through {@link DualAudioProvider} so full
     * MACs stay private.
     *
     * If {@code allOtherMacs} is null, the list is built as "just {mac}"
     * when included=true or cleared to "" when included=false.
     */
    public static void setDeviceIncluded(Context ctx, String mac,
                                         boolean included,
                                         Set<String> allOtherMacs) {
        String norm = normalizeMac(mac);
        if (norm.isEmpty()) return;

        boolean unset = isFilterUnset(ctx);
        Set<String> current;
        if (unset) {
            if (included) {
                // Already-on-by-default; no transition needed.
                return;
            }
            // Materialize: filter becomes "all currently-connected other MACs".
            current = new HashSet<>();
            if (allOtherMacs != null) {
                for (String m : allOtherMacs) {
                    String s = normalizeMac(m);
                    if (!s.isEmpty()) current.add(s);
                }
            }
        } else {
            current = new HashSet<>(getIncludedDeviceMacs(ctx));
            if (included) current.add(norm);
            else current.remove(norm);
        }

        Bundle extras = new Bundle();
        extras.putStringArrayList(DualAudioProvider.EXTRA_MACS, new ArrayList<>(current));
        callProvider(ctx, DualAudioProvider.METHOD_SET_MEMBERS, null, extras);
    }

    /** Legacy 2-arg helper (no snapshot). */
    public static void setDeviceIncluded(Context ctx, String mac, boolean included) {
        setDeviceIncluded(ctx, mac, included, null);
    }

    /**
     * Thin wrapper around ContentResolver.call() with SecurityException
     * swallowed: in rare boot-race cases the provider's owning process
     * might be spinning up; callers treat null as "state not available
     * yet" and retry on the next refresh.
     */
    private static Bundle callProvider(Context ctx, String method,
                                       String arg, Bundle extras) {
        try {
            return ctx.getContentResolver().call(
                    DualAudioProvider.URI, method, arg, extras);
        } catch (SecurityException se) {
            Log.e("DualAudio", "callProvider(" + method + "): SecurityException", se);
            return null;
        } catch (Throwable t) {
            Log.w("DualAudio", "callProvider(" + method + ") failed", t);
            return null;
        }
    }
}
