/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.best.deskclock.sync;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * LAN peer discovery via UDP multicast, mirroring the Windows {@code SyncDiscovery}.
 * Every {@value #ANNOUNCE_INTERVAL_MS} ms a hello datagram is broadcast to the multicast group;
 * incoming hellos update the persisted peer list and notify the {@link PeerListener}.
 */
public final class SyncDiscovery {

    static final String GROUP = "239.255.43.21";
    static final int PORT = 4799;
    private static final long ANNOUNCE_INTERVAL_MS = 10_000;

    public interface PeerListener {
        void onPeerFound(SyncPeerInfo peer);
    }

    private final Context mContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean mRunning;
    private volatile MulticastSocket mSender;
    private volatile MulticastSocket mListener;
    private Thread mListenThread;
    private Thread mAnnounceThread;
    private WifiManager.MulticastLock mMulticastLock;
    private PeerListener mPeerListener;

    public SyncDiscovery(Context context) {
        mContext = context.getApplicationContext();
    }

    public void setPeerListener(PeerListener listener) {
        mPeerListener = listener;
    }

    public void start() {
        if (mRunning) {
            return;
        }
        mRunning = true;
        acquireMulticastLock();
        startSender();
        startListener();
        mAnnounceThread = new Thread(this::announceLoop, "sync-discovery-announce");
        mAnnounceThread.start();
    }

    public void stop() {
        mRunning = false;
        if (mListener != null) {
            mListener.close();
        }
        if (mSender != null) {
            mSender.close();
        }
        if (mMulticastLock != null) {
            try {
                mMulticastLock.release();
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- sender

    private void startSender() {
        try {
            mSender = new MulticastSocket();
            mSender.setTimeToLive(1);
            mSender.joinGroup(InetAddress.getByName(GROUP));
        } catch (IOException e) {
            mSender = null; // multicast unavailable; announcements fail silently
        }
    }

    private void announceLoop() {
        final byte[] hello = helloJson().getBytes(StandardCharsets.UTF_8);
        while (mRunning) {
            try {
                sendHello(hello);
            } catch (IOException ignored) {
            }
            try {
                Thread.sleep(ANNOUNCE_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void sendHello(byte[] hello) throws IOException {
        final MulticastSocket sender = mSender;
        if (sender == null) {
            return;
        }
        sender.send(new DatagramPacket(hello, hello.length, InetAddress.getByName(GROUP), PORT));
    }

    private String helloJson() {
        try {
            final JSONObject o = new JSONObject();
            o.put("type", "hello");
            o.put("deviceId", SyncSettings.getDeviceId(mContext));
            o.put("deviceName", SyncSettings.getDeviceName(mContext));
            o.put("port", SyncSettings.getPort(mContext));
            o.put("version", 1);
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    // ---------------------------------------------------------------- listener

    private void startListener() {
        try {
            mListener = new MulticastSocket(PORT);
            mListener.setReuseAddress(true);
            mListener.joinGroup(InetAddress.getByName(GROUP));
        } catch (IOException e) {
            mListener = null;
        }
        mListenThread = new Thread(this::listenLoop, "sync-discovery-listen");
        mListenThread.start();
    }

    private void listenLoop() {
        final byte[] buffer = new byte[1024];
        while (mRunning) {
            final MulticastSocket socket = mListener;
            if (socket == null) {
                return;
            }
            try {
                final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                final String json = new String(packet.getData(), packet.getOffset(), packet.getLength(),
                    StandardCharsets.UTF_8);
                handleHello(json, packet.getAddress());
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

    private void handleHello(String json, final InetAddress address) {
        try {
            final JSONObject o = new JSONObject(json);
            if (!"hello".equals(o.optString("type"))) {
                return;
            }
            final String deviceId = o.optString("deviceId", "");
            if (deviceId.isEmpty() || deviceId.equals(SyncSettings.getDeviceId(mContext))) {
                return;
            }
            final SyncPeerInfo peer = new SyncPeerInfo(
                deviceId,
                o.optString("deviceName", ""),
                address.getHostAddress(),
                o.optInt("port", SyncSettings.DEFAULT_PORT),
                System.currentTimeMillis()
            );
            mMainHandler.post(() -> {
                if (!mRunning) {
                    return;
                }
                final List<SyncPeerInfo> peers = SyncSettings.getPeers(mContext);
                boolean found = false;
                for (int i = 0; i < peers.size(); i++) {
                    if (peers.get(i).deviceId.equals(deviceId)) {
                        peers.set(i, peer);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    peers.add(peer);
                }
                SyncSettings.savePeers(mContext, peers);
                if (mPeerListener != null) {
                    mPeerListener.onPeerFound(peer);
                }
            });
        } catch (JSONException ignored) {
        }
    }

    // ---------------------------------------------------------------- multicast lock

    private void acquireMulticastLock() {
        try {
            final WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                mMulticastLock = wifiManager.createMulticastLock("deskclock-sync");
                mMulticastLock.setReferenceCounted(false);
                mMulticastLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }
}
