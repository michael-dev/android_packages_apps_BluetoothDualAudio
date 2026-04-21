/*
 * BluetoothDualAudio — Settings Activity.
 *
 * - Master switch (Settings.Global.a2dp_dup_active).
 * - Per-device include toggle + status (connection / playback / active-primary).
 * - Counts and too-many-devices warning.
 * - Native-side disarmed warning.
 */

package org.lineageos.dualaudio;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import org.lineageos.dualaudio.BtStateProvider.DeviceInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DualAudioSettingsActivity extends Activity {
    private static final int REQ_BLUETOOTH_CONNECT = 100;

    private Switch mMasterSwitch;
    private TextView mMasterSummary;
    private View mNativeWarning;

    private TextView mStatusHeadline;
    private TextView mStatusSubtitle;

    private View mWarnTooMany;
    private TextView mWarnTooManyText;

    private LinearLayout mDeviceList;
    private View mEmptyDevices;

    private BtStateProvider mBtState;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver mBtReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Debounce: coalesce rapid state changes into one refresh.
            mMain.removeCallbacks(mRefreshRunnable);
            mMain.postDelayed(mRefreshRunnable, 120L);
        }
    };
    private final Runnable mRefreshRunnable = () -> {
        if (mBtState != null) mBtState.refresh();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mMasterSwitch = findViewById(R.id.master_switch);
        mMasterSummary = findViewById(R.id.master_summary);
        mNativeWarning = findViewById(R.id.native_warning);

        mStatusHeadline = findViewById(R.id.status_headline);
        mStatusSubtitle = findViewById(R.id.status_subtitle);

        mWarnTooMany = findViewById(R.id.warning_too_many);
        mWarnTooManyText = findViewById(R.id.warning_too_many_text);

        mDeviceList = findViewById(R.id.device_list);
        mEmptyDevices = findViewById(R.id.empty_devices);

        mMasterSwitch.setChecked(DualAudioPref.isMasterEnabled(getContentResolver()));
        mMasterSwitch.setOnCheckedChangeListener((v, checked) -> {
            DualAudioPref.setMasterEnabled(getContentResolver(), checked);
            updateMasterUi();
            if (mBtState != null) mBtState.refresh();
        });

        mBtState = new BtStateProvider(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mMasterSwitch.setChecked(DualAudioPref.isMasterEnabled(getContentResolver()));
        updateMasterUi();

        // Ensure BLUETOOTH_CONNECT is granted before we query the A2DP state.
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                    REQ_BLUETOOTH_CONNECT);
            // onRequestPermissionsResult will start mBtState after the user decides.
            return;
        }

        mBtState.start(this::onDevicesUpdated);

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
        filter.addAction("android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED");
        filter.addAction("android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED");
        registerReceiver(mBtReceiver, filter, Context.RECEIVER_EXPORTED);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(mBtReceiver); } catch (IllegalArgumentException ignored) {}
        mMain.removeCallbacks(mRefreshRunnable);
        mBtState.stop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BLUETOOTH_CONNECT) {
            // Regardless of grant outcome, start the state provider so the UI
            // shows either the device list or the empty state.
            mBtState.start(this::onDevicesUpdated);
        }
    }

    private void updateMasterUi() {
        boolean enabled = mMasterSwitch.isChecked();
        mMasterSummary.setText(enabled
                ? R.string.toggle_summary_on
                : R.string.toggle_summary_off);
        mNativeWarning.setVisibility(
                DualAudioPref.isNativeSideArmed() ? View.GONE : View.VISIBLE);
    }

    private void onDevicesUpdated(List<DeviceInfo> devices) {
        mDeviceList.removeAllViews();

        final boolean filterUnset = DualAudioPref.isFilterUnset(this);
        Set<String> included = DualAudioPref.getIncludedDeviceMacs(this);

        int connected = 0;
        int playing = 0;
        int includedEnabledCount = 0;

        LayoutInflater inflater = LayoutInflater.from(this);

        for (DeviceInfo info : devices) {
            if (info.connected) connected++;
            if (info.playing) playing++;

            final View row = inflater.inflate(R.layout.item_device, mDeviceList, false);

            ((TextView) row.findViewById(R.id.dev_name)).setText(info.name);

            StringBuilder status = new StringBuilder();
            status.append(info.connected
                    ? getString(R.string.dev_connected)
                    : getString(R.string.dev_disconnected));
            status.append(" · ");
            status.append(info.playing
                    ? getString(R.string.dev_playing)
                    : getString(R.string.dev_idle));
            if (info.activePrimary) {
                status.append(" · ");
                status.append(getString(R.string.dev_primary));
            }
            if (info.codec != null && !info.codec.isEmpty()) {
                status.append(" · ");
                status.append(info.codec);
            }
            ((TextView) row.findViewById(R.id.dev_status)).setText(status.toString());

            row.findViewById(R.id.dev_play_indicator)
                    .setVisibility(info.playing ? View.VISIBLE : View.GONE);

            Switch sw = row.findViewById(R.id.dev_enable);
            // Primary is implicitly included (it's the source); disable toggle.
            final String devMac = DualAudioPref.normalizeMac(info.mac);
            if (info.activePrimary) {
                sw.setChecked(true);
                sw.setEnabled(false);
            } else {
                sw.setChecked(filterUnset || included.contains(devMac));
                sw.setEnabled(info.connected);
                final String mac = info.mac;
                sw.setOnCheckedChangeListener((v, checked) -> {
                    Set<String> allOtherMacs = new HashSet<>();
                    for (DeviceInfo other : devices) {
                        if (other.mac != null
                                && !other.mac.equals(mac)
                                && !other.activePrimary
                                && other.connected) {
                            allOtherMacs.add(other.mac);
                        }
                    }
                    DualAudioPref.setDeviceIncluded(this, mac, checked, allOtherMacs);
                    refreshWarning();
                });
            }

            if (info.activePrimary
                    || ((filterUnset || included.contains(devMac)) && info.connected)) {
                includedEnabledCount++;
            }

            // Wk 9 — per-peer volume slider. Sends a broadcast to the BT
            // APEX (DualAudioCoordinator), which dispatches absolute volume
            // via AvrcpVolumeManager. Initial progress reflects the current
            // per-peer volume as published in Settings.Global.
            SeekBar volSlider = row.findViewById(R.id.dev_volume);
            volSlider.setEnabled(info.connected);
            if (info.volume >= 0) {
                volSlider.setProgress(info.volume);
            }
            final String volMac = info.mac;
            volSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    Intent i = new Intent("org.lineageos.dualaudio.SET_PEER_VOLUME");
                    i.putExtra("mac", volMac);
                    i.putExtra("volume", progress);
                    i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                    // Restrict delivery to receivers holding CONTROL so a
                    // passive listener can't intercept the mac/volume extras.
                    sendBroadcast(i, "org.lineageos.dualaudio.permission.CONTROL");
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });

            // Icon tint: connected → accent, disconnected → secondary text.
            ImageView icon = row.findViewById(R.id.dev_icon);
            icon.setImageTintList(
                    android.content.res.ColorStateList.valueOf(
                            getColor(info.connected
                                    ? android.R.color.holo_blue_dark
                                    : android.R.color.darker_gray)));

            mDeviceList.addView(row);
        }

        mEmptyDevices.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
        mDeviceList.setVisibility(devices.isEmpty() ? View.GONE : View.VISIBLE);

        // Headline: count of connected
        String headline;
        if (connected == 0) headline = getString(R.string.hdr_count_zero);
        else if (connected == 1) headline = getString(R.string.hdr_count_one);
        else headline = getString(R.string.hdr_count_many, connected);
        mStatusHeadline.setText(headline);

        // Subtitle: playing count
        String subtitle;
        if (playing == 0) subtitle = getString(R.string.sub_playing_zero);
        else if (playing == 1) subtitle = getString(R.string.sub_playing_one);
        else subtitle = getString(R.string.sub_playing_many, playing);
        mStatusSubtitle.setText(subtitle);

        refreshWarning();
    }

    private void refreshWarning() {
        int total = 0;
        Set<String> included = DualAudioPref.getIncludedDeviceMacs(this);
        // Primary is always implicitly included. Plus the explicit set.
        total = included.size() + 1; // estimation; we don't track primary-MAC here
        // More conservative: count enabled toggles visible in the list.
        // Recompute from UI:
        int countedFromUi = 0;
        for (int i = 0; i < mDeviceList.getChildCount(); i++) {
            View row = mDeviceList.getChildAt(i);
            Switch sw = row.findViewById(R.id.dev_enable);
            if (sw != null && sw.isChecked() && sw.isEnabled() == false /* primary */ ) {
                countedFromUi++;
            } else if (sw != null && sw.isChecked()) {
                countedFromUi++;
            }
        }
        total = countedFromUi;

        if (DualAudioPref.isMasterEnabled(getContentResolver())
                && total >= DualAudioPref.WARN_AT) {
            mWarnTooMany.setVisibility(View.VISIBLE);
            mWarnTooManyText.setText(getString(R.string.warn_too_many, total));
        } else {
            mWarnTooMany.setVisibility(View.GONE);
        }
    }
}
