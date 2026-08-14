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
    private readonly AppState _state;
    private readonly SyncDiscovery _discovery;
    private readonly CancellationTokenSource _cts = new();
    private TcpListener? _listener;
    private readonly Dictionary<string, DateTime> _lastSyncByPeer = new();

    public event Action? PeersChanged;
    public event Action? Synced;

    public SyncEngine(AppState state)
    {
        _state = state;
        _discovery = new SyncDiscovery(state);
        _discovery.PeersChanged += () => PeersChanged?.Invoke();
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
                var remote = ReadMessage<SyncSnapshot>(stream);
                if (remote == null || remote.Type != "state") return;

                SyncMerge.ApplySnapshot(_state, remote);

                var ours = SyncMerge.BuildSnapshot(_state);
                WriteMessage(stream, ours);
                ReadMessage<DoneMessage>(stream);
            }
        }
        catch
        {
            // ignore
        }
        finally
        {
            Synced?.Invoke();
        }
    }

    /// <summary>Connects to a peer and performs the state exchange.</summary>
    public async Task<bool> SyncWithPeerAsync(string deviceId, string address, int port)
    {
        var key = deviceId + "@" + address;
        lock (_lastSyncByPeer)
        {
            if (_lastSyncByPeer.TryGetValue(key, out var last) && (DateTime.UtcNow - last).TotalSeconds < 20)
            {
                return false; // rate limit
            }
            _lastSyncByPeer[key] = DateTime.UtcNow;
        }

        try
        {
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
            return true;
        }
        catch
        {
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
        _cts.Cancel();
        _discovery.Dispose();
        try { _listener?.Stop(); } catch { }
    }

    private class DoneMessage
    {
        public string Type { get; set; } = "done";
    }
}
