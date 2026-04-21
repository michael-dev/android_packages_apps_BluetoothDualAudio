/*
 * SM-X205 dual-A2DP app — Settings helpers.
 *
 * Central read/write of Java-side enable flag (Settings.Global) plus
 * per-device inclusion list (SharedPreferences).
 */

package org.lineageos.dualaudio;

import android.content.ContentResolver;
import android.content.Context;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class DualAudioPref {
    /** Master Settings.Global key. 1 = on. */
    public static final String KEY_ENABLED = "a2dp_dup_active";

    /**
     * Settings.Global key for the per-device include list. Comma-separated
     * uppercase MAC addresses (e.g. "AA:BB:CC:DD:EE:FF,11:22:33:44:55:66").
     * Empty / unset means "no explicit filter — promote all connected
     * non-active A2DP peers". Non-empty means "only these MACs".
     * Read by DualAudioCoordinator.autoPromoteConnectedPeers().
     */
    public static final String KEY_MEMBERS = "a2dp_dup_members";

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
     * True iff the filter key in Settings.Global has never been written
     * (null). In that state the coordinator promotes all connected
     * non-active peers.
     */
    public static boolean isFilterUnset(Context ctx) {
        return Settings.Global.getString(ctx.getContentResolver(), KEY_MEMBERS) == null;
    }

    /**
     * Returns the set of MACs explicitly in the include list. Returns empty
     * set both when the filter is unset AND when the list is the empty
     * string — use {@link #isFilterUnset(Context)} to distinguish.
     */
    public static Set<String> getIncludedDeviceMacs(Context ctx) {
        String csv = Settings.Global.getString(ctx.getContentResolver(), KEY_MEMBERS);
        if (csv == null || csv.isEmpty()) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (String mac : csv.split(",")) {
            String t = mac.trim();
            if (!t.isEmpty()) out.add(t.toUpperCase());
        }
        return out;
    }

    /** Whether device with {@code mac} is currently included for shared audio. */
    public static boolean isDeviceIncluded(Context ctx, String mac) {
        if (isFilterUnset(ctx)) return true;  // unset → all included
        Set<String> included = getIncludedDeviceMacs(ctx);
        return included.contains(mac == null ? "" : mac.toUpperCase());
    }

    /**
     * Toggle {@code mac} in the include list. Takes an optional
     * {@code allOtherMacs} snapshot so that when the user flips the FIRST
     * switch OFF from the "unset / all included" state, we materialize the
     * list as "all others except this one" (matching UI expectations).
     *
     * If {@code allOtherMacs} is null, the list is built as "just {mac}"
     * when included=true or cleared to "" when included=false.
     */
    public static void setDeviceIncluded(Context ctx, String mac,
                                         boolean included,
                                         Set<String> allOtherMacs) {
        if (mac == null || mac.isEmpty()) return;
        String norm = mac.toUpperCase();

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
                    if (m != null && !m.isEmpty()) current.add(m.toUpperCase());
                }
            }
        } else {
            current = new HashSet<>(getIncludedDeviceMacs(ctx));
            if (included) current.add(norm);
            else current.remove(norm);
        }

        String csv = String.join(",", current);
        try {
            Settings.Global.putString(ctx.getContentResolver(), KEY_MEMBERS, csv);
        } catch (SecurityException se) {
            Log.e("DualAudio", "setDeviceIncluded: SecurityException", se);
        }
    }

    /** Legacy 2-arg helper (no snapshot). */
    public static void setDeviceIncluded(Context ctx, String mac, boolean included) {
        setDeviceIncluded(ctx, mac, included, null);
    }
}
