/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.best.deskclock.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.best.deskclock.DeskClockApplication;
import com.best.deskclock.data.CityListener;
import com.best.deskclock.data.DataModel;
import com.best.deskclock.data.StopwatchListener;
import com.best.deskclock.data.Timer;
import com.best.deskclock.data.TimerListener;
import com.best.deskclock.provider.Alarm;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P2P sync engine, mirroring the Windows {@code SyncEngine}: a TCP server on the configured sync
 * port plus outbound connections to discovered peers. Every connection exchanges a full snapshot;
 * both sides merge with last-write-wins semantics.
 *
 * <p>Connections follow a Bluetooth-like pairing model:
 * <ul>
 * <li>Only <em>paired</em> peers are auto-connected while the app is running (not only on the
 * settings screen). When no paired peer is connected, the engine connects to the first paired
 * peer it discovers; once connected, it stays with that single peer.</li>
 * <li>On the settings screen the engine reaches out to <em>every</em> peer (paired or not), so
 * unpaired devices can be discovered and paired.</li>
 * </ul>
 */
public final class SyncEngine implements SyncDiscovery.PeerListener {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final long SYNC_RATE_LIMIT_MS = 20_000;

    /**
     * How long to wait after the last local change before pushing a snapshot to peers, so a burst
     * of edits collapses into a single sync.
     */
    private static final long DATA_CHANGE_DEBOUNCE_MS = 500;

    /**
     * A paired peer is considered connected while its last successful sync is fresher than this.
     */
    private static final long CONNECTED_TIMEOUT_MS = 60_000;

    /**
     * Notified whenever the peer list or the connection state changes. Callbacks are posted on the
     * main thread.
     */
    public interface PeersListener {
        void onPeersChanged();
    }

    private final Context mContext;
    private final SyncDiscovery mDiscovery;
    private final ExecutorService mExecutor;
    private final Map<String, Long> mLastSyncByPeer = new HashMap<>();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean mRunning;
    private volatile boolean mSettingsScreenVisible;
    private volatile ServerSocket mServerSocket;
    private volatile Thread mAcceptThread;
    private volatile String mConnectedDeviceId;
    private volatile long mLastPairedSyncMs;
    private volatile PeersListener mPeersListener;

    private final Runnable mDataChangedRunnable = () -> {
        Log.d("ClockSync", "debounce fired at " + System.currentTimeMillis());
        if (mRunning) {
            syncNow();
        }
    };
    private ContentObserver mAlarmObserver;
    private SharedPreferences.OnSharedPreferenceChangeListener mPrefsListener;
    private TimerListener mTimerListener;
    private StopwatchListener mStopwatchListener;
    private CityListener mCityListener;

    public SyncEngine(Context context) {
        mContext = context.getApplicationContext();
        mDiscovery = new SyncDiscovery(mContext);
        mExecutor = Executors.newFixedThreadPool(4);
    }

    public void start() {
        if (mRunning) {
            return;
        }
        mRunning = true;
        mDiscovery.setPeerListener(this);
        mDiscovery.start();
        registerDataChangeListeners();

        try {
            mServerSocket = new ServerSocket(SyncSettings.getPort(mContext));
            mAcceptThread = new Thread(this::acceptLoop, "sync-engine-accept");
            mAcceptThread.start();
        } catch (IOException e) {
            // Port in use — outbound sync still works.
        }

        // Auto-connect to devices that were already paired.
        final List<SyncPeerInfo> peers = SyncSettings.getPairedPeers(mContext);
        for (SyncPeerInfo peer : peers) {
            mExecutor.execute(() -> syncWithPeer(peer, false));
        }
    }

    public void stop() {
        mRunning = false;
        mDiscovery.stop();
        unregisterDataChangeListeners();
        if (mServerSocket != null) {
            try {
                mServerSocket.close();
            } catch (IOException ignored) {
            }
        }
        mExecutor.shutdownNow();
    }

    /**
     * Immediately syncs with the peers the current search mode allows, bypassing the per-peer
     * rate limit. When called from the settings screen this reaches every known peer.
     */
    public void syncNow() {
        final List<SyncPeerInfo> peers = SyncSettings.getPeers(mContext);
        for (SyncPeerInfo peer : peers) {
            if (shouldConnect(peer)) {
                mExecutor.execute(() -> syncWithPeer(peer, true));
            }
        }
    }

    /**
     * Collapses a burst of local changes into a single push shortly after the last one. Fires on
     * the main thread and is a no-op while the engine is stopped.
     */
    private void scheduleDataSync() {
        Log.d("ClockSync", "scheduleDataSync at " + System.currentTimeMillis());
        if (!mRunning) {
            return;
        }
        mMainHandler.removeCallbacks(mDataChangedRunnable);
        mMainHandler.postDelayed(mDataChangedRunnable, DATA_CHANGE_DEBOUNCE_MS);
    }

    /**
     * Watches the data sources that can change locally: alarms (content provider), timers,
     * stopwatch and world cities (DataModel) and the synced settings (default preferences).
     * A remote merge writes the same places, so it triggers this path too — the resulting echo
     * push is harmless because the peer's LWW merge sees equal timestamps and skips it.
     */
    private void registerDataChangeListeners() {
        final Context context = mContext;

        mAlarmObserver = new ContentObserver(mMainHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                scheduleDataSync();
            }
        };
        context.getContentResolver().registerContentObserver(Alarm.CONTENT_URI, true, mAlarmObserver);

        final DataModel dataModel = DataModel.getDataModel();
        mTimerListener = new TimerListener() {
            @Override
            public void timerAdded(Timer timer) {
                scheduleDataSync();
            }

            @Override
            public void timerUpdated(Timer before, Timer after) {
                scheduleDataSync();
            }

            @Override
            public void timerRemoved(Timer timer) {
                scheduleDataSync();
            }
        };
        dataModel.addTimerListener(mTimerListener);

        mStopwatchListener = after -> scheduleDataSync();
        dataModel.addStopwatchListener(mStopwatchListener);

        mCityListener = () -> scheduleDataSync();
        dataModel.addCityListener(mCityListener);

        mPrefsListener = (prefs, key) -> scheduleDataSync();
        DeskClockApplication.getDefaultSharedPreferences(context)
                .registerOnSharedPreferenceChangeListener(mPrefsListener);
    }

    private void unregisterDataChangeListeners() {
        mMainHandler.removeCallbacks(mDataChangedRunnable);
        if (mAlarmObserver != null) {
            mContext.getContentResolver().unregisterContentObserver(mAlarmObserver);
            mAlarmObserver = null;
        }
        if (mTimerListener != null) {
            DataModel.getDataModel().removeTimerListener(mTimerListener);
            mTimerListener = null;
        }
        if (mStopwatchListener != null) {
            DataModel.getDataModel().removeStopwatchListener(mStopwatchListener);
            mStopwatchListener = null;
        }
        if (mCityListener != null) {
            DataModel.getDataModel().removeCityListener(mCityListener);
            mCityListener = null;
        }
        if (mPrefsListener != null) {
            DeskClockApplication.getDefaultSharedPreferences(mContext)
                    .unregisterOnSharedPreferenceChangeListener(mPrefsListener);
            mPrefsListener = null;
        }
    }

    /**
     * Registers a listener notified (on the main thread) whenever the peer list or the connection
     * state changes.
     */
    public void setPeersListener(PeersListener listener) {
        mPeersListener = listener;
    }

    /**
     * Marks the settings screen as visible or hidden. While visible, every device (paired or not)
     * is searched and connected; otherwise only paired devices are auto-connected.
     */
    public void setSettingsScreenVisible(boolean visible) {
        mSettingsScreenVisible = visible;
        if (visible && mRunning) {
            // Reach out to every known device so the list and sync are fresh.
            final List<SyncPeerInfo> peers = SyncSettings.getPeers(mContext);
            for (SyncPeerInfo peer : peers) {
                mExecutor.execute(() -> syncWithPeer(peer, false));
            }
        }
        notifyPeersChanged();
    }

    /**
     * Called after the user pairs or unpairs a device so the engine can react immediately.
     */
    public void onPeerPairedChanged(String deviceId, boolean paired) {
        if (!paired && deviceId.equals(mConnectedDeviceId)) {
            mConnectedDeviceId = null;
            mLastPairedSyncMs = 0;
        }
        notifyPeersChanged();
    }

    /**
     * Immediately syncs with a specific peer, bypassing the rate limit. Used after pairing.
     */
    public void connectToPeer(SyncPeerInfo peer) {
        if (mRunning) {
            mExecutor.execute(() -> syncWithPeer(peer, true));
        }
    }

    public boolean isConnectedToPairedDevice() {
        return isConnected();
    }

    public String getConnectedDeviceId() {
        return mConnectedDeviceId;
    }

    // ---------------------------------------------------------------- server side

    private void acceptLoop() {
        while (mRunning) {
            final ServerSocket serverSocket = mServerSocket;
            if (serverSocket == null) {
                return;
            }
            try {
                final Socket socket = serverSocket.accept();
                mExecutor.execute(() -> handleServer(socket));
            } catch (IOException e) {
                if (mRunning) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
            }
        }
    }

    /**
     * Handles an inbound connection: read the remote state, merge it, reply with our state and
     * wait for the done message — the same sequence as the Windows server.
     */
    private void handleServer(Socket socket) {
        try (socket) {
            socket.setSoTimeout(READ_TIMEOUT_MS);
            final SyncModels.SyncSnapshot remote = SyncWire.readSnapshot(socket.getInputStream());
            if (remote == null || !"state".equals(remote.type)) {
                return;
            }
            Log.d("ClockSync", "handleServer received state from " + remote.deviceId + " at " + System.currentTimeMillis());
            SyncMerger.merge(mContext, remote);
            SyncWire.writeSnapshot(socket.getOutputStream(), SyncSnapshotBuilder.build(mContext));
            SyncWire.readDone(socket.getInputStream());
            if (remote.deviceId != null && SyncSettings.isPeerPaired(mContext, remote.deviceId)
                && shouldAdoptConnection(remote.deviceId)) {
                markConnected(remote.deviceId);
            }
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- client side

    @Override
    public void onPeerFound(SyncPeerInfo peer) {
        notifyPeersChanged();
        if (shouldConnect(peer)) {
            mExecutor.execute(() -> syncWithPeer(peer, false));
        }
    }

    /**
     * Decides whether an outbound connection to {@code peer} is allowed by the current search mode.
     */
    private boolean shouldConnect(SyncPeerInfo peer) {
        if (!mRunning) {
            return false;
        }
        if (peer.paired) {
            // Outside the settings screen, stay with a single connected paired device.
            return mSettingsScreenVisible || shouldAdoptConnection(peer.deviceId);
        }
        // Unpaired devices are only reachable from the settings screen.
        return mSettingsScreenVisible;
    }

    private boolean shouldAdoptConnection(String deviceId) {
        return mSettingsScreenVisible || !isConnected() || deviceId.equals(mConnectedDeviceId);
    }

    private boolean isConnected() {
        return mConnectedDeviceId != null
            && System.currentTimeMillis() - mLastPairedSyncMs < CONNECTED_TIMEOUT_MS;
    }

    /**
     * Connects to a peer and performs the state exchange, rate-limited per peer unless
     * {@code force} is set.
     */
    private void syncWithPeer(SyncPeerInfo peer, boolean force) {
        final String key = peer.deviceId + "@" + peer.address;
        final long now = System.currentTimeMillis();
        synchronized (mLastSyncByPeer) {
            final Long last = mLastSyncByPeer.get(key);
            if (!force && last != null && now - last < SYNC_RATE_LIMIT_MS) {
                return;
            }
            mLastSyncByPeer.put(key, now);
        }

        try {
            Log.d("ClockSync", "syncWithPeer connect to " + peer.address + ":" + peer.port + " at " + System.currentTimeMillis());
            final Socket socket = new Socket();
            socket.connect(new InetSocketAddress(InetAddress.getByName(peer.address), peer.port), CONNECT_TIMEOUT_MS);
            try (socket) {
                socket.setSoTimeout(READ_TIMEOUT_MS);
                SyncWire.writeSnapshot(socket.getOutputStream(), SyncSnapshotBuilder.build(mContext));
                final SyncModels.SyncSnapshot remote = SyncWire.readSnapshot(socket.getInputStream());
                if (remote != null && "state".equals(remote.type)) {
                    SyncMerger.merge(mContext, remote);
                }
                SyncWire.writeDone(socket.getOutputStream());
            }
            Log.d("ClockSync", "syncWithPeer done with " + peer.deviceId + " at " + System.currentTimeMillis());
            if (peer.paired && shouldAdoptConnection(peer.deviceId)) {
                markConnected(peer.deviceId);
            }
        } catch (IOException e) {
            Log.d("ClockSync", "syncWithPeer FAILED " + peer.deviceId + " at " + System.currentTimeMillis() + ": " + e);
            synchronized (mLastSyncByPeer) {
                mLastSyncByPeer.remove(key);
            }
        }
    }

    private void markConnected(String deviceId) {
        mConnectedDeviceId = deviceId;
        mLastPairedSyncMs = System.currentTimeMillis();
        notifyPeersChanged();
    }

    private void notifyPeersChanged() {
        mMainHandler.post(() -> {
            if (mPeersListener != null) {
                mPeersListener.onPeersChanged();
            }
        });
    }
}
