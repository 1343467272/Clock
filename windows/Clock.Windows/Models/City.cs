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
    public TimeZoneInfo? Zone { get; set; }

    public DateTimeOffset GetNow() => TimeZoneInfo.ConvertTime(DateTimeOffset.UtcNow, Zone ?? TimeZoneInfo.Local);

    [JsonIgnore]
    public string LocalTime => GetNow().ToString("HH:mm:ss");
}

public static class CityCatalog
{
    private static List<City>? _all;

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

            foreach (var c in list)
            {
                try { c.Zone = TimeZoneInfo.FindSystemTimeZoneById(c.Tz); }
                catch (TimeZoneNotFoundException) { c.Zone = null; }
            }

            _all = list;
            return _all;
        }
    }

    public static City? ById(string id) => All.FirstOrDefault(c => c.Id == id);

    public static bool IsValidId(string id) => ById(id) != null;
}
