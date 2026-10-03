namespace Clock.Windows.Models;

/// <summary>
/// An addition problem the user must solve before snoozing or dismissing an alarm. Mirrors
/// Android's <c>AlarmMathChallenge</c>: the hardness level selects the same operand ranges.
/// </summary>
public sealed class MathChallenge
{
    public const string HardnessOff = "off";
    public const string HardnessEasy = "easy";
    public const string HardnessNormal = "normal";
    public const string HardnessHard = "hard";

    private MathChallenge(int left, int right)
    {
        Left = left;
        Right = right;
    }

    public int Left { get; }
    public int Right { get; }
    public int Expected => Left + Right;

    /// <summary>True when the trimmed answer parses to the expected sum.</summary>
    public bool Matches(string? answer)
        => int.TryParse((answer ?? "").Trim(), out var value) && value == Expected;

    /// <summary>Creates a problem for the given hardness level ("easy", "normal" or "hard").</summary>
    public static MathChallenge Create(string? hardness, Random random)
    {
        int min;
        int range;
        switch (hardness)
        {
            case HardnessEasy:
                min = 1;
                range = 20;
                break;
            case HardnessNormal:
                min = 10;
                range = 80;
                break;
            case HardnessHard:
                min = 50;
                range = 450;
                break;
            default:
                min = 0;
                range = 0;
                break;
        }

        var left = min + (range > 0 ? random.Next(range) : 0);
        var right = min + (range > 0 ? random.Next(range) : 0);
        return new MathChallenge(left, right);
    }
}
