/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.best.deskclock.settings;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.best.deskclock.R;
import com.best.deskclock.databinding.LanSyncPeerItemBinding;
import com.best.deskclock.sync.SyncPeerInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists the LAN sync peers (paired and unpaired) with a pair/unpair action per device.
 */
public final class LanSyncPeersAdapter extends RecyclerView.Adapter<LanSyncPeersAdapter.PeerViewHolder> {

    public interface PairClickListener {
        void onPairClicked(SyncPeerInfo peer);
    }

    public interface DeleteClickListener {
        void onDeleteClicked(SyncPeerInfo peer);
    }

    private final List<SyncPeerInfo> mPeers = new ArrayList<>();
    private final PairClickListener mPairClickListener;
    private final DeleteClickListener mDeleteClickListener;
    private String mConnectedDeviceId;

    public LanSyncPeersAdapter(PairClickListener pairClickListener, DeleteClickListener deleteClickListener) {
        mPairClickListener = pairClickListener;
        mDeleteClickListener = deleteClickListener;
    }

    @Override
    public void onBindViewHolder(@NonNull PeerViewHolder holder, int position) {
        final Context context = holder.binding.getRoot().getContext();
        final SyncPeerInfo peer = mPeers.get(position);

        holder.binding.peerName.setText(peer.deviceName);
        holder.binding.peerInfo.setText(buildInfo(context, peer));

        if (peer.paired) {
            holder.binding.pairButton.setText(context.getString(R.string.lan_sync_unpair));
        } else {
            holder.binding.pairButton.setText(context.getString(R.string.lan_sync_pair));
        }
        holder.binding.pairButton.setOnClickListener(v -> mPairClickListener.onPairClicked(peer));
        holder.binding.deleteButton.setOnClickListener(v -> mDeleteClickListener.onDeleteClicked(peer));
    }

    @NonNull
    @Override
    public PeerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final LanSyncPeerItemBinding binding = LanSyncPeerItemBinding.inflate(
            LayoutInflater.from(parent.getContext()), parent, false);
        return new PeerViewHolder(binding);
    }

    @Override
    public int getItemCount() {
        return mPeers.size();
    }

    public void update(List<SyncPeerInfo> peers, String connectedDeviceId) {
        mPeers.clear();
        mPeers.addAll(peers);
        mConnectedDeviceId = connectedDeviceId;
        notifyDataSetChanged();
    }

    private String buildInfo(Context context, SyncPeerInfo peer) {
        final String status;
        if (peer.deviceId.equals(mConnectedDeviceId)) {
            status = context.getString(R.string.lan_sync_connected);
        } else if (peer.paired) {
            status = context.getString(R.string.lan_sync_paired);
        } else {
            status = context.getString(R.string.lan_sync_unpaired);
        }
        return peer.address + ":" + peer.port + " \u00b7 " + status;
    }

    static final class PeerViewHolder extends RecyclerView.ViewHolder {

        final LanSyncPeerItemBinding binding;

        PeerViewHolder(@NonNull LanSyncPeerItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
