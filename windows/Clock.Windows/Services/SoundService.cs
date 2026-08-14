using System.IO;
using System.Media;

namespace Clock.Windows.Services;

/// <summary>
/// Plays the same default alarm sound as the built-in Windows Clock app
/// (C:\Windows\Media\Alarm01.wav), looping until the alert is dismissed.
/// Falls back to a system sound if the file is unavailable.
/// </summary>
public static class SoundService
{
    private static readonly string AlarmPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.Windows), "Media", "Alarm01.wav");

    private static SoundPlayer? _player;

    /// <summary>True while the built-in Clock-app sound is actually looping.</summary>
    public static bool IsLooping { get; private set; }

    /// <summary>Starts looping the Clock-app alarm sound, or plays one fallback beep.</summary>
    public static void Start()
    {
        try
        {
            if (File.Exists(AlarmPath))
            {
                _player ??= new SoundPlayer();
                _player.SoundLocation = AlarmPath;
                _player.PlayLooping();
                IsLooping = true;
                return;
            }
        }
        catch
        {
            _player = null;
        }
        PlayFallback();
    }

    /// <summary>Repeating beep used when the Clock-app sound file is missing.</summary>
    public static void PlayFallback()
    {
        try { SystemSounds.Asterisk.Play(); } catch { }
    }

    public static void Stop()
    {
        try { _player?.Stop(); } catch { }
        _player = null;
        IsLooping = false;
    }
}
