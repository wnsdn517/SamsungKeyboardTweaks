package io.github.wnsdn517.oldhangul.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Process;
import android.view.inputmethod.EditorInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.wnsdn517.oldhangul.Prefs;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** LSPosed entry point: hooks Samsung Keyboard (HoneyBoard). */
public final class ModuleMain implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String TARGET = "com.samsung.android.honeyboard";
    private static final String SERVICE = "com.samsung.android.honeyboard.service.HoneyBoardService";
    private static final String MODULE = "io.github.wnsdn517.oldhangul";

    @Override
    public void initZygote(StartupParam startupParam) {
        NativeLoader.modulePath = startupParam.modulePath;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName) || !lpparam.isFirstApplication) {
            return;
        }
        ClassLoader cl = lpparam.classLoader;
        OldHangulController controller = new OldHangulController(new XSharedPreferences(MODULE, Prefs.FILE));
        JapanesePreloader[] preloader = new JapanesePreloader[1];
        try {
            hookService(cl, controller, preloader);
            HookTargets targets = HookTargets.load(cl, lpparam.appInfo.sourceDir, lpparam.appInfo.dataDir);
            UndoBee.install(targets, controller);
            preloader[0] = new JapanesePreloader(targets.engineFactory, lpparam.appInfo.dataDir);
            hookKeyActions(targets, controller);
            NumberSwipe numberSwipe = NumberSwipe.hook(controller);
            LanguageSwitch languageSwitch = LanguageSwitch.install(targets, controller);
            hookLongPress(targets, controller, numberSwipe, languageSwitch);
            hookKeyTouches(targets, controller, numberSwipe);
            hookPreview(targets, numberSwipe);
            hookSamsungInputConnection(targets, controller);
            if (controller.debugLog()) {
                // Only with the log on: these hooks sit on methods Samsung calls a lot.
                hookShiftStateForDebug(targets, controller);
            }
            hookLanguage(targets, controller, preloader[0]);
            hookSizeLimits(targets, controller);
            ClipboardColumns.hook(cl, controller::clipboardColumns);
            hookShiftLabels(cl, controller, targets);
            hookMultilingualNumbers(cl, targets);
            hookInlineSuggestions(cl, controller);
            hookCandidates(cl, controller);
            hookCandidatePick(cl, controller);
            hookSmartCandidate(cl, controller);
            hookTranslationServer(targets, controller);
            SettingsEntry.install(cl);
            XposedBridge.log("OldHangul: hooks installed");
        } catch (Throwable t) {
            XposedBridge.log("OldHangul: failed to hook Samsung Keyboard");
            XposedBridge.log(t);
        }
    }

    private static void hookService(ClassLoader cl, OldHangulController controller,
            JapanesePreloader[] preloader) {
        Class<?> service = XposedHelpers.findClass(SERVICE, cl);
        XposedHelpers.findAndHookMethod(service, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                InputMethodService ime = (InputMethodService) param.thisObject;
                controller.attach(ime);
                registerRestartReceiver(ime);
                if (preloader[0] != null && controller.preloadJapanese()) {
                    preloader[0].start();
                }
                if (controller.debugLog()) {
                    StallWatchdog.start();
                }
            }
        });
        XposedHelpers.findAndHookMethod(service, "onStartInputView", EditorInfo.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if ((Boolean) param.args[1]) {
                            controller.onRestartInput();
                        } else {
                            controller.resetState();
                        }
                        controller.reloadPrefs();
                        controller.ensureUndoButton();
                    }
                });
        XposedHelpers.findAndHookMethod(service, "onStartInput", EditorInfo.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if ((Boolean) param.args[1]) {
                            controller.onRestartInput();
                        } else {
                            controller.resetState();
                        }
                        controller.onStartEditor((EditorInfo) param.args[0]);
                    }
                });
        XposedHelpers.findAndHookMethod(service, "onFinishInputView", boolean.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                controller.commitComposing();
                controller.onKeyboardHidden();
            }
        });
        XposedHelpers.findAndHookMethod(service, "onFinishInput", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                controller.resetState();
            }
        });
        XposedHelpers.findAndHookMethod(service, "onUpdateSelection",
                int.class, int.class, int.class, int.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        controller.beforeUpdateSelection(param.args);
                    }
                });
    }

    /**
     * Samsung's key action runs as usual (sound, vibration, Shift release, keyboard
     * redraw); when the controller handled the key, only the input command inside
     * it is switched off by renaming its request type to one nothing handles.
     */
    /** Lets the settings app restart the keyboard without root. */
    private static void registerRestartReceiver(Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                XposedBridge.log("OldHangul: restarting Samsung Keyboard on request");
                Process.killProcess(Process.myPid());
            }
        };
        IntentFilter filter = new IntentFilter(Prefs.ACTION_RESTART_KEYBOARD);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Prefs.RESTART_PERMISSION, null,
                        Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter, Prefs.RESTART_PERMISSION, null);
            }
        } catch (RuntimeException e) {
            XposedBridge.log("OldHangul: restart receiver not registered: " + e);
        }
    }

    private static void hookKeyActions(HookTargets targets, OldHangulController controller) {
        boolean[] swallowInput = new boolean[1];
        XposedBridge.hookMethod(targets.executeAction, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object action = param.args[0];
                Object info = param.args[1];
                // Actions may run nested; restore the outer value afterwards.
                param.setObjectExtra("outer", swallowInput[0]);
                swallowInput[0] = false;
                if (controller.idleInOtherLanguage() && !controller.debugLog()) {
                    return;
                }
                if (action == null || !targets.keyRequestInfo.isInstance(info)) {
                    return;
                }
                String name = (String) targets.actionName.invoke(action);
                if (controller.debugLog()) {
                    XposedBridge.log("OldHangul: " + name + " samsungShift=" + isShifted(targets, action)
                            + " " + info);
                }
                swallowInput[0] = controller.beforeKeyAction(name, info);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                controller.afterKeyAction();
                Object outer = param.getObjectExtra("outer");
                swallowInput[0] = outer instanceof Boolean && (Boolean) outer;
            }
        });
        XposedBridge.hookMethod(targets.inputCommand, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (swallowInput[0] && param.args[0] != null) {
                    disableInputRequest(param.args[0]);
                }
            }
        });
    }

    private static final Map<Class<?>, Field> SHIFT_FIELDS = new HashMap<>();

    /** Reads Samsung's Shift state (for the debug log) through the holder every key action references. */
    private static boolean isShifted(HookTargets targets, Object action) {
        try {
            Field f;
            if (SHIFT_FIELDS.containsKey(action.getClass())) {
                f = SHIFT_FIELDS.get(action.getClass());
            } else {
                f = null;
                for (Class<?> c = action.getClass(); c != null && f == null; c = c.getSuperclass()) {
                    for (Field candidate : c.getDeclaredFields()) {
                        if (candidate.getType() == targets.shiftState) {
                            candidate.setAccessible(true);
                            f = candidate;
                            break;
                        }
                    }
                }
                SHIFT_FIELDS.put(action.getClass(), f);
            }
            Object state = f == null ? null : f.get(action);
            return state != null && (Boolean) targets.isShifted.invoke(state);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * While a syllable is composed here, Samsung's own finishComposingText /
     * setComposingText / setComposingRegion would commit or erase it (the
     * flashing underline and the duplicated text); those calls are dropped.
     */
    private static void hookSamsungInputConnection(HookTargets targets, OldHangulController controller) {
        XC_MethodHook block = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (controller.blockSamsungComposition()) {
                    param.setResult(true);
                }
            }
        };
        Class<?> ic = targets.samsungInputConnection;
        XposedBridge.hookAllConstructors(ic, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                controller.setSamsungWrapper(param.thisObject);
            }
        });
        XposedHelpers.findAndHookMethod(ic, "setComposingText", CharSequence.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (controller.blockSamsungText((CharSequence) param.args[0])) {
                            param.setResult(true);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // Japanese kana (and swipe words) arrive via
                        // setComposingText, not commitText: without this the
                        // CPM/WPM meter never moved while typing Japanese.
                        // p040b8/b delegates straight to the app after its
                        // p066c8.d cache, so recording here is safe.
                        if (controller.internalEditActive() || param.args[0] == null) return;
                        if (controller.isComposing()) return;
                        // The translation box updates from a worker thread; the meter's views are main-thread only.
                        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return;
                        CharSequence text = (CharSequence) param.args[0];
                        if (text.length() > 64) return;
                        controller.onExternalComposing(text.toString());
                    }
                });
        XposedHelpers.findAndHookMethod(ic, "setComposingRegion", int.class, int.class, block);
        // Blocked while a word of ours is composing (Samsung finishes the span on
        // nearly every key, which ended the word each time), and only then is the
        // model resynced: the after hook runs even for a blocked call.
        // One callback doing both: findAndHookMethod takes a single callback, and a
        // second hook object passed before it was taken for a parameter type, so
        // the call was never blocked and every key finished (committed) the word.
        XposedHelpers.findAndHookMethod(ic, "finishComposingText",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                        if (controller.blockSamsungComposition()) {
                            param.setResult(true);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                        controller.externalFinishComposing();
                    }
                });
        XposedHelpers.findAndHookMethod(ic, "commitText", CharSequence.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        // Samsung also ran the key (multilingual typing): its Korean text must not reach the editor.
                        if (controller.blockSamsungText((CharSequence) param.args[0])) {
                            param.setResult(true);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (controller.internalEditActive() || param.args[0] == null) return;
                        CharSequence text = (CharSequence) param.args[0];
                        if (text.length() == 0) return;
                        controller.onExternalCommit(text.toString());
                    }
                });
    }

    private static final String KEY_LABEL = "com.samsung.android.honeyboard.forms.model.KeyCodeLabelVO";
    private static final String LETTER_KEY_LABEL = "com.samsung.android.honeyboard.forms.model.LetterKeyCodeLabelVO";

    /**
     * Samsung announces language and layout changes to its shift holder through
     * onBoardConfigChanged("currentLang" / "currInputType", old, new).
     */
    private static void hookLanguage(HookTargets targets, OldHangulController controller,
            JapanesePreloader preloader) {
        Object[] language = new Object[1];
        try {
            XposedHelpers.findAndHookMethod(targets.shiftState, "onBoardConfigChanged",
                    String.class, Object.class, Object.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String name = (String) param.args[0];
                            Object value = param.args[2];
                            if ("currentLang".equals(name) && value != null) {
                                language[0] = value;
                            } else if (!"currInputType".equals(name) || language[0] == null) {
                                return;
                            }
                            try {
                                String code = (String) XposedHelpers.callMethod(language[0], "getLanguageCode");
                                preloader.onLanguage(code);
                                controller.onLanguage(code,
                                        (String) XposedHelpers.callMethod(language[0], "getInputType"),
                                        isBilingual(language[0]));
                            } catch (RuntimeException e) {
                                XposedBridge.log("OldHangul: could not read language: " + e);
                            }
                        }
                    });
        } catch (RuntimeException e) {
            XposedBridge.log("OldHangul: language changes not tracked: " + e);
        }
    }

    /** How far the resize handles may go past Samsung's limits. */
    private static final float SIZE_MIN_FACTOR = 0.5f;
    private static final float SIZE_MAX_HEIGHT_FACTOR = 1.6f;

    /**
     * Samsung's keyboard size table answers (…, index, mask) with the minimum,
     * default or maximum ratio (index 0/1/2; mask bit 32 forces the default).
     * Minimums are halved and the maximum height raised; the maximum width stays,
     * since it is already the full screen width.
     */
    private static void hookSizeLimits(HookTargets targets, OldHangulController controller) {
        for (Method m : new Method[] {targets.sizeHeightRatio, targets.sizeWidthRatio}) {
            if (m == null) {
                continue;
            }
            boolean height = m == targets.sizeHeightRatio;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!controller.unlimitedSize() || !(param.getResult() instanceof Float)) {
                        return;
                    }
                    int index = ((Integer) param.args[5] & 32) != 0 ? 1 : (Integer) param.args[4];
                    float ratio = (Float) param.getResult();
                    if (index == 0) {
                        param.setResult(ratio * SIZE_MIN_FACTOR);
                    } else if (index == 2 && height) {
                        param.setResult(ratio * SIZE_MAX_HEIGHT_FACTOR);
                    }
                }
            });
        }
    }

    /** Samsung's multilingual typing pairs the language with a second one (bilingual id). */
    private static boolean isBilingual(Object language) {
        try {
            return (Integer) XposedHelpers.callMethod(language, "getBilingualId") > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Shift layer of dubeolsik: ㄹ ㅇ ㅎ ㅏ show and type ㅿ ㆁ ㆆ ㆍ.
     *
     * <p>Samsung calls this getter constantly (loading layouts, drawing keys,
     * finding the key under a swipe), so the hook is only installed while Korean
     * dubeolsik with the Shift layer is active and removed for other languages.
     */
    private static void hookShiftLabels(ClassLoader cl, OldHangulController controller,
            HookTargets targets) throws Exception {
        Class<?> labelClass = XposedHelpers.findClass(KEY_LABEL, cl);
        Class<?> letterClass = XposedHelpers.findClass(LETTER_KEY_LABEL, cl);
        Method getNormal = letterClass.getMethod("getKeyCodeLabel");
        Method getCodes = labelClass.getMethod("getKeyCodes");
        Method getLabel = labelClass.getMethod("getKeyLabel");
        Method getSize = labelClass.getMethod("getKeyLabelSize");
        java.lang.reflect.Constructor<?> create =
                labelClass.getConstructor(String.class, java.util.List.class, float.class);
        Map<Long, Object> made = new HashMap<>();
        Method upper = letterClass.getMethod("getUpperKeyCodeLabel");
        // Samsung asks for key labels on every key draw: decide once per label
        // object (null result = leave it) instead of reflecting each time.
        Map<Object, Object> decided = new java.util.WeakHashMap<>();
        Object keep = new Object();
        int[] missLogged = new int[1];
        java.util.function.Function<Object, Object> replacement = normal -> {
            Object cached = decided.get(normal);
            if (cached != null) {
                return cached == keep ? null : cached;
            }
            Object result = keep;
            try {
                java.util.List<?> codes = (java.util.List<?>) getCodes.invoke(normal);
                if (codes != null && !codes.isEmpty()) {
                    String rendered = String.valueOf(getLabel.invoke(normal));
                    char variant = controller.shiftVariant(rendered, (Integer) codes.get(0));
                    if (variant != 0) {
                        float size = (Float) getSize.invoke(normal);
                        long key = ((long) variant << 32) | Float.floatToIntBits(size);
                        Object label = made.get(key);
                        if (label == null) {
                            label = create.newInstance(String.valueOf(variant),
                                    java.util.Collections.singletonList((int) variant), size);
                            made.put(key, label);
                            controller.debug("archaic Shift label cached: " + variant);
                        }
                        result = label;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                controller.debug("shift label lookup failed: " + e);
            }
            decided.put(normal, result);
            return result == keep ? null : result;
        };
        controller.setLabelCacheClearer(decided::clear);
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!controller.shiftLayerActive()) {
                    return;
                }
                Object normal = getNormal.invoke(param.thisObject);
                if (normal == null) {
                    return;
                }
                Object label = replacement.apply(normal);
                if (label != null) {
                    param.setResult(label);
                }
            }
        };
        XC_MethodHook normalHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                if (!controller.shiftLayerActive() || !controller.samsungShifted()) return;
                Object normal = param.getResult();
                if (normal == null) return;
                Object label = replacement.apply(normal);
                if (label != null) {
                    param.setResult(label);
                }
            }
        };
        XposedBridge.hookMethod(getNormal, normalHook);
        XC_MethodHook.Unhook[] installed = new XC_MethodHook.Unhook[1];
        XposedBridge.hookMethod(targets.isShifted, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.getResult() instanceof Boolean) {
                    controller.setSamsungShifted((Boolean) param.getResult());
                }
            }
        });
        controller.setShiftLayerListener(() -> {
            boolean wanted = controller.shiftLayerActive();
            if (wanted && installed[0] == null) {
                installed[0] = XposedBridge.hookMethod(upper, hook);
                XposedBridge.log("OldHangul: archaic Shift label hook installed");
            } else if (!wanted && installed[0] != null) {
                installed[0].unhook();
                installed[0] = null;
                XposedBridge.log("OldHangul: archaic Shift label hook removed");
            }
        });
    }

    /** Keeps the top-row number secondary label visible in multilingual layouts. */
    private static void hookMultilingualNumbers(ClassLoader cl, HookTargets targets) {
        try {
            Class<?> keyVo = XposedHelpers.findClass(
                    "com.samsung.android.honeyboard.forms.model.KeyVO", cl);
            XposedHelpers.findAndHookMethod(keyVo, "getSecondaryKey", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String digit = topRowDigit(param.thisObject);
                    Object secondary = param.getResult();
                    if (digit == null || secondary == null) return;
                    try {
                        String old = String.valueOf(XposedHelpers.callMethod(
                                secondary, "getSecondaryNumber"));
                        if (old.length() != 0) return;
                        Object copy = XposedHelpers.callMethod(secondary, "copy",
                                XposedHelpers.callMethod(secondary, "getSecondaryLabel"),
                                XposedHelpers.callMethod(secondary, "getTertiaryLabel"),
                                XposedHelpers.callMethod(secondary, "getSecondarySymbol"),
                                digit,
                                XposedHelpers.callMethod(secondary, "getSecondaryLabelVisibleType"),
                                XposedHelpers.callMethod(secondary, "getSecondaryLabelGravity"),
                                XposedHelpers.callMethod(secondary, "getTertiaryLabelGravity"));
                        param.setResult(copy);
                    } catch (Throwable ignored) {
                    }
                }
            });
            Class<?> presenter = targets.presenterKey.getDeclaringClass();
            Method secondaryText = null;
            for (Class<?> c = presenter; c != null && secondaryText == null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (m.getReturnType() == String.class && p.length == 2
                            && p[0] == boolean.class && p[1] == boolean.class) {
                        secondaryText = m;
                        break;
                    }
                }
            }
            if (secondaryText == null) throw new NoSuchMethodException("secondary label");
            secondaryText.setAccessible(true);
            XposedBridge.hookMethod(secondaryText, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String digit = topRowDigit(keyFromPresenter(param.thisObject, targets.presenterKey));
                            if (digit != null) param.setResult(digit);
                        }
                    });
            try {
                Class<?> visibility = XposedHelpers.findClass("p106dp.b", cl);
                XposedHelpers.findAndHookMethod(visibility, "a", keyVo, boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (topRowDigit(param.args[0]) != null) param.setResult(true);
                            }
                        });
            } catch (Throwable t) {
                XposedBridge.log("OldHangul: number visibility hook unavailable: " + t);
            }
            try {
                Class<?> gravity = XposedHelpers.findClass("p530sp.a", cl);
                XposedHelpers.findAndHookMethod(gravity, "a", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (topRowDigit(field(param.thisObject, "f35239a")) != null) {
                            param.setResult(8388627); // START | CENTER_VERTICAL
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log("OldHangul: number gravity hook unavailable: " + t);
            }
            XposedBridge.log("OldHangul: multilingual number labels hooked");
        } catch (Throwable t) {
            XposedBridge.log("OldHangul: multilingual number hook unavailable: " + t);
        }
    }

    private static Object field(Object object, String name) {
        if (object == null) return null;
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(object);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private static Object keyFromPresenter(Object presenter, Field keyField) {
        try {
            keyField.setAccessible(true);
            return keyField.get(presenter);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return field(presenter, "f779a");
        }
    }

    private static String topRowDigit(Object key) {
        if (key == null) return null;
        try {
            Object normal = XposedHelpers.callMethod(key, "getNormalKey");
            String label = String.valueOf(XposedHelpers.callMethod(normal, "getKeyLabel"));
            String ko = "ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔ";
            String en = "qwertyuiop";
            int index = ko.indexOf(label);
            if (index < 0) index = en.indexOf(label);
            return index >= 0 ? String.valueOf("1234567890".charAt(index)) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** With the debug log on, records every change to Samsung's shift state and who made it. */
    private static void hookShiftStateForDebug(HookTargets targets, OldHangulController controller) {
        XC_MethodHook log = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!controller.debugLog()) {
                    return;
                }
                StringBuilder sb = new StringBuilder("OldHangul: shiftState.")
                        .append(param.method.getName()).append('(').append(param.args[0]).append(')');
                XposedBridge.log(sb.toString());
            }
        };
        for (Method m : targets.shiftState.getDeclaredMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 1 && params[0] == int.class && m.getReturnType() == void.class
                    && !Modifier.isAbstract(m.getModifiers())) {
                XposedBridge.hookMethod(m, log);
            }
        }
    }

    private static final String HANDLED_INPUT = "oldhangul_handled";

    /** Renames the request's "input_key_*" type so the input command ignores it. */
    private static void disableInputRequest(Object request) throws IllegalAccessException {
        for (Class<?> c = request.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                Object v = f.get(request);
                if (v instanceof String && ((String) v).startsWith("input_key_")) {
                    f.set(request, HANDLED_INPUT);
                }
            }
        }
    }

    private static void hookKeyTouches(HookTargets targets, OldHangulController controller,
            NumberSwipe numberSwipe) {
        XC_MethodHook stopRepeat = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                controller.onKeyTouch();
                if (controller.numberSwipe()) {
                    numberSwipe.onKeyDown(keyCodeOf(targets, param.thisObject));
                }
            }
        };
        for (Method m : targets.keyTouch) {
            XposedBridge.hookMethod(m, stopRepeat);
        }
    }

    /** Learns the id of the letter preview bubble Samsung shows on key down. */
    private static void hookPreview(HookTargets targets, NumberSwipe numberSwipe) {
        if (targets.previewShow == null) {
            return;
        }
        XposedBridge.hookMethod(targets.previewShow, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.getResult() instanceof Integer) {
                    numberSwipe.onSamsungPreview((Integer) param.getResult());
                }
            }
        });
        XposedBridge.log("OldHangul: key preview hook installed "
                + targets.previewShow.getDeclaringClass().getName() + "#" + targets.previewShow.getName());
    }

    private static void hookLongPress(HookTargets targets, OldHangulController controller,
            NumberSwipe numberSwipe, LanguageSwitch languageSwitch) throws Exception {
        Method longPress = targets.longPress;
        Object noneResult = findNoneResult(longPress.getReturnType());
        XposedBridge.hookMethod(longPress, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (numberSwipe.isSwiping()) {
                    param.setResult(noneResult);
                    return;
                }
                int code = keyCodeOf(targets, param.thisObject);
                controller.debug("long press key code=" + code);
                if ((code == -108 || code == -401) && languageSwitch != null
                        && languageSwitch.onLongPress(languageSwitch.manager)) {
                    param.setResult(noneResult);
                    return;
                }
                if (code != 0 && controller.onLongPress(code)) {
                    param.setResult(noneResult);
                }
            }
        });
    }

    /** The code of the key a presenter handles, or 0. */
    private static int keyCodeOf(HookTargets targets, Object presenter) {
        try {
            Object key = targets.presenterKey.get(presenter);
            if (key == null) {
                return 0;
            }
            Object normal = XposedHelpers.callMethod(key, "getNormalKey");
            Object label = normal == null ? null : XposedHelpers.callMethod(normal, "getKeyCodeLabel");
            return label == null ? 0 : (Integer) XposedHelpers.callMethod(label, "getKeyCode");
        } catch (IllegalAccessException | RuntimeException e) {
            return 0;
        }
    }

    /** The long-press result that means "nothing happened" (its toString() is "NONE"). */
    private static Object findNoneResult(Class<?> resultType) throws IllegalAccessException {
        Object fallback = null;
        for (Field f : resultType.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == resultType) {
                f.setAccessible(true);
                Object v = f.get(null);
                if (v != null && "NONE".equals(v.toString())) {
                    return v;
                }
                if (fallback == null) {
                    fallback = v;
                }
            }
        }
        return fallback;
    }

    /**
     * Samsung builds one suggestion object (Ta.b: index, text, type...) per
     * candidate shown in its suggestion bar. The module records them to offer the
     * top word as ghost text (he[llo]); with the debug log on they are listed to
     * learn how the engine orders them.
     */
    private static void hookCandidates(ClassLoader cl, OldHangulController controller) {
        try {
            Class<?> suggestion = XposedHelpers.findClass("Ta.b", cl);
            Field index = null;
            Field text = null;
            Field kind = null;
            for (Field f : suggestion.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                if (f.getType() == int.class && index == null) index = f;
                else if (f.getType() == CharSequence.class && text == null) text = f;
                else if (f.getType() == int.class && kind == null) kind = f;
            }
            Field fIndex = index;
            Field fText = text;
            Field fKind = kind;
            if (fText == null) throw new NoSuchFieldException("candidate text");
            XposedBridge.hookAllConstructors(suggestion, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        CharSequence t = (CharSequence) fText.get(param.thisObject);
                        int i = fIndex == null ? -1 : fIndex.getInt(param.thisObject);
                        int k = fKind == null ? -1 : fKind.getInt(param.thisObject);
                        controller.onCandidate(i, t == null ? null : t.toString(), k);
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                    }
                }
            });
            XposedBridge.log("OldHangul: candidate hook installed");
        } catch (Throwable t) {
            XposedBridge.log("OldHangul: candidate hook unavailable: " + t);
        }
    }

    /**
     * Samsung's "paste what you copied" suggestion (smart candidate) replaces the candidate
     * row and is dropped as soon as typing starts. The text it showed is remembered, and when
     * Samsung hides it without the user having used or closed it, a small chip with the same
     * text stays at the side of the candidate row (see OldHangulController.showClipChip).
     */
    private static void hookSmartCandidate(ClassLoader cl, OldHangulController controller) {
        String pkg = "com.samsung.android.honeyboard.textboard.smartcandidate.viewmodel.";
        try {
            Class<?> item = XposedHelpers.findClass(pkg + "SmartCandidateViewModel", cl);
            XposedHelpers.findAndHookMethod(item, "setSmartCandidate",
                    XposedHelpers.findClass("Kb.l", cl), int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object text = XposedHelpers.callMethod(param.thisObject, "getTextCandidate");
                                controller.onSmartCandidateShown(text == null ? null : text.toString());
                            } catch (Throwable t) {
                                controller.debug("smart candidate text unavailable: " + t);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(item, "onClickCandidate", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    controller.onSmartCandidateUsed();
                }
            });
            Class<?> container = XposedHelpers.findClass(pkg + "SmartCandidateContainerViewModel", cl);
            XposedHelpers.findAndHookMethod(container, "onClickCloseButton", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    controller.onSmartCandidateUsed();
                }
            });
            XposedHelpers.findAndHookMethod(container, "setSmartCandidateVisibility", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if ((Boolean) param.args[0]) {
                                controller.onSmartCandidateVisible();
                            } else {
                                controller.onSmartCandidateHidden();
                            }
                        }
                    });
            XposedBridge.log("OldHangul: smart candidate (clipboard suggestion) hooks installed");
        } catch (Throwable t) {
            XposedBridge.log("OldHangul: smart candidate hooks unavailable: " + t);
        }
    }

    /** Tapping a suggestion replaces the word being composed here with Samsung's text. */
    private static void hookCandidatePick(ClassLoader cl, OldHangulController controller) {
        String[] classes = {
                "com.samsung.android.honeyboard.textboard.candidate.viewmodel.CandidateViewModel",
                "com.samsung.android.honeyboard.textboard.candidate.viewmodel.CandidateExpandSpellScrollItemViewModel",
        };
        for (String name : classes) {
            try {
                Class<?> c = XposedHelpers.findClass(name, cl);
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals("pickSuggestion")) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                controller.onCandidatePicked();
                            }
                        });
                        XposedBridge.log("OldHangul: candidate pick hook installed " + name);
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log("OldHangul: candidate pick hook unavailable (" + name + "): " + t);
            }
        }
    }

    /**
     * Samsung's translation decides once whether to use downloaded language packs
     * (on device) or the server. When the packs cannot be listed or downloaded
     * (offline list is empty, "unsupported target for src") nothing translates, so
     * this answers "not on device" and the keyboard's own server path runs. Controlled by the
     * "translate_server" setting (on by default).
     */
    private static void hookTranslationServer(HookTargets targets, OldHangulController controller) {
        if (targets.translateOnDevice == null) {
            return;
        }
        XposedBridge.hookMethod(targets.translateOnDevice, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (controller.translateServer()) {
                    param.setResult(false);
                }
            }
        });
        XposedBridge.log("OldHangul: translation uses the server " + targets.translateOnDevice);
    }

    /** Intercepts Android Inline Suggestions API responses for candidate previews. */
    private static void hookInlineSuggestions(ClassLoader cl, OldHangulController controller) {
        try {
            Class<?> serviceClass = XposedHelpers.findClass(SERVICE, cl);
            XposedHelpers.findAndHookMethod(serviceClass, "onInlineSuggestionsResponse",
                    "android.view.inputmethod.InlineSuggestionsResponse", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args[0] != null) {
                                controller.onInlineSuggestionsResponse(param.args[0]);
                            }
                        }
                    });
            XposedBridge.log("OldHangul: InlineSuggestions API hooked");
        } catch (Throwable t) {
            XposedBridge.log("OldHangul: InlineSuggestions API hook unavailable: " + t);
        }
    }
}
