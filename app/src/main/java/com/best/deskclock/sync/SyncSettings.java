/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.best.deskclock.sync;

import android.content.Context;
import android.os.Build;

import com.best.deskclock.DeskClockApplication;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reads and writes the LAN sync configuration and the known peer list. All values live in the
 * application's default {@link SharedPreferences} so the settings screen can manage them.
 */
public final class SyncSettings {

    public static final int DEFAULT_PORT = 7846;

    public static final String KEY_SYNC_ENABLED = "sync_enabled";
    public static final String KEY_DEVICE_ID = "sync_device_id";
    public static final String KEY_DEVICE_NAME = "sync_device_name";
    public static final String KEY_PORT = "sync_port";
    public static final String KEY_PEERS = "sync_peers";

    private SyncSettings() {
    }

    public static boolean isSyncEnabled(Context context) {
        return DeskClockApplication.getDefaultSharedPreferences(context).getBoolean(KEY_SYNC_ENABLED, false);
    }

    public static void setSyncEnabled(Context context, boolean enabled) {
        DeskClockApplication.getDefaultSharedPreferences(context).edit().putBoolean(KEY_SYNC_ENABLED, enabled).apply();
    }

    /**
     * @return the stable id that identifies this device on the LAN; generated once and persisted.
     */
    public static String getDeviceId(Context context) {
        final android.content.SharedPreferences prefs = DeskClockApplication.getDefaultSharedPreferences(context);
        String id = prefs.getString(KEY_DEVICE_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    public static void setDeviceName(Context context, String name) {
        DeskClockApplication.getDefaultSharedPreferences(context).edit().putString(KEY_DEVICE_NAME, name).apply();
    }

    public static String getDeviceName(Context context) {
        return DeskClockApplication.getDefaultSharedPreferences(context)
            .getString(KEY_DEVICE_NAME, Build.MODEL);
    }

    public static int getPort(Context context) {
        return DeskClockApplication.getDefaultSharedPreferences(context).getInt(KEY_PORT, DEFAULT_PORT);
    }

    public static void setPort(Context context, int port) {
        DeskClockApplication.getDefaultSharedPreferences(context).edit().putInt(KEY_PORT, port).apply();
    }

    public static List<SyncPeerInfo> getPeers(Context context) {
        final List<SyncPeerInfo> peers = new ArrayList<>();
        final String json = DeskClockApplication.getDefaultSharedPreferences(context).getString(KEY_PEERS, null);
        if (json == null || json.isEmpty()) {
            return peers;
        }
        try {
            final JSONArray array = new JSONArray(json);
            final Set<String> seenIds = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                final JSONObject o = array.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                final SyncPeerInfo peer = SyncPeerInfo.fromJson(o);
                // Drop stale duplicates that can accumulate when a device re-registers
                // with a new id (e.g. app reinstall or data reset).
                if (peer.deviceId.isEmpty() || !seenIds.add(peer.deviceId)) {
                    continue;
                }
                peers.add(peer);
            }
        } catch (Exception ignored) {
        }
        return peers;
    }

    public static void savePeers(Context context, List<SyncPeerInfo> peers) {
        final JSONArray array = new JSONArray();
        for (SyncPeerInfo peer : peers) {
            array.put(peer.toJson());
        }
        DeskClockApplication.getDefaultSharedPreferences(context)
            .edit().putString(KEY_PEERS, array.toString()).apply();
    }

    public static SyncPeerInfo getPeer(Context context, String deviceId) {
        for (SyncPeerInfo peer : getPeers(context)) {
            if (peer.deviceId.equals(deviceId)) {
                return peer;
            }
        }
        return null;
    }

    public static List<SyncPeerInfo> getPairedPeers(Context context) {
        final List<SyncPeerInfo> paired = new ArrayList<>();
        for (SyncPeerInfo peer : getPeers(context)) {
            if (peer.paired) {
                paired.add(peer);
            }
        }
        return paired;
    }

    public static boolean isPeerPaired(Context context, String deviceId) {
        final SyncPeerInfo peer = getPeer(context, deviceId);
        return peer != null && peer.paired;
    }

    public static void setPeerPaired(Context context, String deviceId, boolean paired) {
        final List<SyncPeerInfo> peers = getPeers(context);
        boolean changed = false;
        for (SyncPeerInfo peer : peers) {
            if (peer.deviceId.equals(deviceId)) {
                peer.paired = paired;
                changed = true;
                break;
            }
        }
        if (changed) {
            savePeers(context, peers);
        }
    }

    public static void removePeer(Context context, String deviceId) {
        final List<SyncPeerInfo> peers = getPeers(context);
        boolean changed = false;
        for (int i = peers.size() - 1; i >= 0; i--) {
            if (peers.get(i).deviceId.equals(deviceId)) {
                peers.remove(i);
                changed = true;
            }
        }
        if (changed) {
            savePeers(context, peers);
        }
    }
}
