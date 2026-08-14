using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;

namespace Clock.Windows.Controls;

/// <summary>
/// Forwards mouse-wheel events from a control that swallows them (e.g. a ListBox with its own
/// internal ScrollViewer) up to the nearest outer ScrollViewer, so the outer scroll region can
/// scroll even when the inner control's content fits.
/// </summary>
public static class ScrollWheelBehavior
{
    public static readonly DependencyProperty ForwardWheelToOuterScrollViewerProperty =
        DependencyProperty.RegisterAttached(
            "ForwardWheelToOuterScrollViewer",
            typeof(bool),
            typeof(ScrollWheelBehavior),
            new PropertyMetadata(false, OnForwardWheelToOuterScrollViewerChanged));

    public static bool GetForwardWheelToOuterScrollViewer(DependencyObject obj) =>
        (bool)obj.GetValue(ForwardWheelToOuterScrollViewerProperty);

    public static void SetForwardWheelToOuterScrollViewer(DependencyObject obj, bool value) =>
        obj.SetValue(ForwardWheelToOuterScrollViewerProperty, value);

    private static void OnForwardWheelToOuterScrollViewerChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not UIElement element) return;

        element.RemoveHandler(UIElement.PreviewMouseWheelEvent, new MouseWheelEventHandler(OnPreviewMouseWheel));
        if (e.NewValue is true)
        {
            element.AddHandler(UIElement.PreviewMouseWheelEvent, new MouseWheelEventHandler(OnPreviewMouseWheel),
                handledEventsToo: true);
        }
    }

    private static void OnPreviewMouseWheel(object sender, MouseWheelEventArgs e)
    {
        if (sender is not DependencyObject element) return;

        var scrollViewer = FindAncestor<ScrollViewer>(element);
        if (scrollViewer == null || scrollViewer.ViewportHeight >= scrollViewer.ExtentHeight) return;

        scrollViewer.ScrollToVerticalOffset(scrollViewer.VerticalOffset - e.Delta);
        e.Handled = true;
    }

    private static T? FindAncestor<T>(DependencyObject child) where T : DependencyObject
    {
        var parent = VisualTreeHelper.GetParent(child);
        while (parent != null)
        {
            if (parent is T match) return match;
            parent = VisualTreeHelper.GetParent(parent);
        }
        return null;
    }
}
