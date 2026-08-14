/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.best.deskclock.sync;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

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
            if (peer.paired && shouldAdoptConnection(peer.deviceId)) {
                markConnected(peer.deviceId);
            }
        } catch (IOException e) {
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
