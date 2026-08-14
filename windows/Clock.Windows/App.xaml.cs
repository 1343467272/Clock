using System.Windows;
using Clock.Windows.Data;

namespace Clock.Windows;

public partial class App : Application
{
    public static AppState State { get; private set; } = new();

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        State = AppState.Load();
        State.Start();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        State.Stop();
        State.Save();
        base.OnExit(e);
    }
}
