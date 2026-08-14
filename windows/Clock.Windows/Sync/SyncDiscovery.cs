using System.Net;
using System.Net.Sockets;
using System.Text;
using Clock.Windows.Data;
using Clock.Windows.Models;

namespace Clock.Windows.Sync;

/// <summary>
/// LAN peer discovery via UDP multicast. Peers announce themselves periodically;
/// incoming hellos update the peer list in AppState.
/// </summary>
public class SyncDiscovery : IDisposable
{
    public const string Group = "239.255.43.21";
    public const int Port = 4799;

    private readonly AppState _state;
    private readonly CancellationTokenSource _cts = new();
    private UdpClient? _sender;
    private UdpClient? _listener;
    private Thread? _listenThread;

    public event Action? PeersChanged;

    public SyncDiscovery(AppState state)
    {
        _state = state;
    }

    public void Start()
    {
        try
        {
            _sender = new UdpClient();
            _sender.EnableBroadcast = true;
            _sender.JoinMulticastGroup(IPAddress.Parse(Group));
        }
        catch
        {
            // Multicast unavailable (e.g. no network) — announcements just fail silently.
        }

        try
        {
            _listener = new UdpClient();
            _listener.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            _listener.Client.Bind(new IPEndPoint(IPAddress.Any, Port));
            _listener.JoinMulticastGroup(IPAddress.Parse(Group));
            _listenThread = new Thread(ListenLoop) { IsBackground = true };
            _listenThread.Start();
        }
        catch
        {
            // Listening failed; discovery is disabled but sync still works for manually added peers.
        }

        var _ = Task.Run(AnnounceLoopAsync);
    }

    private async Task AnnounceLoopAsync()
    {
        var hello = SyncWire.Serialize(new HelloMessage
        {
            DeviceId = _state.Settings.DeviceId,
            DeviceName = _state.Settings.SyncDeviceName,
            Port = _state.Settings.SyncPort,
            Version = 1,
        });
        var bytes = Encoding.UTF8.GetBytes(hello);

        while (!_cts.IsCancellationRequested)
        {
            try
            {
                _sender?.Send(bytes, bytes.Length, new IPEndPoint(IPAddress.Parse(Group), Port));
            }
            catch
            {
                // ignore
            }
            try
            {
                await Task.Delay(TimeSpan.FromSeconds(10), _cts.Token);
            }
            catch (OperationCanceledException) { break; }
        }
    }

    private void ListenLoop()
    {
        var remoteEp = new IPEndPoint(IPAddress.Any, 0);
        while (!_cts.IsCancellationRequested)
        {
            try
            {
                var data = _listener?.Receive(ref remoteEp);
                if (data == null || data.Length == 0) continue;

                var json = Encoding.UTF8.GetString(data);
                var hello = SyncWire.Deserialize<HelloMessage>(json);
                if (hello == null || hello.Type != "hello") continue;
                if (string.IsNullOrEmpty(hello.DeviceId) || hello.DeviceId == _state.Settings.DeviceId) continue;

                var address = remoteEp.Address.ToString();
                lock (_state)
                {
                    var existing = _state.Peers.FirstOrDefault(p => p.DeviceId == hello.DeviceId);
                    if (existing == null)
                    {
                        _state.Peers.Add(new SyncPeerInfo
                        {
                            DeviceId = hello.DeviceId,
                            DeviceName = hello.DeviceName,
                            Address = address,
                            Port = hello.Port,
                            LastSeen = DateTime.UtcNow,
                        });
                    }
                    else
                    {
                        existing.DeviceName = hello.DeviceName;
                        existing.Address = address;
                        existing.Port = hello.Port;
                        existing.LastSeen = DateTime.UtcNow;
                    }
                }
                _state.Save();
                PeersChanged?.Invoke();
            }
            catch
            {
                // ignore
            }
        }
    }

    public void Dispose()
    {
        _cts.Cancel();
        try { _listener?.Dispose(); } catch { }
        try { _sender?.Dispose(); } catch { }
    }

    private class HelloMessage
    {
        public string Type { get; set; } = "hello";
        public string DeviceId { get; set; } = "";
        public string DeviceName { get; set; } = "";
        public int Port { get; set; }
        public int Version { get; set; }
    }
}
