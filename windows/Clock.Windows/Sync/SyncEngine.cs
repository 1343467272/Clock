using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using Clock.Windows.Data;
using Clock.Windows.Models;

namespace Clock.Windows.Sync;

/// <summary>
/// P2P sync engine. Runs a TCP server on Settings.SyncPort and connects to discovered peers.
/// A full state snapshot is exchanged on every connection; both sides merge (LWW).
/// </summary>
public class SyncEngine : IDisposable
{
    private static readonly string TracePath =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Clock", "synctrace.log");

    private static void Trace(string message)
    {
        try { File.AppendAllText(TracePath, $"{DateTime.Now:HH:mm:ss.fff} {message}\n"); } catch { }
    }
    /// <summary>A paired peer is considered connected while its last successful sync is fresher than this.</summary>
    private const long ConnectedTimeoutMs = 60_000;

    /// <summary>How long to wait after the last local change before pushing a snapshot to peers.</summary>
    private static readonly TimeSpan PushDebounce = TimeSpan.FromMilliseconds(400);

    private readonly AppState _state;
    private readonly SyncDiscovery _discovery;
    private readonly CancellationTokenSource _cts = new();
    private TcpListener? _listener;
    private readonly Dictionary<string, DateTime> _lastSyncByPeer = new();
    private readonly object _connectedLock = new();
    private string? _connectedDeviceId;
    private DateTime _lastConnectedAt = DateTime.MinValue;
    private volatile bool _settingsPanelVisible;
    private readonly object _pushLock = new();
    private int _pushGeneration;

    public event Action? PeersChanged;
    public event Action? Synced;

    /// <summary>Whether a paired peer is currently connected (its last sync is fresh).</summary>
    public bool IsConnectedToPairedDevice
    {
        get
        {
            lock (_connectedLock)
            {
                return _connectedDeviceId != null
                    && (DateTime.UtcNow - _lastConnectedAt).TotalMilliseconds < ConnectedTimeoutMs;
            }
        }
    }

    /// <summary>Device id of the currently connected paired peer, if any.</summary>
    public string? ConnectedDeviceId
    {
        get { lock (_connectedLock) return _connectedDeviceId; }
    }

    public SyncEngine(AppState state)
    {
        _state = state;
        _discovery = new SyncDiscovery(state);
        _discovery.PeersChanged += () =>
        {
            PeersChanged?.Invoke();
            TryConnectToNewPeers();
        };
        _state.UserChanged += OnLocalDataChanged;
    }

    public void Start()
    {
        _discovery.Start();
        try
        {
            _listener = new TcpListener(IPAddress.Any, _state.Settings.SyncPort);
            _listener.Start();
            var t = new Thread(AcceptLoop) { IsBackground = true };
            t.Start();
        }
        catch
        {
            // Port in use — sync outbound still works.
        }

        // Auto-connect to devices that were already paired.
        TryConnectToNewPeers();
    }

    /// <summary>
    /// Marks the settings screen as visible or hidden. While visible, every device (paired or not)
    /// is connected so unpaired devices can be discovered and paired; otherwise only paired devices
    /// are auto-connected.
    /// </summary>
    public void SetSettingsScreenVisible(bool visible)
    {
        _settingsPanelVisible = visible;
        if (visible)
        {
            TryConnectToNewPeers();
        }
        PeersChanged?.Invoke();
    }

    /// <summary>Called after the user pairs or unpairs a device so the engine can react immediately.</summary>
    public void OnPeerPairedChanged(string deviceId, bool paired)
    {
        if (!paired)
        {
            lock (_connectedLock)
            {
                if (deviceId == _connectedDeviceId)
                {
                    _connectedDeviceId = null;
                    _lastConnectedAt = DateTime.MinValue;
                }
            }
        }
        PeersChanged?.Invoke();
    }

    /// <summary>Immediately syncs with a specific peer, bypassing the rate limit. Used after pairing.</summary>
    public void ConnectToPeer(SyncPeerInfo peer) => _ = SyncWithPeerAsync(peer.DeviceId, peer.Address, peer.Port, force: true);

    /// <summary>Immediately syncs with every known peer, bypassing the rate limit.</summary>
    public void SyncAllNow()
    {
        List<SyncPeerInfo> peers;
        lock (_state)
        {
            peers = _state.Peers.ToList();
        }
        foreach (var peer in peers)
        {
            _ = SyncWithPeerAsync(peer.DeviceId, peer.Address, peer.Port, force: true);
        }
    }

    /// <summary>Connects to every peer the current search mode allows.</summary>
    private void TryConnectToNewPeers()
    {
        List<SyncPeerInfo> peers;
        lock (_state)
        {
            peers = _state.Peers.Where(ShouldConnect).ToList();
        }
        foreach (var peer in peers)
        {
            _ = SyncWithPeerAsync(peer.DeviceId, peer.Address, peer.Port);
        }
    }

    /// <summary>Decides whether an outbound connection to a peer is allowed by the current search mode.</summary>
    private bool ShouldConnect(SyncPeerInfo peer)
    {
        if (peer.Paired)
        {
            // Outside the settings screen, stay with a single connected paired device.
            return _settingsPanelVisible || ShouldAdopt(peer.DeviceId);
        }
        // Unpaired devices are only reachable from the settings screen.
        return _settingsPanelVisible;
    }

    private bool ShouldAdopt(string deviceId)
    {
        return _settingsPanelVisible || !IsConnectedToPairedDevice || deviceId == ConnectedDeviceId;
    }

    private bool IsPaired(string deviceId)
    {
        lock (_state)
        {
            return _state.Peers.Any(p => p.DeviceId == deviceId && p.Paired);
        }
    }

    private void MarkConnected(string deviceId)
    {
        lock (_connectedLock)
        {
            _connectedDeviceId = deviceId;
            _lastConnectedAt = DateTime.UtcNow;
        }
        PeersChanged?.Invoke();
    }

    /// <summary>
    /// Local data changed: schedule a debounced push so a burst of edits collapses into one sync.
    /// Remote-applied changes are suppressed by AppState and never reach here.
    /// </summary>
    private void OnLocalDataChanged()
    {
        Trace("OnLocalDataChanged at " + DateTime.Now.ToString("HH:mm:ss.fff"));
        int generation;
        lock (_pushLock)
        {
            generation = ++_pushGeneration;
        }

        _ = Task.Run(async () =>
        {
            await Task.Delay(PushDebounce).ConfigureAwait(false);
            lock (_pushLock)
            {
                if (generation != _pushGeneration) return; // a newer change superseded this push
            }
            PushToPeers();
        });
    }

    /// <summary>Pushes the current local snapshot to every peer the search mode allows.</summary>
    private void PushToPeers()
    {
        List<SyncPeerInfo> peers;
        lock (_state)
        {
            peers = _state.Peers.Where(ShouldConnect).ToList();
        }
        foreach (var peer in peers)
        {
            _ = SyncWithPeerAsync(peer.DeviceId, peer.Address, peer.Port, force: true);
        }
    }

    private void AcceptLoop()
    {
        while (!_cts.IsCancellationRequested)
        {
            try
            {
                var client = _listener!.AcceptTcpClient();
                var t = new Thread(() => HandleServer(client)) { IsBackground = true };
                t.Start();
            }
            catch
            {
                break;
            }
        }
    }

    /// <summary>Handles an inbound connection: read remote state, merge, reply with our state.</summary>
    private void HandleServer(TcpClient client)
    {
        try
        {
            using (client)
            using (var stream = client.GetStream())
            {
                Trace("HandleServer: start");
                var remote = ReadMessage<SyncSnapshot>(stream);
                if (remote == null || remote.Type != "state") { Trace("HandleServer: bad msg"); return; }

                Trace($"HandleServer: merging from {remote.DeviceId} at {DateTime.Now:HH:mm:ss.fff}");
                SyncMerge.ApplySnapshot(_state, remote);

                var ours = SyncMerge.BuildSnapshot(_state);
                WriteMessage(stream, ours);
                ReadMessage<DoneMessage>(stream);

                if (IsPaired(remote.DeviceId) && ShouldAdopt(remote.DeviceId))
                {
                    MarkConnected(remote.DeviceId);
                }
                Trace("HandleServer: done");
            }
        }
        catch (Exception ex)
        {
            Trace($"HandleServer: ERROR {ex.Message}");
        }
        finally
        {
            Synced?.Invoke();
        }
    }

    /// <summary>Connects to a peer and performs the state exchange, rate-limited per peer unless <paramref name="force"/> is set.</summary>
    public async Task<bool> SyncWithPeerAsync(string deviceId, string address, int port, bool force = false)
    {
        var key = deviceId + "@" + address;
        lock (_lastSyncByPeer)
        {
            if (!force && _lastSyncByPeer.TryGetValue(key, out var last) && (DateTime.UtcNow - last).TotalSeconds < 20)
            {
                return false; // rate limit
            }
            _lastSyncByPeer[key] = DateTime.UtcNow;
        }

        try
        {
            Trace($"SyncWithPeerAsync: connect {deviceId}@{address}:{port} at {DateTime.Now:HH:mm:ss.fff}");
            using var client = new TcpClient();
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            await client.ConnectAsync(IPAddress.Parse(address), port, cts.Token);

            await using var stream = client.GetStream();
            var ours = SyncMerge.BuildSnapshot(_state);
            WriteMessage(stream, ours);

            var remote = ReadMessage<SyncSnapshot>(stream);
            if (remote != null && remote.Type == "state")
            {
                SyncMerge.ApplySnapshot(_state, remote);
            }

            WriteMessage(stream, new DoneMessage());

            if (IsPaired(deviceId) && ShouldAdopt(deviceId))
            {
                MarkConnected(deviceId);
            }
            Trace($"SyncWithPeerAsync: done {deviceId} at {DateTime.Now:HH:mm:ss.fff}");
            return true;
        }
        catch (Exception ex)
        {
            Trace($"SyncWithPeerAsync: ERROR {deviceId}: {ex.Message}");
            lock (_lastSyncByPeer) { _lastSyncByPeer.Remove(key); }
            return false;
        }
        finally
        {
            Synced?.Invoke();
        }
    }

    /// <summary>Called when a peer appears/disappears to trigger a fresh sync.</summary>
    public void PeerDiscovered(SyncPeerInfo peer) => _ = SyncWithPeerAsync(peer.DeviceId, peer.Address, peer.Port);

    private static void WriteMessage<T>(Stream stream, T message)
    {
        var json = SyncWire.Serialize(message);
        var bytes = Encoding.UTF8.GetBytes(json);
        var len = BitConverter.GetBytes(IPAddress.HostToNetworkOrder(bytes.Length));
        stream.Write(len, 0, 4);
        stream.Write(bytes, 0, bytes.Length);
        stream.Flush();
    }

    private static T? ReadMessage<T>(Stream stream)
    {
        var lenBuf = new byte[4];
        if (!ReadExact(stream, lenBuf, 4)) return default;
        var len = IPAddress.NetworkToHostOrder(BitConverter.ToInt32(lenBuf, 0));
        if (len < 0 || len > 10 * 1024 * 1024) return default;

        var buf = new byte[len];
        if (!ReadExact(stream, buf, len)) return default;

        var json = Encoding.UTF8.GetString(buf);
        return SyncWire.Deserialize<T>(json);
    }

    private static bool ReadExact(Stream stream, byte[] buffer, int count)
    {
        var offset = 0;
        while (offset < count)
        {
            var read = stream.Read(buffer, offset, count - offset);
            if (read <= 0) return false;
            offset += read;
        }
        return true;
    }

    public void Dispose()
    {
        _state.UserChanged -= OnLocalDataChanged;
        _cts.Cancel();
        _discovery.Dispose();
        try { _listener?.Stop(); } catch { }
    }

    private class DoneMessage
    {
        public string Type { get; set; } = "done";
    }
}
