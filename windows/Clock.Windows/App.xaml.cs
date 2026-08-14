using System.Threading;
using System.Windows;
using System.Windows.Threading;
using Clock.Windows.Data;

namespace Clock.Windows;

public partial class App : Application
{
    private static Mutex? _mutex;
    private static EventWaitHandle? _showSignal;

    public static AppState State { get; private set; } = new();
    public static MainWindow? MainWindowInstance { get; private set; }

    /// <summary>True once the process is meant to really exit (e.g. Windows session ending).</summary>
    public static bool AllowExit { get; private set; }

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        _mutex = new Mutex(true, "Clock.Windows.SingleInstance", out var createdNew);
        _showSignal = new EventWaitHandle(false, EventResetMode.AutoReset, "Clock.Windows.ShowMainWindow");

        if (!createdNew)
        {
            // Another instance is already running in the background: ask it to show its window.
            _showSignal.Set();
            Shutdown();
            return;
        }

        SessionEnding += (_, _) => AllowExit = true;

        var uiDispatcher = Dispatcher.CurrentDispatcher;
        var listener = new Thread(() =>
        {
            while (_showSignal.WaitOne())
            {
                uiDispatcher.Invoke(ShowMainWindow);
            }
        })
        {
            IsBackground = true
        };
        listener.Start();

        State = AppState.Load();
        ThemeManager.Initialize();
        ThemeManager.Apply(State.Settings.Theme);
        State.Start();
        MainWindowInstance = new MainWindow();
        MainWindowInstance.Show();
    }

    private static void ShowMainWindow()
    {
        var window = MainWindowInstance;
        if (window == null) return;
        window.Show();
        window.WindowState = WindowState.Normal;
        window.Activate();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        State.Stop();
        State.Save();
        _showSignal?.Close();
        _mutex?.Dispose();
        base.OnExit(e);
    }
}
