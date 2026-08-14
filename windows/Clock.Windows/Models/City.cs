using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Clock.Windows.Models;

public class City
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string Tz { get; set; } = "";

    [JsonIgnore]
    public string LocalName { get; set; } = "";

    [JsonIgnore]
    public string DisplayName => string.IsNullOrEmpty(LocalName) ? Name : LocalName;

    [JsonIgnore]
    public TimeZoneInfo? Zone { get; set; }

    public DateTimeOffset GetNow() => TimeZoneInfo.ConvertTime(DateTimeOffset.UtcNow, Zone ?? TimeZoneInfo.Local);

    [JsonIgnore]
    public string LocalTime => GetNow().ToString("HH:mm:ss");
}

public static class CityCatalog
{
    private static List<City>? _all;
    private static Dictionary<string, string>? _localNames;

    public static IReadOnlyList<City> All
    {
        get
        {
            if (_all != null) return _all;

            var baseDir = AppContext.BaseDirectory;
            var path = Path.Combine(baseDir, "Data", "cities.json");
            if (!File.Exists(path)) path = Path.Combine(baseDir, "cities.json");

            var list = new List<City>();
            if (File.Exists(path))
            {
                try
                {
                    var parsed = JsonSerializer.Deserialize<List<City>>(File.ReadAllText(path));
                    if (parsed != null) list = parsed;
                }
                catch
                {
                    // Fall through to empty catalog.
                }
            }

            var localNames = LoadLocalNames();
            foreach (var c in list)
            {
                if (localNames.TryGetValue(c.Id, out var local)) c.LocalName = local;
                try { c.Zone = TimeZoneInfo.FindSystemTimeZoneById(c.Tz); }
                catch (TimeZoneNotFoundException) { c.Zone = null; }
            }

            _all = list;
            return _all;
        }
    }

    /// <summary>Loads the Chinese display-name map (id → Chinese name), if present.</summary>
    private static Dictionary<string, string> LoadLocalNames()
    {
        if (_localNames != null) return _localNames;

        var map = new Dictionary<string, string>();
        var baseDir = AppContext.BaseDirectory;
        var path = Path.Combine(baseDir, "Data", "cities.zh-CN.json");
        if (!File.Exists(path)) path = Path.Combine(baseDir, "cities.zh-CN.json");
        if (File.Exists(path))
        {
            try
            {
                var parsed = JsonSerializer.Deserialize<Dictionary<string, string>>(File.ReadAllText(path));
                if (parsed != null) map = parsed;
            }
            catch
            {
                // No local names; fall back to English display names.
            }
        }

        _localNames = map;
        return _localNames;
    }

    public static City? ById(string id) => All.FirstOrDefault(c => c.Id == id);

    public static bool IsValidId(string id) => ById(id) != null;
}
