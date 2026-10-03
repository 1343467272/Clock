using System.Windows;
using Clock.Windows.Localization;
using Clock.Windows.Models;

namespace Clock.Windows.Controls;

/// <summary>
/// Modal dialog that blocks an alarm action until the user solves an addition problem,
/// mirroring Android's math mission dialog.
/// </summary>
public partial class MathChallengeWindow : Window
{
    private static readonly Random Rng = new();

    private readonly string _hardness;
    private MathChallenge _challenge;

    private MathChallengeWindow(string hardness, bool snooze)
    {
        InitializeComponent();
        _hardness = hardness;
        TitleText.Text = snooze ? Text.MathChallengeTitleSnooze : Text.MathChallengeTitleDismiss;
        _challenge = MathChallenge.Create(hardness, Rng);
        UpdatePrompt();
        Loaded += (_, _) => AnswerBox.Focus();
    }

    /// <summary>Shows the dialog and returns true when the mission has been solved.</summary>
    public static bool ShowDialog(Window owner, string hardness, bool snooze)
    {
        var window = new MathChallengeWindow(hardness, snooze) { Owner = owner };
        return window.ShowDialog() == true;
    }

    private void UpdatePrompt()
        => PromptText.Text = string.Format(Text.MathChallengePrompt, _challenge.Left, _challenge.Right);

    private void OnNewProblem(object sender, RoutedEventArgs e)
    {
        _challenge = MathChallenge.Create(_hardness, Rng);
        UpdatePrompt();
        AnswerBox.Clear();
        ErrorText.Visibility = Visibility.Collapsed;
        AnswerBox.Focus();
    }

    private void OnAnswerChanged(object sender, System.Windows.Controls.TextChangedEventArgs e)
    {
        // Matching Android: typing clears a previous wrong-answer message.
        if (ErrorText.Visibility == Visibility.Visible) ErrorText.Visibility = Visibility.Collapsed;
    }

    private void OnConfirm(object sender, RoutedEventArgs e)
    {
        if (_challenge.Matches(AnswerBox.Text))
        {
            DialogResult = true;
            return;
        }

        ErrorText.Text = Text.MathChallengeWrongAnswer;
        ErrorText.Visibility = Visibility.Visible;
        AnswerBox.SelectAll();
        AnswerBox.Focus();
    }

    private void OnCancel(object sender, RoutedEventArgs e) => DialogResult = false;
}
