/*
 * BluetoothDualAudio — Settings helpers.
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
import java.util.Locale;
import java.util.Set;

public final class DualAudioPref {
    /** Master Settings.Global key. 1 = on. */
    public static final String KEY_ENABLED = "a2dp_dup_active";

    /**
     * Settings.Global key for the per-device include list. Comma-separated
     * uppercase MAC **suffixes** — last 5 chars, e.g. "AA:3D,91:26".
     * Empty / unset means "no explicit filter — promote all connected
     * non-active A2DP peers". Non-empty means "only these suffixes".
     *
     * Suffix-only (not the full MAC) because Settings.Global is
     * world-readable by every app with no permission; storing full MACs
     * would leak paired-device identifiers. Coordinator matches on
     * suffix anyway (Android per-process MAC anonymization leaves the
     * last two octets invariant).
     *
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
     * Last-5-chars uppercase suffix of a MAC (e.g. "AA:3D"). The
     * coordinator uses the same value for cross-process matching since
     * the first four octets are per-process-anonymized on modern Android.
     */
    public static String macSuffix(String mac) {
        if (mac == null || mac.length() < 5) return "";
        return mac.substring(mac.length() - 5).toUpperCase(Locale.US);
    }

    /**
     * Returns the set of MAC suffixes explicitly in the include list.
     * Returns empty set both when the filter is unset AND when the list
     * is the empty string — use {@link #isFilterUnset(Context)} to
     * distinguish.
     */
    public static Set<String> getIncludedDeviceMacs(Context ctx) {
        String csv = Settings.Global.getString(ctx.getContentResolver(), KEY_MEMBERS);
        if (csv == null || csv.isEmpty()) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (String entry : csv.split(",")) {
            String s = macSuffix(entry.trim());
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** Whether device with {@code mac} is currently included for shared audio. */
    public static boolean isDeviceIncluded(Context ctx, String mac) {
        if (isFilterUnset(ctx)) return true;  // unset → all included
        Set<String> included = getIncludedDeviceMacs(ctx);
        return included.contains(macSuffix(mac));
    }

    /**
     * Toggle {@code mac} in the include list. Takes an optional
     * {@code allOtherMacs} snapshot so that when the user flips the FIRST
     * switch OFF from the "unset / all included" state, we materialize
     * the list as "all others except this one" (matching UI expectations).
     * The list is persisted as MAC suffixes only.
     *
     * If {@code allOtherMacs} is null, the list is built as "just {mac}"
     * when included=true or cleared to "" when included=false.
     */
    public static void setDeviceIncluded(Context ctx, String mac,
                                         boolean included,
                                         Set<String> allOtherMacs) {
        String norm = macSuffix(mac);
        if (norm.isEmpty()) return;

        boolean unset = isFilterUnset(ctx);
        Set<String> current;
        if (unset) {
            if (included) {
                // Already-on-by-default; no transition needed.
                return;
            }
            // Materialize: filter becomes "all currently-connected other
            // MAC suffixes".
            current = new HashSet<>();
            if (allOtherMacs != null) {
                for (String m : allOtherMacs) {
                    String s = macSuffix(m);
                    if (!s.isEmpty()) current.add(s);
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
