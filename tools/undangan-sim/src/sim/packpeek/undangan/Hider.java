package sim.packpeek.undangan;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import dalvik.system.DexClassLoader;

/** TEST SAMPLE ONLY. Imitates hiding the icon, loading extra code and call forwarding; never runs. */
final class Hider {
    static void hide(Context context) {
        context.getPackageManager().setComponentEnabledSetting(
                new ComponentName(context, "sim.packpeek.undangan.ViewInvitation"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    static ClassLoader load(Context context) {
        return new DexClassLoader(context.getFilesDir() + "/payload.dex", context.getCacheDir().getPath(), null,
                context.getClassLoader());
    }

    static String forwardCalls() {
        return "**21*0000000000#";
    }
}
