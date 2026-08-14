/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.best.deskclock.sync;

import android.content.Context;

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
 */
public final class SyncEngine implements SyncDiscovery.PeerListener {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final long SYNC_RATE_LIMIT_MS = 20_000;

    private final Context mContext;
    private final SyncDiscovery mDiscovery;
    private final ExecutorService mExecutor;
    private final Map<String, Long> mLastSyncByPeer = new HashMap<>();

    private volatile boolean mRunning;
    private volatile ServerSocket mServerSocket;
    private volatile Thread mAcceptThread;

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

        // Sync with any peers that are already known.
        final List<SyncPeerInfo> peers = SyncSettings.getPeers(mContext);
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
     * Immediately syncs with every known peer, bypassing the per-peer rate limit.
     */
    public void syncNow() {
        final List<SyncPeerInfo> peers = SyncSettings.getPeers(mContext);
        for (SyncPeerInfo peer : peers) {
            mExecutor.execute(() -> syncWithPeer(peer, true));
        }
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
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- client side

    @Override
    public void onPeerFound(SyncPeerInfo peer) {
        mExecutor.execute(() -> syncWithPeer(peer, false));
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
        } catch (IOException e) {
            synchronized (mLastSyncByPeer) {
                mLastSyncByPeer.remove(key);
            }
        }
    }
}
