/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.best.deskclock.sync;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A peer (device) discovered on the LAN. Mirrors the Windows {@code SyncPeerInfo} wire shape.
 */
public final class SyncPeerInfo {

    public String deviceId;
    public String deviceName;
    public String address;
    public int port;
    public long lastSeen; // epoch ms

    public SyncPeerInfo() {
    }

    public SyncPeerInfo(String deviceId, String deviceName, String address, int port, long lastSeen) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.address = address;
        this.port = port;
        this.lastSeen = lastSeen;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("deviceId", deviceId);
            o.put("deviceName", deviceName);
            o.put("address", address);
            o.put("port", port);
            o.put("lastSeen", lastSeen);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static SyncPeerInfo fromJson(JSONObject o) {
        SyncPeerInfo p = new SyncPeerInfo();
        p.deviceId = o.optString("deviceId", "");
        p.deviceName = o.optString("deviceName", "");
        p.address = o.optString("address", "");
        p.port = o.optInt("port", 0);
        p.lastSeen = o.optLong("lastSeen", 0);
        return p;
    }
}
