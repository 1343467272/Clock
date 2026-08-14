/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.best.deskclock.settings;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.best.deskclock.DeskClockApplication;
import com.best.deskclock.R;
import com.best.deskclock.base.BaseSettingsScreenFragment;
import com.best.deskclock.databinding.LanSyncPeersDialogBinding;
import com.best.deskclock.sync.SyncEngine;
import com.best.deskclock.sync.SyncPeerInfo;
import com.best.deskclock.sync.SyncSettings;
import com.best.deskclock.uicomponents.CustomDialog;
import com.best.deskclock.uicomponents.toast.CustomToast;

import java.util.List;

/**
 * LAN sync settings: enable/disable the sync engine, edit the device name and port, pair and unpair
 * the detected devices and trigger a manual sync. Devices are listed with a pair/unpair action;
 * paired devices auto-connect while the app is running.
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
    private LanSyncPeersAdapter mPeersAdapter;
    private LanSyncPeersDialogBinding mPeersDialogBinding;

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
        registerWithEngine();
        updateSummaries();
    }

    @Override
    public void onPause() {
        super.onPause();
        final SyncEngine engine = getSyncEngine();
        if (engine != null) {
            engine.setPeersListener(null);
            engine.setSettingsScreenVisible(false);
        }
    }

    @Override
    public void onDestroyView() {
        if (mPeersDialogBinding != null && mPeersDialogBinding.peersList != null) {
            mPeersDialogBinding.peersList.setAdapter(null);
        }
        mPeersDialogBinding = null;
        mPeersAdapter = null;
        super.onDestroyView();
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
                final DeskClockApplication application =
                    (DeskClockApplication) requireContext().getApplicationContext();
                application.setSyncEnabled(enabled);
                if (enabled) {
                    registerWithEngine();
                }
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

    private SyncEngine getSyncEngine() {
        final DeskClockApplication application =
            (DeskClockApplication) requireContext().getApplicationContext();
        return application.getSyncEngine();
    }

    private void registerWithEngine() {
        final SyncEngine engine = getSyncEngine();
        if (engine != null) {
            engine.setPeersListener(this::refreshPeersUi);
            engine.setSettingsScreenVisible(true);
        }
    }

    private void updateSummaries() {
        final Context context = requireContext();
        mDeviceNamePref.setSummary(SyncSettings.getDeviceName(context));
        mPortPref.setSummary(String.valueOf(SyncSettings.getPort(context)));

        final boolean enabled = SyncSettings.isSyncEnabled(context);
        mSyncNowPref.setEnabled(enabled);

        final List<SyncPeerInfo> peers = SyncSettings.getPeers(context);
        int pairedCount = 0;
        for (SyncPeerInfo peer : peers) {
            if (peer.paired) {
                pairedCount++;
            }
        }
        String summary;
        if (peers.isEmpty()) {
            summary = getString(R.string.lan_sync_peers_empty_summary);
        } else {
            summary = getString(R.string.lan_sync_peers_summary, peers.size(), pairedCount);
        }
        final SyncEngine engine = getSyncEngine();
        if (engine != null && engine.isConnectedToPairedDevice()) {
            final String connectedDeviceId = engine.getConnectedDeviceId();
            if (connectedDeviceId != null) {
                for (SyncPeerInfo peer : peers) {
                    if (peer.deviceId.equals(connectedDeviceId)) {
                        summary += " \u00b7 " + getString(R.string.lan_sync_connected_to, peer.deviceName);
                        break;
                    }
                }
            }
        }
        mPeersPref.setSummary(summary);
    }

    /**
     * Called (on the main thread) whenever the sync engine notices a peer or connection change.
     */
    private void refreshPeersUi() {
        if (!isAdded()) {
            return;
        }
        updateSummaries();
        refreshPeersDialog();
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
                registerWithEngine();
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
        mPeersDialogBinding = LanSyncPeersDialogBinding.inflate(getLayoutInflater());
        mPeersDialogBinding.peersList.setLayoutManager(new LinearLayoutManager(context));
        mPeersAdapter = new LanSyncPeersAdapter(this::onPairClicked);
        mPeersDialogBinding.peersList.setAdapter(mPeersAdapter);

        refreshPeersDialog();

        mActiveDialog = CustomDialog.create(
            context,
            null,
            null,
            getString(R.string.lan_sync_peers),
            null,
            mPeersDialogBinding.getRoot(),
            getString(android.R.string.ok),
            null,
            null,
            null,
            null,
            null,
            dialog -> dialog.setOnDismissListener(d -> mPeersDialogBinding = null),
            CustomDialog.SoftInputMode.NONE
        );
        mActiveDialog.show();
    }

    private void refreshPeersDialog() {
        if (mPeersDialogBinding == null || mPeersAdapter == null) {
            return;
        }
        final Context context = requireContext();
        final List<SyncPeerInfo> peers = SyncSettings.getPeers(context);
        final SyncEngine engine = getSyncEngine();
        final String connectedDeviceId = engine == null ? null : engine.getConnectedDeviceId();

        final boolean empty = peers.isEmpty();
        mPeersDialogBinding.peersEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        mPeersDialogBinding.peersList.setVisibility(empty ? View.GONE : View.VISIBLE);
        mPeersAdapter.update(peers, connectedDeviceId);
    }

    private void onPairClicked(SyncPeerInfo peer) {
        final Context context = requireContext();
        final boolean pair = !peer.paired;
        SyncSettings.setPeerPaired(context, peer.deviceId, pair);
        final SyncEngine engine = getSyncEngine();
        if (engine != null) {
            engine.onPeerPairedChanged(peer.deviceId, pair);
            if (pair) {
                engine.connectToPeer(peer);
            }
        }
        refreshPeersDialog();
        updateSummaries();
    }

    private void syncNow() {
        final SyncEngine engine = getSyncEngine();
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
