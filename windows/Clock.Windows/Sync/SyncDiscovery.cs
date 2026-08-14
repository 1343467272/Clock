using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using Clock.Windows.Data;
using Clock.Windows.Models;

namespace Clock.Windows.Sync;

/// <summary>
/// LAN peer discovery via UDP. Peers announce themselves periodically via multicast, broadcast and
/// a per-host subnet sweep (fallbacks for routers that do not forward multicast between Wi-Fi and
/// Ethernet); incoming hellos update the peer list in AppState.
/// </summary>
public class SyncDiscovery : IDisposable
{
    public const string Group = "239.255.43.21";
    public const int Port = 4799;

    private static readonly TimeSpan AnnounceInterval = TimeSpan.FromSeconds(10);
    private static readonly TimeSpan SweepInterval = TimeSpan.FromSeconds(30);
    private const int MaxProbeHosts = 256;

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
            _listener.EnableBroadcast = true; // receive broadcast-fallback hellos too
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
        var group = new IPEndPoint(IPAddress.Parse(Group), Port);
        var limitedBroadcast = new IPEndPoint(IPAddress.Broadcast, Port);

        var lastSweep = DateTime.UtcNow - SweepInterval;
        while (!_cts.IsCancellationRequested)
        {
            SendHello(bytes, group);
            SendHello(bytes, limitedBroadcast);
            foreach (var broadcast in LocalSubnets().Select(s => new IPEndPoint(s.Broadcast, Port)))
            {
                SendHello(bytes, broadcast);
            }

            if ((DateTime.UtcNow - lastSweep) >= SweepInterval)
            {
                lastSweep = DateTime.UtcNow;
                ProbeSubnets(bytes);
            }

            try
            {
                await Task.Delay(AnnounceInterval, _cts.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) { break; }
        }
    }

    private void SendHello(byte[] hello, IPEndPoint destination)
    {
        try
        {
            _sender?.Send(hello, hello.Length, destination);
        }
        catch
        {
            // ignore
        }
    }

    /// <summary>
    /// Unicasts the hello to every host in the local subnets. The most reliable fallback: it only
    /// needs plain unicast routing, which works even when multicast/broadcast is filtered.
    /// </summary>
    private void ProbeSubnets(byte[] hello)
    {
        foreach (var subnet in LocalSubnets())
        {
            var firstHost = ToLong(subnet.Network.GetAddressBytes()) + 1;
            var lastHost = ToLong(subnet.Broadcast.GetAddressBytes()) - 1;
            if (lastHost - firstHost + 1 > MaxProbeHosts) continue;
            for (var address = firstHost; address <= lastHost; address++)
            {
                SendHello(hello, new IPEndPoint(FromLong(address), Port));
            }
        }
    }

    private readonly record struct Subnet(IPAddress Network, IPAddress Broadcast);

    /// <summary>
    /// Collects the small (≤ <see cref="MaxProbeHosts"/> hosts) IPv4 subnets of every up,
    /// non-loopback interface. Larger ranges are skipped so mobile-data interfaces with huge
    /// address pools never trigger an expensive scan.
    /// </summary>
    private static IEnumerable<Subnet> LocalSubnets()
    {
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up) continue;
            if (nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            foreach (var unicast in nic.GetIPProperties().UnicastAddresses)
            {
                if (unicast.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                var ip = unicast.Address.GetAddressBytes();
                var mask = unicast.IPv4Mask.GetAddressBytes();
                var network = new byte[4];
                var broadcast = new byte[4];
                var prefix = 0;
                for (var i = 0; i < 4; i++)
                {
                    network[i] = (byte)(ip[i] & mask[i]);
                    broadcast[i] = (byte)(ip[i] | (byte)~mask[i]);
                    for (var bit = 0x80; (bit & mask[i]) != 0; bit >>= 1) prefix++;
                }
                var size = (long)(ToLong(broadcast) - ToLong(network)) + 1;
                if (size > MaxProbeHosts + 2) continue;
                yield return new Subnet(new IPAddress(network), new IPAddress(broadcast));
            }
        }
    }

    private static long ToLong(byte[] bytes) =>
        ((long)bytes[0] << 24) | ((long)bytes[1] << 16) | ((long)bytes[2] << 8) | bytes[3];

    private static IPAddress FromLong(long value) => new(new byte[]
    {
        (byte)(value >> 24), (byte)(value >> 16), (byte)(value >> 8), (byte)value,
    });

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
