/*
 * BluetoothDualAudio — signature-gated persistence for members + volumes.
 *
 * Why a ContentProvider instead of Settings.Global:
 *   Settings.Global is world-readable by every app with no permission
 *   required. Storing full MAC addresses there would leak paired-device
 *   identifiers to any 3rd-party app via a single getString() call.
 *
 *   This provider is declared with a signature-level permission in the
 *   manifest, so only platform-signed callers (the app itself and the
 *   DualAudioCoordinator inside the Bluetooth APEX) can read or write.
 *   Backing storage is the app's private SharedPreferences, not visible
 *   to any other process.
 *
 * Shape — call()-based key/value API (no cursors needed):
 *
 *   uri: content://org.lineageos.dualaudio.provider
 *
 *   method "getMembers" → Bundle
 *     if "unset" bool true → members filter is unset (promote all)
 *     else "macs" ArrayList<String> of full MACs (possibly empty)
 *
 *   method "setMembers" extras:
 *     if "unset" bool true → mark unset (forget any previous list)
 *     else "macs" ArrayList<String> → exact new list
 *
 *   method "getVolumes" → Bundle
 *     "pairs" ArrayList<String> — each "MAC=vol", where MAC is
 *     uppercase and vol is an integer
 *
 *   method "setVolume" arg=mac extras:
 *     "volume" int — new volume for that peer
 *
 * Observers register on the sub-URIs .../members and .../volumes.
 * Writes call notifyChange on the respective sub-URI.
 */

package org.lineageos.dualaudio;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DualAudioProvider extends ContentProvider {
    private static final String TAG = "DualAudioProvider";

    public static final String AUTHORITY = "org.lineageos.dualaudio.provider";
    public static final Uri URI =
            Uri.parse(ContentResolver.SCHEME_CONTENT + "://" + AUTHORITY);
    public static final Uri URI_MEMBERS = Uri.withAppendedPath(URI, "members");
    public static final Uri URI_VOLUMES = Uri.withAppendedPath(URI, "volumes");

    public static final String METHOD_GET_MEMBERS = "getMembers";
    public static final String METHOD_SET_MEMBERS = "setMembers";
    public static final String METHOD_GET_VOLUMES = "getVolumes";
    public static final String METHOD_SET_VOLUME  = "setVolume";

    public static final String EXTRA_MACS     = "macs";     // ArrayList<String>
    public static final String EXTRA_UNSET    = "unset";    // boolean
    public static final String EXTRA_PAIRS    = "pairs";    // ArrayList<String> ("MAC=vol")
    public static final String EXTRA_VOLUME   = "volume";   // int

    private static final String PREFS_NAME = "dualaudio_state";
    private static final String KEY_MEMBERS_SET  = "members_set";   // boolean marker
    private static final String KEY_MEMBERS_MACS = "members_macs";  // StringSet
    private static final String KEY_VOL_PREFIX   = "vol_";          // per-peer int, key=vol_<MAC>

    private SharedPreferences mPrefs;

    @Override
    public boolean onCreate() {
        mPrefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (method == null) return null;
        switch (method) {
            case METHOD_GET_MEMBERS: return getMembers();
            case METHOD_SET_MEMBERS: setMembers(extras); return null;
            case METHOD_GET_VOLUMES: return getVolumes();
            case METHOD_SET_VOLUME:  setVolume(arg, extras); return null;
            default:
                Log.w(TAG, "unknown call method: " + method);
                return null;
        }
    }

    private Bundle getMembers() {
        Bundle out = new Bundle();
        boolean set = mPrefs.getBoolean(KEY_MEMBERS_SET, false);
        if (!set) {
            out.putBoolean(EXTRA_UNSET, true);
            return out;
        }
        Set<String> macs = mPrefs.getStringSet(KEY_MEMBERS_MACS, null);
        out.putStringArrayList(EXTRA_MACS,
                macs == null ? new ArrayList<>() : new ArrayList<>(macs));
        return out;
    }

    private void setMembers(Bundle extras) {
        SharedPreferences.Editor e = mPrefs.edit();
        boolean unset = extras != null && extras.getBoolean(EXTRA_UNSET, false);
        if (unset) {
            e.remove(KEY_MEMBERS_SET).remove(KEY_MEMBERS_MACS);
        } else {
            ArrayList<String> macs = extras == null
                    ? null : extras.getStringArrayList(EXTRA_MACS);
            Set<String> s = new HashSet<>();
            if (macs != null) {
                for (String m : macs) {
                    if (m != null && !m.isEmpty()) {
                        s.add(m.toUpperCase(Locale.US));
                    }
                }
            }
            e.putBoolean(KEY_MEMBERS_SET, true).putStringSet(KEY_MEMBERS_MACS, s);
        }
        e.apply();
        getContext().getContentResolver().notifyChange(URI_MEMBERS, null);
    }

    private Bundle getVolumes() {
        Bundle out = new Bundle();
        ArrayList<String> pairs = new ArrayList<>();
        for (Map.Entry<String, ?> e : mPrefs.getAll().entrySet()) {
            String k = e.getKey();
            if (k.startsWith(KEY_VOL_PREFIX) && e.getValue() instanceof Integer) {
                pairs.add(k.substring(KEY_VOL_PREFIX.length()) + "=" + e.getValue());
            }
        }
        out.putStringArrayList(EXTRA_PAIRS, pairs);
        return out;
    }

    private void setVolume(String mac, Bundle extras) {
        if (mac == null || mac.isEmpty() || extras == null) return;
        int vol = extras.getInt(EXTRA_VOLUME, -1);
        if (vol < 0) return;
        mPrefs.edit()
                .putInt(KEY_VOL_PREFIX + mac.toUpperCase(Locale.US), vol)
                .apply();
        getContext().getContentResolver().notifyChange(URI_VOLUMES, null);
    }

    // Cursor/CRUD API not used; all ops go through call().
    @Override public Cursor query(Uri u, String[] p, String s, String[] sa, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] sa) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] sa) { return 0; }
}
