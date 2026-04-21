/*
 * BluetoothDualAudio — Quick Settings tile.
 *
 * Tap to toggle Settings.Global.a2dp_dup_active. Tile state reflects the
 * current value. Long-press opens DualAudioSettingsActivity.
 */

package org.lineageos.dualaudio;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class DualAudioTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        refreshTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        boolean current = DualAudioPref.isMasterEnabled(getContentResolver());
        DualAudioPref.setMasterEnabled(getContentResolver(), !current);
        refreshTile();
    }

    private void refreshTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean enabled = DualAudioPref.isMasterEnabled(getContentResolver());
        tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.app_name));
        tile.setSubtitle(enabled ? "On" : "Off");
        tile.updateTile();
    }
}
