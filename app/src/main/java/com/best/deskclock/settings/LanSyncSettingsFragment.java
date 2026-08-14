/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.best.deskclock.settings;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.best.deskclock.DeskClockApplication;
import com.best.deskclock.R;
import com.best.deskclock.base.BaseSettingsScreenFragment;
import com.best.deskclock.sync.SyncEngine;
import com.best.deskclock.sync.SyncPeerInfo;
import com.best.deskclock.sync.SyncSettings;
import com.best.deskclock.uicomponents.CustomDialog;
import com.best.deskclock.uicomponents.toast.CustomToast;

import java.util.List;

/**
 * LAN sync settings: enable/disable the sync engine, edit the device name and port, list the
 * detected devices and trigger a manual sync.
 */
public final class LanSyncSettingsFragment extends BaseSettingsScreenFragment
    implements Preference.OnPreferenceChangeListener, Preference.OnPreferenceClickListener {

    private static final int MIN_SYNC_PORT = 1024;
    private static final int MAX_SYNC_PORT = 65535;

    private SwitchPreferenceCompat mSyncEnabledPref;
    private Preference mDeviceNamePref;
    private Preference mPortPref;
    private Preference mPeersPref;
    private Preference mSyncNowPref;

    @Override
    protected String getFragmentTitle() {
        return getString(R.string.lan_sync_title);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.settings_lan_sync);

        mSyncEnabledPref = findPreference(SyncSettings.KEY_SYNC_ENABLED);
        mDeviceNamePref = findPreference(SyncSettings.KEY_DEVICE_NAME);
        mPortPref = findPreference(SyncSettings.KEY_PORT);
        mPeersPref = findPreference(SyncSettings.KEY_PEERS);
        mSyncNowPref = findPreference(PreferencesKeys.KEY_LAN_SYNC_NOW);

        setupPreferences();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateSummaries();
    }

    @Override
    public void onDestroy() {
        nullifyPreferenceListeners(mSyncEnabledPref, mDeviceNamePref, mPortPref, mPeersPref, mSyncNowPref);
        nullifyAllPrefs();
        super.onDestroy();
    }

    @Override
    public boolean onPreferenceChange(@NonNull Preference pref, Object newValue) {
        if (pref.getKey() == null) {
            return true;
        }
        switch (pref.getKey()) {
            case SyncSettings.KEY_SYNC_ENABLED -> {
                final boolean enabled = (Boolean) newValue;
                SyncSettings.setSyncEnabled(requireContext(), enabled);
                ((DeskClockApplication) requireContext().getApplicationContext()).setSyncEnabled(enabled);
            }
        }
        return true;
    }

    @Override
    public boolean onPreferenceClick(@NonNull Preference pref) {
        final String key = pref.getKey();
        if (key == null) {
            return true;
        }
        switch (key) {
            case SyncSettings.KEY_DEVICE_NAME -> showDeviceNameDialog();
            case SyncSettings.KEY_PORT -> showPortDialog();
            case SyncSettings.KEY_PEERS -> showPeersDialog();
            case PreferencesKeys.KEY_LAN_SYNC_NOW -> syncNow();
        }
        return true;
    }

    private void setupPreferences() {
        mSyncEnabledPref.setOnPreferenceChangeListener(this);
        mDeviceNamePref.setOnPreferenceClickListener(this);
        mPortPref.setOnPreferenceClickListener(this);
        mPeersPref.setOnPreferenceClickListener(this);
        mSyncNowPref.setOnPreferenceClickListener(this);
    }

    private void updateSummaries() {
        final Context context = requireContext();
        mDeviceNamePref.setSummary(SyncSettings.getDeviceName(context));
        mPortPref.setSummary(String.valueOf(SyncSettings.getPort(context)));

        final boolean enabled = SyncSettings.isSyncEnabled(context);
        mSyncNowPref.setEnabled(enabled);

        final List<SyncPeerInfo> peers = SyncSettings.getPeers(context);
        mPeersPref.setSummary(peers.isEmpty()
            ? getString(R.string.lan_sync_peers_empty_summary)
            : getString(R.string.lan_sync_peers_count, peers.size()));
    }

    private void showDeviceNameDialog() {
        final Context context = requireContext();
        final EditText input = new EditText(context);
        input.setText(SyncSettings.getDeviceName(context));
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.selectAll();

        mActiveDialog = CustomDialog.create(
            context,
            null,
            null,
            getString(R.string.lan_sync_device_name_dialog_title),
            null,
            input,
            getString(android.R.string.ok),
            (d, w) -> {
                String name = input.getText() == null ? "" : input.getText().toString().trim();
                if (name.isEmpty()) {
                    name = SyncSettings.getDeviceName(context);
                }
                SyncSettings.setDeviceName(context, name);
                mDeviceNamePref.setSummary(name);
            },
            getString(android.R.string.cancel),
            null,
            null,
            null,
            null,
            CustomDialog.SoftInputMode.SHOW_KEYBOARD
        );
        mActiveDialog.show();
    }

    private void showPortDialog() {
        final Context context = requireContext();
        final EditText input = new EditText(context);
        input.setText(String.valueOf(SyncSettings.getPort(context)));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.selectAll();

        mActiveDialog = CustomDialog.create(
            context,
            null,
            null,
            getString(R.string.lan_sync_port_dialog_title),
            null,
            input,
            getString(android.R.string.ok),
            (d, w) -> {
                final String text = input.getText() == null ? "" : input.getText().toString().trim();
                final int port;
                try {
                    port = Integer.parseInt(text);
                } catch (NumberFormatException e) {
                    return;
                }
                if (port < MIN_SYNC_PORT || port > MAX_SYNC_PORT) {
                    CustomToast.show(context, R.string.lan_sync_port_invalid);
                    return;
                }
                SyncSettings.setPort(context, port);
                mPortPref.setSummary(String.valueOf(port));

                // Rebind the server socket to the new port.
                final DeskClockApplication application = (DeskClockApplication) context.getApplicationContext();
                application.setSyncEnabled(false);
                application.setSyncEnabled(SyncSettings.isSyncEnabled(context));
            },
            getString(android.R.string.cancel),
            null,
            null,
            null,
            null,
            CustomDialog.SoftInputMode.SHOW_KEYBOARD
        );
        mActiveDialog.show();
    }

    private void showPeersDialog() {
        final Context context = requireContext();
        final List<SyncPeerInfo> peers = SyncSettings.getPeers(context);
        final StringBuilder message = new StringBuilder();
        if (peers.isEmpty()) {
            message.append(getString(R.string.lan_sync_peers_empty_summary));
        } else {
            for (int i = 0; i < peers.size(); i++) {
                final SyncPeerInfo peer = peers.get(i);
                if (i > 0) {
                    message.append('\n');
                }
                message.append(peer.deviceName).append('\n');
                message.append(peer.address).append(':').append(peer.port);
            }
        }

        mActiveDialog = CustomDialog.create(
            context,
            null,
            null,
            getString(R.string.lan_sync_peers),
            message,
            null,
            getString(android.R.string.ok),
            null,
            null,
            null,
            null,
            null,
            null,
            CustomDialog.SoftInputMode.NONE
        );
        mActiveDialog.show();
    }

    private void syncNow() {
        final DeskClockApplication application = (DeskClockApplication) requireContext().getApplicationContext();
        final SyncEngine engine = application.getSyncEngine();
        if (engine != null) {
            engine.syncNow();
        }
        CustomToast.show(requireContext(), R.string.lan_sync_now_toast);
    }

    private void nullifyAllPrefs() {
        mSyncEnabledPref = null;
        mDeviceNamePref = null;
        mPortPref = null;
        mPeersPref = null;
        mSyncNowPref = null;
    }
}
