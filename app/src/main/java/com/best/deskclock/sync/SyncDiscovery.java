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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LAN peer discovery via UDP, mirroring the Windows {@code SyncDiscovery}. Every
 * {@value #ANNOUNCE_INTERVAL_MS} ms a hello datagram is sent to the multicast group plus the local
 * broadcast addresses; every {@value #SUBNET_SWEEP_INTERVAL_MS} ms the local subnet is also probed
 * host by host. The broadcast and unicast sweeps are fallbacks for routers that do not forward
 * multicast between Wi-Fi and Ethernet. Incoming hellos update the persisted peer list and notify
 * the {@link PeerListener}.
 */
public final class SyncDiscovery {

    static final String GROUP = "239.255.43.21";
    static final int PORT = 4799;
    private static final long ANNOUNCE_INTERVAL_MS = 10_000;
    private static final long SUBNET_SWEEP_INTERVAL_MS = 30_000;
    private static final int MAX_PROBE_HOSTS = 256;

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
            mSender.setBroadcast(true);
            mSender.joinGroup(InetAddress.getByName(GROUP));
        } catch (IOException e) {
            mSender = null; // multicast unavailable; announcements fail silently
        }
    }

    private void announceLoop() {
        final byte[] hello = helloJson().getBytes(StandardCharsets.UTF_8);
        long lastSweepMs = 0;
        while (mRunning) {
            try {
                sendHello(hello);
            } catch (IOException ignored) {
            }
            final long now = System.currentTimeMillis();
            if (now - lastSweepMs >= SUBNET_SWEEP_INTERVAL_MS) {
                lastSweepMs = now;
                probeSubnets(hello);
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
        // Broadcast fallback for routers that drop multicast between Wi-Fi and Ethernet.
        try {
            sender.send(new DatagramPacket(hello, hello.length, InetAddress.getByName("255.255.255.255"), PORT));
        } catch (IOException ignored) {
        }
        for (Subnet subnet : localSubnets()) {
            try {
                sender.send(new DatagramPacket(hello, hello.length, addressOf(subnet.broadcast), PORT));
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Unicasts the hello to every host in the local subnets. This is the most reliable fallback:
     * unlike multicast and broadcast it only needs plain unicast routing, which virtually every
     * network provides even when AP isolation or multicast filtering is on.
     */
    private void probeSubnets(byte[] hello) {
        final MulticastSocket sender = mSender;
        if (sender == null) {
            return;
        }
        for (Subnet subnet : localSubnets()) {
            final int firstHost = subnet.network + 1;
            final int lastHost = subnet.broadcast - 1;
            if (lastHost - firstHost + 1 > MAX_PROBE_HOSTS) {
                continue;
            }
            for (int address = firstHost; address <= lastHost; address++) {
                try {
                    sender.send(new DatagramPacket(hello, hello.length, addressOf(address), PORT));
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ---------------------------------------------------------------- subnets

    private static final class Subnet {
        final int network;
        final int broadcast;

        Subnet(int network, int broadcast) {
            this.network = network;
            this.broadcast = broadcast;
        }
    }

    /**
     * Collects the small (at most {@value #MAX_PROBE_HOSTS} hosts) IPv4 subnets of every up,
     * non-loopback network interface. Larger ranges are skipped so mobile-data interfaces with
     * huge address pools never trigger an expensive scan.
     */
    private List<Subnet> localSubnets() {
        final List<Subnet> subnets = new ArrayList<>();
        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                    final InetAddress address = interfaceAddress.getAddress();
                    if (!(address instanceof Inet4Address)) {
                        continue;
                    }
                    final int prefix = interfaceAddress.getNetworkPrefixLength() & 0x1f;
                    final int mask = prefix == 0 ? 0 : ~((1 << (32 - prefix)) - 1);
                    final int network = toInt(address.getAddress()) & mask;
                    final int broadcast = network | ~mask;
                    if (broadcast - network + 1 > MAX_PROBE_HOSTS + 2) {
                        continue;
                    }
                    subnets.add(new Subnet(network, broadcast));
                }
            }
        } catch (IOException ignored) {
        }
        return subnets;
    }

    private static int toInt(byte[] address) {
        return ((address[0] & 0xff) << 24)
            | ((address[1] & 0xff) << 16)
            | ((address[2] & 0xff) << 8)
            | (address[3] & 0xff);
    }

    private static Inet4Address addressOf(int value) throws IOException {
        return (Inet4Address) InetAddress.getByAddress(new byte[]{
            (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
        });
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
            mListener = new MulticastSocket(null);
            mListener.setReuseAddress(true);
            mListener.setBroadcast(true);
            mListener.bind(new InetSocketAddress(PORT));
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
                    final SyncPeerInfo stored = peers.get(i);
                    if (stored.deviceId.equals(deviceId)) {
                        peer.paired = stored.paired;
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
