using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;
using Microsoft.Win32;

namespace Clock.Windows;

/// <summary>
/// Swaps the Material light/dark brush palettes used app-wide.
/// Theme value comes from AppSettings.Theme: "light", "dark" or "system" (follows Windows).
/// </summary>
public static class ThemeManager
{
    private const string LightKey = "BkLight";
    private const string DarkKey = "BkDark";

    private static string _theme = "system";
    private static string? _resolved; // "light" or "dark"
    private static DispatcherTimer? _watcher;

    public static void Apply(string theme)
    {
        _theme = theme switch
        {
            "dark" => "dark",
            "light" => "light",
            _ => "system",
        };

        var resolved = _theme == "system" ? (IsOsDark() ? "dark" : "light") : _theme;
        if (_resolved == resolved) return;
        _resolved = resolved;

        var app = Application.Current;
        if (app == null) return;

        var merged = app.Resources.MergedDictionaries;
        var keep = resolved == "dark" ? DarkKey : LightKey;
        var drop = resolved == "dark" ? LightKey : DarkKey;

        var keepDict = (ResourceDictionary)app.Resources[keep];
        var dropDict = (ResourceDictionary)app.Resources[drop];

        if (merged.Contains(dropDict)) merged.Remove(dropDict);
        if (!merged.Contains(keepDict)) merged.Add(keepDict);

        if (_theme == "system") StartWatcher();
        else StopWatcher();
    }

    /// <summary>Builds the two palettes and registers them in application resources.</summary>
    public static void Initialize()
    {
        var app = Application.Current;
        if (app == null) return;

        var light = BuildLight();
        var dark = BuildDark();
        app.Resources[LightKey] = light;
        app.Resources[DarkKey] = dark;
    }

    private static void StartWatcher()
    {
        if (_watcher != null) return;
        _watcher = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
        _watcher.Tick += (_, _) =>
        {
            var resolved = IsOsDark() ? "dark" : "light";
            if (resolved != _resolved) Apply("system");
        };
        _watcher.Start();
    }

    private static void StopWatcher()
    {
        _watcher?.Stop();
        _watcher = null;
    }

    private static bool IsOsDark()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            if (key?.GetValue("AppsUseLightTheme") is int value) return value == 0;
        }
        catch
        {
            // Fall back to light theme.
        }
        return false;
    }

    private static void Add(ResourceDictionary d, string key, string hex)
    {
        var brush = new SolidColorBrush((Color)ColorConverter.ConvertFromString(hex));
        brush.Freeze();
        d[key] = brush;
    }

    private static ResourceDictionary BuildLight()
    {
        var d = new ResourceDictionary();
        Add(d, "BkBackground", "#FEFBFF");
        Add(d, "BkSurface", "#F0F0FA");
        Add(d, "BkCard", "#FFFFFF");
        Add(d, "BkPrimary", "#475D92");
        Add(d, "BkOnPrimary", "#FFFFFF");
        Add(d, "BkOnSurface", "#1A1B20");
        Add(d, "BkOnSurfaceVariant", "#44464F");
        Add(d, "BkOutline", "#C5C6D0");
        Add(d, "BkBorder", "#14000000");
        Add(d, "BkHover", "#14475D92");
        Add(d, "BkPrimaryHover", "#3A5580");
        Add(d, "BkInput", "#E1E2EC");
        Add(d, "BkInputBorder", "#C5C6D0");
        Add(d, "BkSurfaceDim", "#66000000");
        Add(d, "BkScrollThumb", "#66000000");
        Add(d, "BkScrollTrack", "Transparent");
        return d;
    }

    private static ResourceDictionary BuildDark()
    {
        var d = new ResourceDictionary();
        Add(d, "BkBackground", "#1A1B20");
        Add(d, "BkSurface", "#2E3038");
        Add(d, "BkCard", "#191B23");
        Add(d, "BkPrimary", "#B0C6FF");
        Add(d, "BkOnPrimary", "#152E60");
        Add(d, "BkOnSurface", "#E2E2E9");
        Add(d, "BkOnSurfaceVariant", "#C5C6D0");
        Add(d, "BkOutline", "#44464F");
        Add(d, "BkBorder", "#2EFFFFFF");
        Add(d, "BkHover", "#33B0C6FF");
        Add(d, "BkPrimaryHover", "#C0D4FF");
        Add(d, "BkInput", "#44464F");
        Add(d, "BkInputBorder", "#8F9099");
        Add(d, "BkSurfaceDim", "#99000000");
        Add(d, "BkScrollThumb", "#5AFFFFFF");
        Add(d, "BkScrollTrack", "Transparent");
        return d;
    }
}
