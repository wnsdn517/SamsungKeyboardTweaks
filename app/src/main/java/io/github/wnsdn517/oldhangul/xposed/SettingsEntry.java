package io.github.wnsdn517.oldhangul.xposed;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Adds this module's settings as an entry to Samsung Keyboard's own settings screen
 * (Settings > General management > Samsung Keyboard settings), so they can be reached from there.
 *
 * <p>The screen is a PreferenceFragmentCompat built from {@code settings_main_layout}; the entry is a
 * plain Preference that starts {@code SettingsActivity}. androidx.preference keeps its names in
 * Samsung's build, so it is used by name through reflection.
 */
final class SettingsEntry {
    private SettingsEntry() {}

    private static final String FRAGMENT = "com.samsung.android.honeyboard.settings.common.HoneyBoardSettingsFragment";
    private static final String PREFERENCE = "androidx.preference.Preference";
    private static final String KEY = "oldhangul_settings";
    private static final String MODULE = "io.github.wnsdn517.oldhangul";
    /** The category the entry joins; the screen itself is the fallback. */
    private static final String CATEGORY = "SETTINGS_CATEGORY_CUSTOMIZATION_GESTURE_AND_FEEDBACK";

    static void install(ClassLoader cl) {
        try {
            Class<?> fragment = XposedHelpers.findClass(FRAGMENT, cl);
            XposedHelpers.findAndHookMethod(fragment, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        addEntry(param.thisObject, cl);
                    } catch (Throwable t) {
                        XposedBridge.log("OldHangul: settings entry not added: " + t);
                    }
                }
            });
            XposedBridge.log("OldHangul: settings entry hook installed");
        } catch (Throwable t) {
            XposedBridge.log("OldHangul: settings entry hook unavailable: " + t);
        }
    }

    private static void addEntry(Object fragment, ClassLoader cl) {
        if (XposedHelpers.callMethod(fragment, "findPreference", KEY) != null) return;
        Context context = (Context) XposedHelpers.callMethod(fragment, "getContext");
        if (context == null) return;
        Object preference = XposedHelpers.newInstance(XposedHelpers.findClass(PREFERENCE, cl), context);
        XposedHelpers.callMethod(preference, "setKey", KEY);
        XposedHelpers.callMethod(preference, "setTitle", (CharSequence) "옛한글 입력 보조");
        XposedHelpers.callMethod(preference, "setSummary", (CharSequence) "옛한글 조합, 도구, 타자 속도 등 모듈 설정");
        XposedHelpers.callMethod(preference, "setPersistent", false);
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .setClassName(MODULE, MODULE + ".ui.SettingsActivity");
        XposedHelpers.callMethod(preference, "setIntent", intent);

        Object parent = XposedHelpers.callMethod(fragment, "findPreference", CATEGORY);
        if (parent == null) parent = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
        if (parent == null) return;
        XposedHelpers.callMethod(parent, "addPreference", preference);
    }
}
