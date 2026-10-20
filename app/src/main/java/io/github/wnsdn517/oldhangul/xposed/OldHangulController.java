package io.github.wnsdn517.oldhangul.xposed;

import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import io.github.wnsdn517.oldhangul.Prefs;
import io.github.wnsdn517.oldhangul.engine.HangulComposer;
import io.github.wnsdn517.oldhangul.engine.Jamo;
import io.github.wnsdn517.oldhangul.engine.Laughter;

import java.util.Random;
import java.util.ArrayDeque;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * Takes over Korean dubeolsik composition inside Samsung Keyboard.
 *
 * <p>Samsung composes Korean in its native XT9 engine, which cannot produce
 * archaic jamo. While enabled, jamo keys are composed with {@link HangulComposer}
 * and Samsung's own text input for those keys is switched off; the rest of
 * Samsung's key action (vibration, sound, releasing Shift, redrawing the
 * keyboard) still runs. Every other key first commits the composing text.
 *
 * <p>Samsung must not replace the text while a syllable is composed here: its
 * setComposingText/setComposingRegion calls are blocked meanwhile (see
 * {@link #isComposing}), backspace over Hangul is handled here, and every edit
 * first checks that our composing text is still right before the cursor and
 * rewrites it, so a dropped composing region never duplicates text.
 *
 * <p>All calls arrive on the keyboard's main thread.
 */
final class OldHangulController {

    /** Actions that must not end the current syllable (they fire around every key press). */
    private static final String[] NEUTRAL_ACTIONS = {
            "SoundAndVibrationKeyA", "ShiftKeyA", "ShiftKeyCancelA", "PreUpdateShiftStateKeyA",
            "CapslockKeyA", "AltKeyA", "DexCtrlKeyA",
    };
    private static final String CHARACTER_ACTION = "CharacterKeyA";
    private static final String BACKSPACE_ACTION = "BackspaceKeyA";
    private static final String SPACE_ACTION = "SpaceKeyA";
    /** Key code of the sound/vibration event Samsung sends when any key goes down. */
    private static final int KEY_DOWN_FEEDBACK = -202;

    /** Vowel keys that exist on dubeolsik but not on 천지인 / 나랏글 layouts. */
    private static final String DUBEOLSIK_ONLY = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅛㅜㅠ";
    /** 천지인's ㆍ key sends U+119E (dubeolsik's Shift+ㅏ sends ㆍ, U+318D). */
    private static final int CHEONJIIN_ARAEA = 0x119E;
    /** 방점 (tone marks) of the Old Korean IME's Shift layer: single dot 거성, double dot 상성. */
    static final char TONE_SINGLE = '\u302E';
    static final char TONE_DOUBLE = '\u302F';


    private static final long SUPPRESS_TIMEOUT_MS = 10_000;
    private static final long REPEAT_MAX_MS = 30_000;

    private final XSharedPreferences prefs;
    private final HangulComposer composer = new HangulComposer(true, true);
    private final Handler handler = new Handler(Looper.getMainLooper());

    private InputMethodService service;

    private boolean enabled = true;
    private boolean longPressRepeat = true;
    private boolean longPressArchaic = true;
    private boolean archaic = true;
    private boolean recapture = true;
    private boolean splitOnSpace = true;
    private boolean debugLog;
    private boolean preloadJapanese;
    private boolean unlimitedSize;
    private boolean translateServer = true;
    private boolean numberSwipe;
    private int clipboardColumns;
    private boolean typingMeter = true;
    private boolean calcTools = true;
    private boolean undoButton = true;

    /** Whether the current Korean layout is dubeolsik (the only layout composed here). */
    private boolean dubeolsik = true;
    /** Samsung told us the current language (see {@link #onLanguage}). */
    private boolean languageKnown;
    /** The current language is Korean with a dubeolsik (qwerty) layout. */
    private boolean koreanQwerty;
    /** True while Samsung is using key-based language detection for bilingual input. */
    private boolean bilingualKeyMode;
    private boolean multilingual;
    /** Experimental: Samsung's engine also gets the modern jamo keys (see Prefs.SAMSUNG_SUGGEST). */
    private boolean samsungSuggest;
    /**
     * Samsung's engine did not see every key of this word (an archaic jamo or tone mark was
     * swallowed), so what it would commit for the word can differ from what is in the editor.
     */
    private boolean samsungDesynced;
    /**
     * A key action that ends the word (space, enter...) is running while Samsung's engine still
     * holds that word: its text for the word must not reach the editor, which has it already.
     */
    private boolean samsungWordPending;
    /** A suggestion was just picked: Samsung's text replaces the word until then. */
    private long candidatePickUntil;
    /**
     * Multilingual typing: Samsung's engine decided this word is not Korean (it sent
     * Latin text), so it owns the word until it ends and keys are left to it.
     */
    private boolean samsungWord;
    private boolean samsungShifted;
    private boolean shiftArchaic = true;
    /** Korean was typed more recently than a letter of another script. */
    private boolean koreanActive;
    /** Still inside the word being typed: backspace takes it apart jamo by jamo. */
    private boolean inWord;
    /** Composing text last sent to the editor. */
    private String composing = "";
    /**
     * The editor shows no composing text (Termux and other terminals): every
     * change is committed right away, replacing what changed with backspaces.
     */
    private boolean directMode;
    private boolean directInput = true;
    /** The editor reported no composing region while we were composing. */
    private boolean composingMaybeLost;
    private boolean internalEdit;
    /**
     * When the module last edited the text. Selection updates that arrive shortly
     * after are the editor echoing that edit; apps that apply input asynchronously
     * report positions from before it, which must not be taken for a cursor move.
     */
    private long lastEditAt;
    private static final long EDIT_ECHO_MS = 500;
    /** Cursor position observed after our last edit; -1 means not known yet. */
    private int lastSelection = -1;
    /** Samsung's accelerating delete took over a held backspace. */
    private boolean samsungRepeating;
    private boolean fastDelete = true;
    /** The last key split an archaic cluster; a second space is a real space, backspace undoes it. */
    private boolean justSplit;
    /** The user undid a split: the next space keeps the cluster and types a space. */
    private boolean splitDeclined;
    /** The current backspace press was handled here. */
    private boolean backspaceOwned;
    /** Key code whose release is ignored after its long press was handled. */
    private int suppressedRelease;
    private long suppressedAt;
    private final ArrayDeque<String> editHistory = new ArrayDeque<>();
    private android.widget.PopupWindow undoPanel;
    private final TypingStats typingStats = new TypingStats();
    private android.widget.TextView typingMeterView;
    private android.graphics.drawable.GradientDrawable meterBackground;
    private android.view.ViewGroup typingMeterParent;
    private android.widget.PopupWindow typingPanel;
    private android.view.View typingOverlay;
    private android.view.WindowManager typingOverlayManager;
    private long lastHistoryAt;
    /** Last time the meter text was redrawn; keystrokes only schedule a redraw. */
    private long lastMeterUpdateAt;
    private final Runnable meterTick = this::updateTypingMeterNow;
    private boolean meterPending;
    /** Recent committed text for the calculator / unit tools (bounded). */
    private final StringBuilder recentText = new StringBuilder();
    private ToolSuggester.Result toolResult;

    /** Letters of a held ㅋ, or null when not repeating. */
    private Laughter repeating;
    private Laughter.Style laughStyle = Laughter.Style.PLAIN;
    /** Preference value for vowelIeung; applied dynamically based on inWord. */
    private boolean vowelIeungPref = true;
    private final Random random = new Random();
    private long repeatStartedAt;
    private final Runnable repeatTick = new Runnable() {
        @Override
        public void run() {
            if (repeating == null) {
                return;
            }
            InputConnection ic = inputConnection();
            if (ic == null || SystemClock.uptimeMillis() - repeatStartedAt > REPEAT_MAX_MS) {
                stopRepeat();
                return;
            }
            ic.commitText(String.valueOf(repeating.next()), 1);
            handler.postDelayed(this, repeating.nextDelayMs());
        }
    };

    OldHangulController(XSharedPreferences prefs) {
        this.prefs = prefs;
        composer.setKeepWord(true);
        reloadPrefs();
    }

    void attach(InputMethodService service) {
        this.service = service;
        CurrencyFeed.start(service.getFilesDir());
        // The tool button belongs to the keyboard chrome, so create it as soon
        // as the keyboard window exists rather than waiting for the first key.
        handler.post(this::updateTypingMeter);
    }

    void ensureUndoButton() {
        updateTypingMeter();
        handler.postDelayed(this::updateTypingMeter, 350);
        handler.postDelayed(this::updateTypingMeter, 1000);
    }

    void reloadPrefs() {
        if (prefs.hasFileChanged()) {
            prefs.reload();
        }
        enabled = prefs.getBoolean(Prefs.ENABLED, true);
        archaic = prefs.getBoolean(Prefs.ARCHAIC, true);
        longPressRepeat = prefs.getBoolean(Prefs.LONG_PRESS_KKK, true);
        longPressArchaic = prefs.getBoolean(Prefs.LONG_PRESS_ARCHAIC, true);
        recapture = prefs.getBoolean(Prefs.RECAPTURE, true);
        splitOnSpace = prefs.getBoolean(Prefs.SPLIT_ON_SPACE, true);
        debugLog = prefs.getBoolean(Prefs.DEBUG_LOG, false);
        preloadJapanese = prefs.getBoolean(Prefs.PRELOAD_JAPANESE, true);
        unlimitedSize = prefs.getBoolean(Prefs.UNLIMITED_SIZE, true);
        translateServer = prefs.getBoolean(Prefs.TRANSLATE_SERVER, true);
        numberSwipe = prefs.getBoolean(Prefs.NUMBER_SWIPE, true);
        try {
            clipboardColumns = Integer.parseInt(
                    prefs.getString(Prefs.CLIPBOARD_COLUMNS, Prefs.CLIPBOARD_COLUMNS_DEFAULT));
        } catch (NumberFormatException e) {
            clipboardColumns = 0;
        }
        fastDelete = prefs.getBoolean(Prefs.FAST_DELETE, true);
        directInput = prefs.getBoolean(Prefs.DIRECT_INPUT, true);
        shiftArchaic = prefs.getBoolean(Prefs.SHIFT_ARCHAIC, true);
        samsungSuggest = prefs.getBoolean(Prefs.SAMSUNG_SUGGEST, true);
        typingMeter = prefs.getBoolean(Prefs.TYPING_METER, true);
        calcTools = prefs.getBoolean(Prefs.CALC_TOOLS, true);
        CurrencyTool.setEnabled(prefs.getBoolean(Prefs.CURRENCY, true));
        if (service != null) CurrencyFeed.refreshIfStale();
        undoButton = prefs.getBoolean(Prefs.UNDO_BUTTON, true);
        notifyShiftLayer();
        laughStyle = Laughter.Style.of(prefs.getString(Prefs.LAUGH_MIX, Prefs.LAUGH_MIX_OFF));
        composer.configure(archaic, prefs.getBoolean(Prefs.AUTO_IEUNG, true));
        vowelIeungPref = prefs.getBoolean(Prefs.VOWEL_IEUNG, true);
        composer.setVowelIeung(vowelIeungPref && inWord);
    }

    /** Most clipboard tiles per row, or 0 to leave Samsung's layout alone. */
    int clipboardColumns() {
        return enabled ? clipboardColumns : 0;
    }

    /** Long-press vibration for presses handled here (Samsung's own is skipped with its long press). */
    void longPressHaptic() {
        try {
            if (service == null || service.getWindow() == null) return;
            service.getWindow().getWindow().getDecorView().performHapticFeedback(
                    android.view.HapticFeedbackConstants.LONG_PRESS,
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        } catch (RuntimeException e) {
            debug("haptic failed: " + e);
        }
    }

    // ---------------------------------------------------------------- clipboard chip

    private String smartText = "";
    private boolean smartUsed;
    /** The chip while it sits in Samsung's candidate row, and what it changed there. */
    private android.view.View clipChip;
    private android.view.ViewGroup clipRoot;
    private android.view.View clipHolder;
    private Object clipHolderParams;
    private int clipHolderEndToStart;
    private java.lang.reflect.Field clipEndToStart;
    private final Runnable clipChipExpire = this::removeClipChip;

    void onSmartCandidateShown(String text) {
        smartText = text == null ? "" : text;
        smartUsed = false;
        debug("smart candidate shown: " + smartText);
    }

    /** Tapped or closed by the user: nothing to keep. */
    void onSmartCandidateUsed() {
        smartUsed = true;
        removeClipChip();
    }

    void onSmartCandidateVisible() {
        removeClipChip();
    }

    /** Samsung drops its suggestion (typing started, timeout...): keep it as a small chip. */
    void onSmartCandidateHidden() {
        if (!enabled || smartUsed || smartText.isEmpty() || !prefs.getBoolean(Prefs.CLIP_CHIP, true)) {
            return;
        }
        // Samsung rebuilds its candidate row right after hiding the suggestion; try again if it is not there yet.
        final String text = smartText;
        handler.postDelayed(() -> { if (!showClipChip(text)) handler.postDelayed(() -> showClipChip(text), 300); }, 150);
    }

    /**
     * Puts the chip into Samsung's candidate row (a ConstraintLayout: close button | candidates |
     * expand button) between the candidates and the expand button, and ends the candidates at the
     * chip instead of the expand button, so the words slide over and nothing is covered.
     */
    private boolean showClipChip(String text) {
        if (service == null || service.getWindow() == null || smartUsed || clipChip != null) return clipChip != null;
        try {
            android.view.View decor = service.getWindow().getWindow().getDecorView();
            android.view.View row = findIdView(decor, "candidate_view");
            android.view.View holder = findIdView(decor, "candidates_holder");
            android.view.View expand = findIdView(decor, "candidate_expand_button_frame");
            if (!(row instanceof android.view.ViewGroup) || holder == null || expand == null
                    || row.getVisibility() != android.view.View.VISIBLE) {
                debug("clip chip: candidate row not ready");
                return false;
            }
            android.view.ViewGroup.LayoutParams rowParams = holder.getLayoutParams();
            boolean constraint = false;
            for (Class<?> c = row.getClass(); c != null; c = c.getSuperclass()) {
                constraint |= c.getName().contains("ConstraintLayout");
            }
            if (rowParams == null || !constraint) {
                debug("clip chip: candidate row is not a ConstraintLayout");
                return false;
            }
            // Samsung ships ConstraintLayout.LayoutParams renamed (androidx...widget.d): its int
            // fields are found by position, in the library's declaration order, and checked
            // against the row's real constraints (the candidates start at the close button and end
            // at the expand button).
            java.lang.reflect.Field[] ints = constraintIntFields(rowParams.getClass());
            android.view.View closeFrame = findIdView(decor, "candidate_close_button_frame");
            if (ints == null || closeFrame == null
                    || ints[FIELD_START_TO_END].getInt(rowParams) != closeFrame.getId()
                    || ints[FIELD_END_TO_START].getInt(rowParams) != expand.getId()) {
                debug("clip chip: constraint fields do not match this build");
                return false;
            }
            float density = service.getResources().getDisplayMetrics().density;
            android.widget.LinearLayout chip = new android.widget.LinearLayout(service);
            chip.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding((int) (7 * density), 0, (int) (5 * density), 0);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0x33808080);
            bg.setCornerRadius(12 * density);
            chip.setBackground(bg);
            android.widget.ImageView icon = new android.widget.ImageView(service);
            int iconId = service.getResources().getIdentifier("ic_smartcandidate_clipboard", "drawable", service.getPackageName());
            if (iconId != 0) {
                android.graphics.drawable.Drawable d = service.getResources().getDrawable(iconId, null).mutate();
                d.setTint(0xFF8AB4F8);
                icon.setImageDrawable(d);
            }
            chip.addView(icon, new android.widget.LinearLayout.LayoutParams((int) (16 * density), (int) (16 * density)));
            // Only the first few characters: it is a reminder, the words are what matter.
            String shown = text.replace('\n', ' ').trim();
            if (shown.codePointCount(0, shown.length()) > 3) {
                shown = shown.substring(0, shown.offsetByCodePoints(0, 3)) + "…";
            }
            android.widget.TextView label = new android.widget.TextView(service);
            label.setText(shown);
            label.setTextColor(0xFFC7CBD0);
            label.setTextSize(12f);
            label.setSingleLine(true);
            label.setIncludeFontPadding(false);
            label.setPadding((int) (4 * density), 0, 0, 0);
            chip.addView(label);
            chip.setOnClickListener(v -> pasteClip(text));
            chip.setOnLongClickListener(v -> { smartUsed = true; removeClipChip(); return true; });
            chip.setContentDescription("복사한 글 붙여넣기");
            chip.setId(android.view.View.generateViewId());

            android.view.ViewGroup root = (android.view.ViewGroup) row;
            Object lp = rowParams.getClass().getConstructor(int.class, int.class).newInstance(-2, 0);
            ints[FIELD_TOP_TO_TOP].setInt(lp, 0);          // PARENT_ID
            ints[FIELD_BOTTOM_TO_BOTTOM].setInt(lp, 0);
            ints[FIELD_END_TO_START].setInt(lp, expand.getId());
            ((android.view.ViewGroup.MarginLayoutParams) lp).setMargins((int) (3 * density), (int) (8 * density),
                    (int) (3 * density), (int) (8 * density));
            clipHolderParams = holder.getLayoutParams();
            clipEndToStart = ints[FIELD_END_TO_START];
            clipHolderEndToStart = clipEndToStart.getInt(clipHolderParams);
            clipHolder = holder;
            clipRoot = root;
            root.addView(chip, (android.view.ViewGroup.LayoutParams) lp);
            clipEndToStart.setInt(clipHolderParams, chip.getId());
            holder.setLayoutParams((android.view.ViewGroup.LayoutParams) clipHolderParams);
            root.requestLayout();
            clipChip = chip;
            handler.removeCallbacks(clipChipExpire);
            handler.postDelayed(clipChipExpire, 120_000);
            debug("clip chip inserted in the candidate row for '" + shown + "'");
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            debug("clip chip failed: " + e);
            removeClipChip();
            return false;
        }
    }

    private void pasteClip(String text) {
        InputConnection ic = inputConnection();
        if (ic == null) return;
        commitSymbol(text);
        smartUsed = true;
        removeClipChip();
    }

    void onKeyboardHidden() {
        removeClipChip();
    }

    /** Takes the chip out and gives the candidates their old width back. */
    private void removeClipChip() {
        handler.removeCallbacks(clipChipExpire);
        try {
            if (clipHolder != null && clipHolderParams != null && clipEndToStart != null) {
                clipEndToStart.setInt(clipHolderParams, clipHolderEndToStart);
                clipHolder.setLayoutParams((android.view.ViewGroup.LayoutParams) clipHolderParams);
            }
            if (clipRoot != null && clipChip != null) {
                clipRoot.removeView(clipChip);
                clipRoot.requestLayout();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // the row is gone already
        }
        clipChip = null;
        clipRoot = null;
        clipHolder = null;
        clipHolderParams = null;
    }

    // Positions of the int fields of ConstraintLayout.LayoutParams in declaration order
    // (guideBegin, guideEnd, leftToLeft, leftToRight, rightToLeft, rightToRight, topToTop, topToBottom,
    // bottomToTop, bottomToBottom, baselineToBaseline, baselineToTop, baselineToBottom, circleConstraint,
    // circleRadius, startToEnd, startToStart, endToStart, endToEnd).
    private static final int FIELD_TOP_TO_TOP = 6;
    private static final int FIELD_BOTTOM_TO_BOTTOM = 9;
    private static final int FIELD_START_TO_END = 15;
    private static final int FIELD_END_TO_START = 17;

    private static java.lang.reflect.Field[] constraintIntFields(Class<?> lpClass) {
        java.util.ArrayList<java.lang.reflect.Field> out = new java.util.ArrayList<>();
        for (java.lang.reflect.Field f : lpClass.getDeclaredFields()) {
            if (f.getType() == int.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    && java.lang.reflect.Modifier.isPublic(f.getModifiers())) {
                f.setAccessible(true);
                out.add(f);
            }
        }
        return out.size() > FIELD_END_TO_START ? out.toArray(new java.lang.reflect.Field[0]) : null;
    }

    /** The view with this id name in Samsung's keyboard (ids are looked up by name: the package's R is obfuscated away). */
    private android.view.View findIdView(android.view.View root, String idName) {
        int id = service.getResources().getIdentifier(idName, "id", service.getPackageName());
        return id == 0 ? null : root.findViewById(id);
    }

    /** Suggestions Samsung just built, in creation order (see ModuleMain.hookCandidates). */
    void onCandidate(int index, String text, int kind) {
        if (debugLog && index == 0) debug("candidate top=" + text);
    }

    boolean languagePairToggle() {
        return enabled && prefs.getBoolean(Prefs.LANGUAGE_PAIR, true);
    }

    boolean numberSwipe() {
        return enabled && numberSwipe;
    }

    boolean translateServer() {
        reloadPrefs();
        return translateServer;
    }

    boolean unlimitedSize() {
        return unlimitedSize;
    }

    boolean preloadJapanese() {
        return preloadJapanese;
    }

    boolean undoButtonEnabled() {
        return enabled && undoButton;
    }

    boolean typingMeterEnabled() {
        return enabled && typingMeter;
    }

    boolean debugLog() {
        return debugLog;
    }

    /** Keyboard service context (the Samsung Keyboard package), or null before onCreate. */
    android.content.Context serviceContext() {
        return service;
    }

    /** Writes to the LSPosed log only while the debug log option is on. */
    void debug(String message) {
        if (debugLog) {
            XposedBridge.log("OldHangul: " + message);
        }
    }

    /**
     * Samsung switched language or layout. Only Korean dubeolsik is composed
     * here; every other language (and 천지인, 나랏글, 단모음) is left to Samsung.
     */
    /** Told whenever the Shift layer may have turned on or off (see {@link #shiftLayerActive}). */
    private Runnable shiftLayerListener;

    void setShiftLayerListener(Runnable listener) {
        shiftLayerListener = listener;
        listener.run();
    }

    private Runnable labelCacheClearer;

    void setLabelCacheClearer(Runnable clearer) {
        labelCacheClearer = clearer;
    }

    private void notifyShiftLayer() {
        if (labelCacheClearer != null) {
            labelCacheClearer.run();
        }
        if (shiftLayerListener != null) {
            shiftLayerListener.run();
        }
    }

    /**
     * @param bilingual Samsung's multilingual typing is on for this language: it
     *     may be another language with Korean mixed in, so the keys decide.
     */
    void onLanguage(String languageCode, String inputType, boolean bilingual) {
        boolean korean = "ko".equalsIgnoreCase(languageCode);
        String type = inputType == null ? "" : inputType.toLowerCase(java.util.Locale.ROOT);
        boolean other = type.contains("phonepad") || type.contains("chunjiin") || type.contains("naratgul")
                || type.contains("vega") || type.contains("single_vowel");
        boolean qwerty = korean && !other;
        // In multilingual mode the language callback is not authoritative:
        // Samsung often reports the currently selected language, not the key
        // actually pressed.  Let onCharacter() decide from the real key code.
        boolean byKeys = bilingual;
        // In bilingual mode languageKnown intentionally remains false.  The old
        // condition treated that sentinel as "not initialized" on every callback,
        // repeatedly resetting key-language detection and losing the active side.
        if (byKeys ? bilingualKeyMode
                : languageKnown && !bilingualKeyMode && qwerty == koreanQwerty && bilingual == multilingual) {
            return;
        }
        commitComposing();
        resetState();
        // Leaving Korean: nothing of ours may stay composing, or Samsung's own
        // composing (kana, swipe words) would be blocked.
        languageKnown = !byKeys;
        bilingualKeyMode = byKeys;
        multilingual = bilingual;
        koreanQwerty = qwerty;
        dubeolsik = qwerty || !korean;
        koreanActive = qwerty;
        // Other scripts are gated by koreanLayoutActive(), so Korean features
        // remain available after an actual Korean key even in multilingual mode.
        boolean effectiveArchaic = archaic;
        boolean effectiveAutoIeung = prefs.getBoolean(Prefs.AUTO_IEUNG, true);
        composer.configure(effectiveArchaic, effectiveAutoIeung);
        vowelIeungPref = prefs.getBoolean(Prefs.VOWEL_IEUNG, true) && !bilingual;
        composer.setVowelIeung(false); // reset; will be set dynamically in type()
        notifyShiftLayer();
        if (debugLog) {
            de.robv.android.xposed.XposedBridge.log("OldHangul: language " + languageCode + "/" + inputType
                    + (bilingual ? " (multilingual)" : "") + " -> "
                    + (byKeys ? "decided by keys" : qwerty ? "composing here" : "left to Samsung"));
        }
    }

    /** Korean dubeolsik is active, as far as we know. */
    private boolean koreanLayoutActive() {
        return languageKnown ? koreanQwerty : koreanActive && dubeolsik;
    }

    /**
     * The archaic letter a key shows and types while Shift is on (ㅇ → ㆁ), or 0.
     * Samsung sends the Shift layer's code itself, so this only feeds the labels.
     */
    char shiftVariant(int code) {
        if (!enabled || !archaic || !shiftArchaic || !koreanKeyCode(code)) {
            return 0;
        }
        return archaicVariant(code);
    }

    /**
     * Latin key codes (z x c ... in the multilingual layout, whose keys carry the
     * Korean and the Latin code) only count while a Korean layout is showing; an
     * English-only keyboard keeps its letters.
     */
    private boolean koreanKeyCode(int code) {
        boolean latin = (code >= 'a' && code <= 'z') || (code >= 'A' && code <= 'Z');
        return !latin || koreanLayoutActive() || multilingual;
    }

    /** Same lookup, using the label Samsung actually renders on this build. */
    char shiftVariant(String label, int code) {
        if (label != null) {
            boolean latin = false;
            for (int i = 0; i < label.length();) {
                int cp = label.codePointAt(i);
                if (cp <= Character.MAX_VALUE && !((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z'))) {
                    char variant = shiftVariant(cp);
                    if (variant != 0) return variant;
                } else {
                    latin = true;
                }
                i += Character.charCount(cp);
            }
            if (latin) {
                return 0; // a Latin key label is an English key, whatever code it also carries
            }
        }
        return shiftVariant(code);
    }

    boolean internalEditActive() {
        return internalEdit;
    }

    /** A syllable is being composed here; Samsung must leave the composing text alone. */
    boolean isComposing() {
        return enabled && !composer.isEmpty() && koreanLayoutActive();
    }

    /** Another language is active and nothing of ours is pending: key actions can be ignored cheaply. */
    boolean idleInOtherLanguage() {
        return !enabled || (languageKnown && !koreanQwerty && composer.isEmpty() && repeating == null);
    }

    /** Cheap check for the hot label hook: is the archaic Shift layer in use right now? */
    boolean shiftLayerActive() {
        // In multilingual mode the language callback can still say “English”
        // while the shared keyboard is showing the Korean layer. Do not unhook
        // the label replacement based on that callback.
        return enabled && archaic && shiftArchaic;
    }

    void setSamsungShifted(boolean shifted) {
        samsungShifted = shifted;
    }

    boolean samsungShifted() {
        return samsungShifted;
    }

    // ------------------------------------------------------------ key actions

    /**
     * Called before Samsung runs a key action. Returns true when the key was
     * handled here and Samsung's text input for it must be switched off.
     *
     */
    boolean beforeKeyAction(String action, Object keyRequestInfo) {
        if (!enabled) {
            return false;
        }
        KeyEventInfo key = KeyEventInfo.parse(keyRequestInfo);
        if (key == null) {
            return false;
        }
        // Test-only trace for language/enter/send keys: short vs long press is
        // distinguished here by touchAction (1=down, 2=up, 3=repeat) and in
        // ModuleMain.hookLongPress by the presenter code. Ql/H LanguageKeyA
        // uses -401 for the language-select dialog; other codes just switch.
        // Remapping short=KO/EN + long=JA is NOT done here: without device
        // logs for the exact codes it would break language switching.
        if (debugLog && action.contains("Language")) {
            debug("language key action=" + action + " code=" + key.keyCode
                    + " label=" + key.label + " touch=" + key.touchAction);
        }
        if (key.keyCode == KEY_DOWN_FEEDBACK) {
            // A new key press: a pending long-press release can no longer arrive.
            suppressedRelease = 0;
            stopRepeat();
        }
        for (String neutral : NEUTRAL_ACTIONS) {
            if (neutral.equals(action)) {
                return false;
            }
        }
        stopRepeat();
        if (BACKSPACE_ACTION.equals(action)) {
            return onBackspace(key);
        }
        boolean wasSplit = justSplit;
        justSplit = false;
        if (CHARACTER_ACTION.equals(action)) {
            splitDeclined = false;
            return onCharacter(key);
        }
        boolean declined = splitDeclined;
        splitDeclined = false;
        if (SPACE_ACTION.equals(action) && splitOnSpace && !wasSplit && !declined && archaic
                && !composer.isEmpty()) {
            HangulComposer.Output out = composer.splitLastArchaic();
            if (out != null) {
                rewrite(out);
                justSplit = true;
                return true;
            }
        }
        boolean hadWord = !composer.isEmpty() || samsungDesynced;
        // Finishing the word and Samsung's own commit of the blank (space, newline) that follows
        // are two calls; asynchronous editors (Chrome, WebView) sometimes lost the blank between
        // them. Hold both in one batch until Samsung's action is done (see afterKeyAction).
        if (!composer.isEmpty() && !directMode) {
            openActionBatch();
        }
        commitComposing();
        samsungWordPending = (multilingual || samsungSuggest) && hadWord && !samsungWord;
        samsungDesynced = false;
        inWord = false;
        return false;
    }

    /** Samsung's key action finished. */
    void afterKeyAction() {
        samsungWordPending = false;
        closeActionBatch();
    }

    private InputConnection actionBatch;
    private final Runnable actionBatchTimeout = this::closeActionBatch;

    private void openActionBatch() {
        closeActionBatch();
        InputConnection ic = inputConnection();
        if (ic == null) return;
        try {
            ic.beginBatchEdit();
            actionBatch = ic;
            // Never leave the editor in a batch if the key action does not come back.
            handler.postDelayed(actionBatchTimeout, 500);
        } catch (RuntimeException e) {
            debug("batch open failed: " + e);
        }
    }

    private void closeActionBatch() {
        InputConnection ic = actionBatch;
        if (ic == null) return;
        actionBatch = null;
        handler.removeCallbacks(actionBatchTimeout);
        try {
            ic.endBatchEdit();
        } catch (RuntimeException e) {
            debug("batch close failed: " + e);
        }
    }

    /** A suggestion / Hanja candidate was tapped: Samsung is about to put it in place of the word. */
    void onCandidatePicked() {
        debug("candidate picked, composing=" + composing);
        candidatePickUntil = SystemClock.uptimeMillis() + 1200;
        composer.reset();
        composing = "";
        composingMaybeLost = false;
        inWord = false;
        samsungDesynced = false;
        samsungWordPending = false;
    }

    private static boolean isBlank(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) return false;
        }
        return true;
    }

    private boolean onCharacter(KeyEventInfo key) {
        int code = key.keyCode;
        if (suppressedRelease != 0 && code == suppressedRelease
                && SystemClock.uptimeMillis() - suppressedAt < SUPPRESS_TIMEOUT_MS) {
            suppressedRelease = 0;
            return true;
        }
        suppressedRelease = 0;
        detectLayout(key);
        char jamo = compositionKey(key);
        boolean koreanKey = jamo != 0;
        if (koreanKey) {
            koreanActive = true;
        } else if (isForeignLetter(key)) {
            // A letter of another script (Latin, kana, ...): another language is active.
            koreanActive = false;
        }
        if (!dubeolsik || !koreanKey) {
            // Not counted here: the text Samsung sends to the editor is the single
            // source for non-Korean typing (see onExternalComposing/onExternalCommit).
            commitComposing();
            inWord = false;
            if (debugLog) {
                debug("foreign key label=" + key.label + " code=" + code
                        + " koreanActive=" + koreanActive);
            }
            return false;
        }
        if (multilingual) {
            return typeMultilingual(jamo, key);
        }
        if (samsungSuggest) {
            type(jamo);
            if (!modernJamo(jamo)) {
                samsungDesynced = true;
                return true;
            }
            return false;
        }
        return type(jamo);
    }

    private static boolean isKoreanInputKey(int code) {
        return Jamo.isKey(code) || code == CHEONJIIN_ARAEA
                || code == Jamo.PANSIOS || code == Jamo.YESIEUNG
                || code == Jamo.YEORINHIEUH || code == Jamo.ARAEA
                || isTone(code);
    }

    static boolean isTone(int code) {
        return code == TONE_SINGLE || code == TONE_DOUBLE;
    }

    /** Samsung key codes are not stable across multilingual keyboard layouts. */
    private static boolean isKoreanInputKey(KeyEventInfo key) {
        String label = key.label == null ? "" : key.label;
        // The visible label wins over a stale physical-layout code. In
        // multilingual mode an English key can retain the Korean key code
        // (for example ㅗ) while its actual label is "d" or "i". If the label
        // holds an ASCII letter, it is a foreign key even when a Korean jamo
        // is also present (e.g. a stale code behind the label).
        if (containsAsciiLetter(label)) return false;
        for (int i = 0; i < label.length(); ) {
            int cp = label.codePointAt(i);
            if (isKoreanInputKey(cp)) return true;
            i += Character.charCount(cp);
        }
        boolean hasLetter = false;
        for (int i = 0; i < label.length(); ) {
            int cp = label.codePointAt(i);
            if (Character.isLetter(cp)) hasLetter = true;
            i += Character.charCount(cp);
        }
        if (hasLetter) return false;
        return isKoreanInputKey(key.keyCode);
    }

    private static boolean containsAsciiLetter(String label) {
        for (int i = 0; i < label.length(); ) {
            int cp = label.codePointAt(i);
            if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z')) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean isForeignLetter(KeyEventInfo key) {
        String label = key.label == null ? "" : key.label;
        if (!label.isEmpty()) {
            for (int i = 0; i < label.length(); ) {
                int cp = label.codePointAt(i);
                if (Character.isLetter(cp)) {
                    return !isKoreanInputKey(cp);
                }
                i += Character.charCount(cp);
            }
        }
        return Character.isLetter(key.keyCode)
                && Character.UnicodeScript.of(key.keyCode) != Character.UnicodeScript.HANGUL;
    }

    /**
     * Multilingual typing: both languages share the Korean keys and only Samsung's
     * engine knows which one the word is in. So the key is composed here as Korean
     * AND left to Samsung (not swallowed): while Samsung agrees on Korean its text
     * is blocked; the moment it sends Latin text the word is English, ours is dropped
     * and Samsung's text goes through (see {@link #allowSamsungComposition}).
     */
    private boolean typeMultilingual(char jamo, KeyEventInfo key) {
        if (samsungWord) {
            return false;
        }
        type(jamo);
        if (!modernJamo(jamo)) {
            // Samsung does not know ㅿ ᄼ 〮 ...: keep them to ourselves, and remember its engine missed one.
            samsungDesynced = true;
            return true;
        }
        return false;
    }

    private static boolean modernJamo(char c) {
        return c >= 0x3131 && c <= 0x3163;
    }

    private void clearWordLanguage() {
        samsungWord = false;
    }

    /** Normalizes Samsung's key label to the jamo accepted by the composer. */
    private static char compositionKey(KeyEventInfo key) {
        String label = key.label == null ? "" : key.label;
        // "hello" must never become Korean: an ASCII letter on the label means
        // the physical key is English right now, regardless of a stale Korean
        // key code Samsung left behind (Dm/b KeyRequestInfo keyCode reuse).
        if (containsAsciiLetter(label)) return 0;
        for (int i = 0; i < label.length();) {
            int cp = label.codePointAt(i);
            if (cp <= Character.MAX_VALUE && isKoreanInputKey(cp)) return (char) cp;
            i += Character.charCount(cp);
        }
        // A few builds expose the rendered vowel syllable instead of its jamo.
        switch (label) {
            case "아": return 'ㅏ'; case "애": return 'ㅐ'; case "야": return 'ㅑ';
            case "얘": return 'ㅒ'; case "어": return 'ㅓ'; case "에": return 'ㅔ';
            case "여": return 'ㅕ'; case "예": return 'ㅖ'; case "오": return 'ㅗ';
            case "요": return 'ㅛ'; case "우": return 'ㅜ'; case "유": return 'ㅠ';
            case "으": return 'ㅡ'; case "이": return 'ㅣ';
            default:
                boolean hasForeignLetter = false;
                for (int i = 0; i < label.length();) {
                    int cp = label.codePointAt(i);
                    hasForeignLetter |= Character.isLetter(cp);
                    i += Character.charCount(cp);
                }
                return !hasForeignLetter && isKoreanInputKey(key.keyCode)
                        && key.keyCode <= Character.MAX_VALUE
                        ? (char) key.keyCode : 0;
        }
    }

    /** Samsung's setComposingText (cumulative span): feeds the meter and the tools. */
    void onExternalComposing(String text) {
        if (text == null) return;
        typingStats.onComposing(text);
        noteTyped(text.length() > 0 ? text.substring(text.length() - 1) : text);
        externalComposing = text;
        refreshToolTip();
        updateTypingMeter();
        if (debugLog) debug("external composing len=" + text.length() + " " + typingStats.summary());
    }

    /** Samsung's commitText. */
    void onExternalCommit(String text) {
        if (text == null || text.isEmpty()) return;
        typingStats.onCommit(text);
        appendRecent(text);
        externalComposing = "";
        updateTypingMeter();
        if (debugLog) debug("external commit len=" + text.length() + " " + typingStats.summary());
    }

    private String externalComposing = "";

    private void appendRecent(String text) {
        noteTyped(text);
        refreshToolTip();
    }

    private final Runnable toolRead = this::readToolFromEditor;

    /**
     * The tool answers come from the text the editor really has before the cursor
     * (not from a record of what was typed, which went stale after edits, cursor
     * moves and Send). Read once shortly after the last change.
     */
    private int charsSinceDigit = 99;

    private void noteTyped(CharSequence text) {
        if (text == null) return;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // digits, Korean numerals (오만원), currency symbols: something the tools may answer
            if ((c >= '0' && c <= '9') || c == '=' || c == '%'
                    || "일이삼사오육칠팔구십백천만억조".indexOf(c) >= 0 || "$€£¥￥₩₫฿₹₽₺₱₪₴".indexOf(c) >= 0) {
                charsSinceDigit = 0;
                return;
            }
        }
        charsSinceDigit += text.length();
    }

    /** Reads the editor only when a number was typed lately or an answer is showing (to update or clear it). */
    private void refreshToolTip() {
        if (!calcTools) {
            handler.removeCallbacks(toolRead);
            toolResult = null;
            return;
        }
        if (toolResult == null && charsSinceDigit > 12) {
            return;
        }
        handler.removeCallbacks(toolRead);
        handler.postDelayed(toolRead, 200);
    }

    private void readToolFromEditor() {
        InputConnection ic = inputConnection();
        ToolSuggester.Result r = null;
        if (ic != null && calcTools) {
            try {
                CharSequence before = ic.getTextBeforeCursor(48, 0);
                r = before == null ? null : ToolSuggester.analyze(before.toString());
            } catch (RuntimeException e) {
                r = null;
            }
        }
        String now = r == null ? "" : r.display;
        String was = toolResult == null ? "" : toolResult.display;
        toolResult = r;
        if (!now.equals(was)) {
            updateTypingMeterNow();
        }
    }

    private void detectLayout(KeyEventInfo key) {
        if (languageKnown) {
            return;
        }
        String label = key.label == null ? "" : key.label;
        // Decide from the visible label, not the possibly stale key code:
        // an ASCII label means the other language's layout is showing.
        if (containsAsciiLetter(label)) {
            return;
        }
        int code = key.keyCode;
        if (code == CHEONJIIN_ARAEA) {
            dubeolsik = false;
        } else if (DUBEOLSIK_ONLY.indexOf(code) >= 0) {
            dubeolsik = true;
        } else if (Jamo.isKey(code) && label.codePointCount(0, label.length()) > 1
                && !"null".equals(label)) {
            // 천지인 / 나랏글 consonant keys are labelled with several letters ("ㄱㅋ").
            dubeolsik = false;
        } else if (!label.isEmpty()) {
            for (int i = 0; i < label.length(); ) {
                int cp = label.codePointAt(i);
                if (DUBEOLSIK_ONLY.indexOf(cp) >= 0) {
                    dubeolsik = true;
                    break;
                }
                i += Character.charCount(cp);
            }
        }
        if (debugLog) {
            debug("detectLayout label=" + label + " code=" + code + " dubeolsik=" + dubeolsik);
        }
    }

    private boolean onBackspace(KeyEventInfo key) {
        if (key.touchAction != KeyEventInfo.TOUCH_UP) {
            refreshToolTip();
        }
        if (composer.isEmpty() && !koreanLayoutActive()) {
            backspaceOwned = false;
            return false;
        }
        switch (key.touchAction) {
            case KeyEventInfo.TOUCH_UP: {
                // Samsung's release cleanup runs whenever Samsung deleted something itself.
                boolean owned = backspaceOwned && !samsungRepeating;
                backspaceOwned = false;
                samsungRepeating = false;
                return owned;
            }
            case KeyEventInfo.TOUCH_REPEAT:
                if (fastDelete && composer.isEmpty()) {
                    // Samsung's own held delete, which speeds up to whole words.
                    samsungRepeating = true;
                    return false;
                }
                // Otherwise a held backspace is handled here at one steady pace.
                return backspace(true);
            case KeyEventInfo.TOUCH_DOWN:
            case 0:
                samsungRepeating = false;
                backspaceOwned = backspace(false);
                return backspaceOwned;
            default:
                return false;
        }
    }

    /** Long press on a key. Returns true when handled (Samsung's long press is skipped). */
    boolean onLongPress(int code) {
        if (!enabled || !dubeolsik || !Jamo.isKey(code)) {
            return false;
        }
        InputConnection ic = inputConnection();
        if (ic == null) {
            return false;
        }
        if (code == 'ㅋ' && longPressRepeat) {
            commitComposing();
            inWord = false;
            repeating = new Laughter(laughStyle, random);
            ic.commitText(repeating.burst(), 1);
            repeatStartedAt = SystemClock.uptimeMillis();
            handler.postDelayed(repeatTick, repeating.nextDelayMs());
        } else {
            return false;
        }
        suppressedRelease = code;
        suppressedAt = SystemClock.uptimeMillis();
        return true;
    }

    /** Any key touch (down or up) ends a long-press repeat. */
    void onKeyTouch() {
        stopRepeat();
    }

    private void stopRepeat() {
        if (repeating != null) {
            repeating = null;
            handler.removeCallbacks(repeatTick);
        }
    }

    /**
     * The archaic sibling of a key: shown and typed on the Shift layer and typed by
     * swiping the key down (Old Korean IME layout). ㄱ ㄷ ㅂ ㅅ ㅈ are left alone so
     * Samsung's own Shift still gives ㄲ ㄸ ㅃ ㅆ ㅉ.
     */
    static char archaicVariant(int code) {
        switch (code) {
            case 'ㄹ': case 'f': case 'F': return Jamo.PANSIOS;     // ㅿ 반치음
            case 'ㅇ': case 'd': case 'D': return Jamo.YESIEUNG;    // ㆁ 옛이응
            case 'ㅎ': case 'g': case 'G': return Jamo.YEORINHIEUH; // ㆆ 여린히읗
            case 'ㅏ': case 'k': case 'K': return Jamo.ARAEA;       // ㆍ 아래아
            case 'ㅋ': case 'z': case 'Z': return 'ᄼ';              // 치두음 ㅅ
            case 'ㅌ': case 'x': case 'X': return 'ᄾ';              // 정치음 ㅅ
            case 'ㅊ': case 'c': case 'C': return 'ᅎ';              // 치두음 ㅈ
            case 'ㅍ': case 'v': case 'V': return 'ᅐ';              // 정치음 ㅈ
            case 'ㅠ': case 'b': case 'B': return 'ᅔ';              // 치두음 ㅊ
            case 'ㅜ': case 'n': case 'N': return 'ᅕ';              // 정치음 ㅊ
            case 'ㅓ': case 'j': case 'J': return TONE_SINGLE;      // 방점: once 〮, again 〯, again removes it
            default: return 0;
        }
    }

    /** What swiping a key down types, or 0. The top row keeps its digits; ㅓ swipes the tone mark. */
    char swipeSymbol(int code) {
        if (!enabled || !archaic || !longPressArchaic) {
            return 0;
        }
        if (!koreanKeyCode(code)) {
            return 0;
        }
        return archaicVariant(code);
    }

    boolean swipeArchaic() {
        return enabled && archaic && longPressArchaic;
    }

    /**
     * The tone key: the first press adds 〮 (거성) after the syllable, pressing again turns it into
     * 〯 (상성), a third press removes it.
     */
    private boolean typeTone() {
        InputConnection ic = inputConnection();
        if (ic == null) {
            return false;
        }
        commitComposing();
        CharSequence before = null;
        try {
            before = ic.getTextBeforeCursor(1, 0);
        } catch (RuntimeException ignored) {
            // no text to look at: just add the first mark
        }
        char last = before != null && before.length() == 1 ? before.charAt(0) : 0;
        if (last != TONE_SINGLE && last != TONE_DOUBLE) {
            commitSymbol(String.valueOf(TONE_SINGLE));
            inWord = true;
            return true;
        }
        ic.beginBatchEdit();
        boolean previous = internalEdit;
        internalEdit = true;
        try {
            ic.deleteSurroundingText(1, 0);
            if (last == TONE_SINGLE) {
                ic.commitText(String.valueOf(TONE_DOUBLE), 1);
            }
            rememberEdit("방점");
            lastEditAt = SystemClock.uptimeMillis();
        } finally {
            internalEdit = previous;
            ic.endBatchEdit();
        }
        inWord = true;
        return true;
    }

    /** A swiped-down archaic key or a Shift-layer tone mark, typed like a normal key. */
    void typeSwipeSymbol(char symbol) {
        type(symbol);
    }

    // ------------------------------------------------------------ composition

    private boolean type(char key) {
        if (isTone(key)) {
            return typeTone();
        }
        InputConnection ic = inputConnection();
        if (ic == null) {
            return false;
        }
        ic.beginBatchEdit();
        boolean previousInternalEdit = internalEdit;
        internalEdit = true;
        try {
            takeBackComposing(ic);
            // Update vowelIeung: lone vowels outside a word don't get auto-ㅇ.
            composer.setVowelIeung(vowelIeungPref && inWord);
            apply(ic, composer.type(key));
            rememberEdit("입력 " + key);
            typingStats.record(String.valueOf(key));
            appendRecent(String.valueOf(key));
            noteTyped(composing);
            refreshToolTip();
            updateTypingMeter();
            inWord = true;
            debug("type key=" + key + " composing=" + composing + " meter=" + typingStats.summary());
            // After typing, update vowelIeung for subsequent keys in this word.
            composer.setVowelIeung(vowelIeungPref && inWord);
        } finally {
            internalEdit = previousInternalEdit;
            ic.endBatchEdit();
        }
        return true;
    }

    /** Replaces the composing text after the composer changed without a new key. */
    private void rewrite(HangulComposer.Output out) {
        InputConnection ic = inputConnection();
        if (ic == null) {
            return;
        }
        ic.beginBatchEdit();
        boolean previousInternalEdit = internalEdit;
        internalEdit = true;
        try {
            takeBackComposing(ic);
            apply(ic, out);
        } finally {
            internalEdit = previousInternalEdit;
            ic.endBatchEdit();
        }
    }

    /**
     * Prepares the editor for the next {@link #apply}. Normally nothing is needed:
     * setComposingText/commitText replace the composing region. Only when the
     * editor reported that the region is gone (see {@link #beforeUpdateSelection})
     * is our text taken back by hand, so it is rewritten instead of duplicated.
     *
     * <p>The text before the cursor is never used to restart composition: apps
     * that apply input asynchronously (Chrome, WebView, Flutter) still return the
     * text from before our last edit, which reset composition after every key.
     */
    private void takeBackComposing(InputConnection ic) {
        if (directMode) {
            return;
        }
        if (composing.isEmpty()) {
            // Never overwrite a composing region someone else left behind.
            ic.finishComposingText();
            composingMaybeLost = false;
            return;
        }
        if (!composingMaybeLost) {
            return;
        }
        composingMaybeLost = false;
        CharSequence before = ic.getTextBeforeCursor(composing.length(), 0);
        if (before != null && composing.contentEquals(before)) {
            ic.finishComposingText();
            ic.deleteSurroundingText(composing.length(), 0);
            composing = "";
        } else if (SystemClock.uptimeMillis() - lastEditAt >= EDIT_ECHO_MS) {
            // The editor no longer holds our word (a chat app cleared it on Send,
            // the text was replaced...) and no edit of ours is still in flight:
            // forget it, or the next key would bring the sent text back.
            debug("composing gone from editor, dropping composing=" + composing);
            ic.finishComposingText();
            composer.reset();
            composing = "";
            inWord = false;
        }
    }

    /**
     * Backspace. Returns true when handled here. {@code repeat} is a held key,
     * which also deletes non-Hangul text here, one character at a time.
     */
    private boolean backspace(boolean repeat) {
        InputConnection ic = inputConnection();
        if (ic == null) {
            return false;
        }
        if (justSplit) {
            justSplit = false;
            HangulComposer.Output undo = composer.undoSplit();
            if (undo != null) {
                rewrite(undo);
                splitDeclined = true;
                return true;
            }
        }
        if (!composer.isEmpty()) {
            ic.beginBatchEdit();
            boolean previousInternalEdit = internalEdit;
            internalEdit = true;
            try {
                takeBackComposing(ic);
                HangulComposer.Output out = composer.backspace();
                if (out != null) {
                    apply(ic, out);
                    rememberEdit("삭제");
                    return true;
                }
            } finally {
                internalEdit = previousInternalEdit;
                ic.endBatchEdit();
            }
        }
        if (!TextUtils.isEmpty(ic.getSelectedText(0))) {
            return false;
        }
        CharSequence before = ic.getTextBeforeCursor(8, 0);
        HangulComposer.Recaptured r = koreanLayoutActive() && !directMode
                ? HangulComposer.recapture(before) : null;
        if (r == null) {
            if (!repeat || TextUtils.isEmpty(before)) {
                return false;
            }
            ic.deleteSurroundingTextInCodePoints(1, 0);
            rememberEdit("삭제");
            inWord = false;
            return true;
        }
        ic.beginBatchEdit();
        boolean previousInternalEdit = internalEdit;
        internalEdit = true;
        try {
            ic.finishComposingText();
            ic.deleteSurroundingText(r.length, 0);
            if (recapture && inWord && !repeat) {
                // Inside the word being typed: take the syllable apart jamo by jamo.
                apply(ic, composer.backspaceInto(r));
            } else {
                // Finished text: delete a whole syllable, as Samsung does.
                composing = "";
            }
        } finally {
            internalEdit = previousInternalEdit;
            ic.endBatchEdit();
        }
        return true;
    }

    private void apply(InputConnection ic, HangulComposer.Output out) {
        lastEditAt = SystemClock.uptimeMillis();
        if (directMode) {
            applyDirect(ic, out);
            return;
        }
        if (!out.commit.isEmpty()) {
            ic.commitText(out.commit, 1);
        }
        if (!out.composing.isEmpty()) {
            ic.setComposingText(out.composing, 1);
        } else if (out.commit.isEmpty() && !composing.isEmpty()) {
            // Nothing left to compose: remove the old composing text.
            ic.commitText("", 1);
        }
        composing = out.composing;
    }

    /**
     * Direct mode: the text shown so far ({@link #composing}) is already
     * committed, so only the changed tail is deleted and the new tail committed.
     */
    private void applyDirect(InputConnection ic, HangulComposer.Output out) {
        String shown = composing;
        String now = out.commit + out.composing;
        int same = 0;
        int max = Math.min(shown.length(), now.length());
        while (same < max && shown.charAt(same) == now.charAt(same)) {
            same++;
        }
        if (shown.length() > same) {
            ic.deleteSurroundingText(shown.length() - same, 0);
        }
        if (now.length() > same) {
            ic.commitText(now.substring(same), 1);
        }
        composing = out.composing;
    }

    /**
     * Types text that bypassed Samsung (a digit swiped down from the top row):
     * whatever is being composed, ours or Samsung's, is finished first.
     */
    void commitSymbol(String text) {
        InputConnection ic = inputConnection();
        if (ic == null) {
            return;
        }
        ic.beginBatchEdit();
        boolean previousInternalEdit = internalEdit;
        internalEdit = true;
        try {
            commitComposing();
            if (!directMode) {
                ic.finishComposingText();
            }
            ic.commitText(text, 1);
            rememberEdit("입력 " + text);
            typingStats.record(text);
            appendRecent(text);
            updateTypingMeter();
            lastEditAt = SystemClock.uptimeMillis();
        } finally {
            internalEdit = previousInternalEdit;
            ic.endBatchEdit();
        }
        inWord = false;
    }

    /** Finishes the current syllable, leaving its text in the editor. */
    void commitComposing() {
        clearWordLanguage();
        if (composer.isEmpty()) {
            return;
        }
        InputConnection ic = inputConnection();
        if (ic != null && !directMode) {
            boolean previousInternalEdit = internalEdit;
            internalEdit = true;
            try {
                ic.finishComposingText();
            } finally {
                internalEdit = previousInternalEdit;
            }
            lastEditAt = SystemClock.uptimeMillis();
        }
        composer.reset();
        composing = "";
        composingMaybeLost = false;
    }

    /** A new editor gained focus. */
    void onStartEditor(EditorInfo info) {
        removeClipChip();
        smartText = "";
        boolean terminal = info != null
                && ((info.inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_NULL
                || TERMINAL_PACKAGES.contains(info.packageName));
        directMode = directInput && terminal;
        recentText.setLength(0);
        externalComposing = "";
        typingStats.endComposing();
        toolResult = null;
    }

    /** Apps whose editors show no composing text. */
    private static final java.util.Set<String> TERMINAL_PACKAGES = new java.util.HashSet<>(
            java.util.Arrays.asList("com.termux", "jackpal.androidterm", "yarolegovich.materialterminal"));

    /**
     * The same editor restarted input (search boxes do this on every change).
     * The word being composed stays ours: resetting here let Samsung's stale,
     * empty composing state overwrite the editor's text.
     */
    void onRestartInput() {
        closeActionBatch();
        // A restart is also used by chat apps after Send (KakaoTalk etc.) and
        // by translation/search actions. The editor already owns its text at
        // this point, so do NOT touch it here: the old code called
        // setComposingText("", 1), which stole focus back from translation UI
        // and left a stale span that the next key resurrected. Just drop our
        // internal state; the next key starts fresh.
        // See HoneyBoardService.onStartInput(..., restarting=true).
        debug("onRestartInput composing=" + composing);
        clearWordLanguage();
        recentText.setLength(0);
        externalComposing = "";
        typingStats.endComposing();
        toolResult = null;
        composer.reset();
        composing = "";
        composingMaybeLost = false;
        backspaceOwned = false;
        stopRepeat();
        inWord = false;
        justSplit = false;
        splitDeclined = false;
        lastSelection = -1;
    }

    void resetState() {
        closeActionBatch();
        clearWordLanguage();
        if (!composer.isEmpty()) {
            debug("resetState dropping composing=" + composing);
        }
        composer.reset();
        composing = "";
        composingMaybeLost = false;
        lastSelection = -1;
        inWord = false;
        justSplit = false;
        splitDeclined = false;
        backspaceOwned = false;
        stopRepeat();
    }

    // ---------------------------------------------------------- editor events

    /**
     * Adjusts onUpdateSelection arguments before Samsung sees them. Our composing
     * region is hidden from Samsung so it does not try to manage it.
     */
    void beforeUpdateSelection(Object[] args) {
        refreshToolTip();
        if (composer.isEmpty() || directMode) {
            return;
        }
        int newSelStart = (Integer) args[2];
        int newSelEnd = (Integer) args[3];

        boolean echo = SystemClock.uptimeMillis() - lastEditAt < EDIT_ECHO_MS;
        CharSequence beforeEcho = null;
        // The editor reports its composing region itself (args 4 and 5): when that is exactly our
        // word before the cursor, no (blocking, for asynchronous editors slow) text read is needed.
        boolean regionMatches = !composing.isEmpty() && newSelStart == newSelEnd
                && (Integer) args[5] == newSelEnd
                && (Integer) args[5] - (Integer) args[4] == composing.length();
        if (!composing.isEmpty() && !regionMatches) {
            try {
                InputConnection ic = inputConnection();
                beforeEcho = ic == null ? null : ic.getTextBeforeCursor(composing.length(), 0);
            } catch (RuntimeException ignored) {
                beforeEcho = null;
            }
        }

        if (!regionMatches && (beforeEcho == null || !composing.contentEquals(beforeEcho))) {
            // The editor may still be applying our edit asynchronously and answer
            // with the text from before it, so a mismatch is not proof that the
            // word is gone. Dropping it here committed every syllable on its own
            // (see takeBackComposing). Keep the model, flag the region as lost
            // and let the next key re-sync: takeBackComposing only rewrites the
            // text when the editor really still holds it.
            debug("beforeUpdateSelection text mismatch, keeping composing=" + composing
                    + " echo=" + echo);
            composingMaybeLost = true;
            lastSelection = newSelEnd;
            if (!echo) {
                // Not an echo of our edit: the text was changed by the app (Send
                // in a chat app empties the field). Drop the word now so no
                // later key rewrites it.
                debug("beforeUpdateSelection: editor changed under us, dropping " + composing);
                // The cursor was moved (or the app changed the text) while the word was composing.
                // Samsung's own finish was blocked meanwhile, so the editor may still hold the word
                // as a composing span: the next setComposingText would replace that span at its old
                // position and the new text would vanish from where the cursor is now. Settle it.
                finishStaleComposing();
                composer.reset();
                composing = "";
                composingMaybeLost = false;
                inWord = false;
            }
            return;
        }

        int candStart = Math.max(0, newSelEnd - composing.length());
        int candEnd = newSelEnd;
        args[4] = candStart;
        args[5] = candEnd;

        if (!echo && newSelStart != newSelEnd) {
            commitComposing();
            inWord = false;
        }
        lastSelection = newSelEnd;
    }

    /** Ends a composing span of ours that the editor still holds somewhere else than at the cursor. */
    private void finishStaleComposing() {
        InputConnection ic = inputConnection();
        if (ic == null) return;
        boolean previous = internalEdit;
        internalEdit = true;
        try {
            ic.finishComposingText();
        } catch (RuntimeException e) {
            debug("finishStaleComposing failed: " + e);
        } finally {
            internalEdit = previous;
        }
        lastEditAt = SystemClock.uptimeMillis();
    }

    /**
     * Where edits go. Samsung's connection wrapper keeps several connections (index 0
     * is the app's editor; the others are fields inside the keyboard such as the
     * translation box) and sends every edit to the active one. While another one is
     * active our edits must follow it, or typing lands in the app and the keyboard's
     * own field loses focus.
     */
    private InputConnection inputConnection() {
        if (service == null) return null;
        InputConnection app = service.getCurrentInputConnection();
        InputConnection wrapper = samsungWrapper;
        if (wrapper == null) return app;
        int index = samsungConnectionIndex();
        if (index != lastConnectionIndex) {
            debug("input connection index " + lastConnectionIndex + " -> " + index);
            if (lastConnectionIndex != -1) {
                // The target of our edits changed: nothing composed here belongs to the new one.
                composer.reset();
                composing = "";
                composingMaybeLost = false;
                inWord = false;
                samsungWord = false;
            }
            lastConnectionIndex = index;
        }
        return index > 0 ? wrapper : app;
    }

    private InputConnection samsungWrapper;
    private java.lang.reflect.Field wrapperConnections;
    private java.lang.reflect.Field wrapperIndex;
    private int lastConnectionIndex = -1;

    /** Samsung's wrapper (its constructor ran), see ModuleMain.hookSamsungInputConnection. */
    void setSamsungWrapper(Object wrapper) {
        if (!(wrapper instanceof InputConnection)) return;
        samsungWrapper = (InputConnection) wrapper;
        try {
            for (Class<?> c = wrapper.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType() == InputConnection[].class) {
                        f.setAccessible(true);
                        wrapperConnections = f;
                    }
                }
            }
            // The index's name differs between the jadx dump (f18874j) and the
            // installed build ("j", the dump's "renamed from" comment).
            java.lang.reflect.Field named = null;
            for (String name : new String[]{"f18874j", "j"}) {
                try {
                    named = wrapper.getClass().getDeclaredField(name);
                    if (named.getType() == int.class) break;
                    named = null;
                } catch (NoSuchFieldException ignored) {
                    // try the next name
                }
            }
            wrapperIndex = named;
            if (wrapperIndex != null) wrapperIndex.setAccessible(true);
        } catch (RuntimeException e) {
            debug("wrapper fields: " + e);
        }
    }

    /** Index of the connection Samsung's wrapper uses now (0 = the app), or 0 when unknown. */
    private int samsungConnectionIndex() {
        try {
            if (wrapperConnections == null) return 0;
            InputConnection[] all = (InputConnection[]) wrapperConnections.get(samsungWrapper);
            if (all == null) return 0;
            if (wrapperIndex != null) {
                int i = wrapperIndex.getInt(samsungWrapper);
                return i >= 0 && i < all.length ? i : 0;
            }
            // Unknown field: the app's connection is the one the framework gave us.
            InputConnection app = service.getCurrentInputConnection();
            for (int i = 1; i < all.length; i++) {
                if (all[i] != null && all[i] != app && all[0] == app) {
                    return 0;
                }
            }
            return 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    private void rememberEdit(String label) {
        long now = SystemClock.uptimeMillis();
        if (!editHistory.isEmpty() && now - lastHistoryAt < 1200
                && ((label.startsWith("입력") && editHistory.peekFirst().startsWith("입력"))
                || (label.startsWith("삭제") && editHistory.peekFirst().startsWith("삭제")))) {
            editHistory.removeFirst();
            editHistory.addFirst(label.startsWith("입력") ? "입력 작업" : "삭제 작업");
            lastHistoryAt = now;
            return;
        }
        editHistory.addFirst(label);
        lastHistoryAt = now;
        while (editHistory.size() > 24) editHistory.removeLast();
    }

    private String lastMeterText = "";
    private boolean lastMeterUndoable;

    private void updateTypingMeter() {
        // Throttle: typing fires several keys per second; redrawing the overlay
        // on every keystroke is what made CPM/WPM flicker. At most ~2 redraws/s.
        long now = SystemClock.uptimeMillis();
        if (meterPending) return;
        if (now - lastMeterUpdateAt < 1000
                && typingOverlay != null && typingOverlay.getWindowToken() != null) {
            meterPending = true;
            handler.removeCallbacks(meterTick);
            handler.postDelayed(meterTick, 1000 - (now - lastMeterUpdateAt));
            return;
        }
        updateTypingMeterNow();
    }

    private void updateTypingMeterNow() {
        meterPending = false;
        lastMeterUpdateAt = SystemClock.uptimeMillis();
        if (service == null || service.getWindow() == null) return;
        if (!typingMeterEnabled()) {
            removeTypingMeter();
            return;
        }
        String summary = typingStats.summary();
        if (toolResult != null) {
            summary += "" + toolResult.display;
        }
        boolean undoable = !editHistory.isEmpty();
        boolean attached = typingOverlay != null && typingOverlay.getWindowToken() != null;
        if (attached && summary.equals(lastMeterText) && undoable == lastMeterUndoable) {
            return;  // nothing to redraw
        }
        lastMeterText = summary;
        lastMeterUndoable = undoable;
        android.view.View decor = service.getWindow().getWindow().getDecorView();
        float density = service.getResources().getDisplayMetrics().density;
        try {
            if (typingOverlay == null) {
                android.widget.LinearLayout bar = new android.widget.LinearLayout(service);
                bar.setGravity(android.view.Gravity.CENTER);
                bar.setPadding((int) (12 * density), 0, (int) (12 * density), 0);
                meterBackground = new android.graphics.drawable.GradientDrawable();
                meterBackground.setColor(0xCC202124);
                meterBackground.setCornerRadius(11 * density);
                bar.setBackground(meterBackground);
                if (typingMeterView == null) {
                    typingMeterView = new android.widget.TextView(service);
                    typingMeterView.setTextColor(0xFFE6E6E6);
                    typingMeterView.setTextSize(12.5f);
                    typingMeterView.setSingleLine(true);
                    typingMeterView.setIncludeFontPadding(false);
                    typingMeterView.setGravity(android.view.Gravity.CENTER);
                }
                android.widget.LinearLayout.LayoutParams meterParams =
                        new android.widget.LinearLayout.LayoutParams(-2, -1);
                meterParams.gravity = android.view.Gravity.CENTER;
                bar.addView(typingMeterView, meterParams);
                typingOverlay = bar;
            }
            typingMeterView.setText(typingStats.summary());
            typingMeterView.setGravity(android.view.Gravity.CENTER);
            if (typingOverlay.getWindowToken() == null) {
                android.view.WindowManager wm = (android.view.WindowManager)
                        service.getSystemService(android.content.Context.WINDOW_SERVICE);
                int flags = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                android.view.WindowManager.LayoutParams lp =
                        new android.view.WindowManager.LayoutParams(
                                -2, (int) (22 * density + 0.5f),
                                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG,
                                flags,
                                android.graphics.PixelFormat.TRANSLUCENT);
                lp.token = decor.getWindowToken();
                lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
                // Centered in Samsung's bottom bar (the strip under the keys), not stuck to the screen edge.
                int strip = (int) (48 * density + 0.5f);
                lp.y = -(strip + lp.height) / 2;
                lp.flags |= android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
                debug("typing meter y=" + lp.y + " overlay=" + lp.height);
                wm.addView(typingOverlay, lp);
                typingOverlayManager = wm;
                debug("typing meter overlay attached (content-sized, 15dp, centered)");
            }
        } catch (Throwable t) {
            XposedBridge.log("OldHangul: typing meter overlay unavailable: " + t);
        }
        updateCards();
        UndoBee.refresh();
    }

    private android.widget.HorizontalScrollView cardWindow;
    private android.widget.LinearLayout cardRow;
    private android.view.WindowManager cardManager;
    private boolean cardsShown;
    private float anchorX = Float.NaN;
    private float anchorTop = Float.NaN;

    /** The editor's caret, in screen coordinates (from the cursor anchor updates). */
    void onCursorAnchor(android.view.inputmethod.CursorAnchorInfo info) {
        try {
            float x = info.getInsertionMarkerHorizontal();
            float top = info.getInsertionMarkerTop();
            if (Float.isNaN(x) || Float.isNaN(top)) return;
            float[] pts = {x, top};
            info.getMatrix().mapPoints(pts);
            if (pts[0] == anchorX && pts[1] == anchorTop) return;
            anchorX = pts[0];
            anchorTop = pts[1];
            if (cardsShown) positionCards();
        } catch (RuntimeException e) {
            debug("cursor anchor failed: " + e);
        }
    }

    /** Top of Samsung's keyboard inside the IME window (its own content top), or -1. */
    private int keyboardTop() {
        try {
            android.inputmethodservice.InputMethodService.Insets in =
                    new android.inputmethodservice.InputMethodService.Insets();
            de.robv.android.xposed.XposedHelpers.callMethod(service, "onComputeInsets", in);
            if (in.contentTopInsets > 0) return in.contentTopInsets;
        } catch (Throwable ignored) {
            // fall through
        }
        return -1;
    }

    /**
     * Answer cards in their own small window, just above the caret line (above the keyboard when
     * the caret is unknown). The CPM/WPM meter keeps its own window and stays visible.
     */
    private void updateCards() {
        boolean cards = toolResult != null && !toolResult.cards.isEmpty() && service != null
                && service.getWindow() != null;
        if (!cards) {
            cardsShown = false;
            if (cardWindow != null && cardManager != null) {
                try {
                    cardManager.removeView(cardWindow);
                } catch (RuntimeException ignored) {
                    // Already removed.
                }
            }
            cardWindow = null;
            cardRow = null;
            cardManager = null;
            return;
        }
        float density = service.getResources().getDisplayMetrics().density;
        boolean appearing = !cardsShown || cardWindow == null;
        try {
            if (cardWindow == null) {
                cardRow = new android.widget.LinearLayout(service);
                cardRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                cardRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
                cardWindow = new android.widget.HorizontalScrollView(service);
                cardWindow.setHorizontalScrollBarEnabled(false);
                cardWindow.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
                cardWindow.addView(cardRow, new android.widget.FrameLayout.LayoutParams(-2, -1));
            }
            cardRow.removeAllViews();
            boolean first = true;
            for (String[] card : toolResult.cards) {
                android.widget.TextView t = new android.widget.TextView(service);
                t.setText(card[0]);
                t.setTextColor(0xFFFFFFFF);
                t.setTextSize(14f);
                t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                t.setSingleLine(true);
                t.setIncludeFontPadding(false);
                t.setGravity(android.view.Gravity.CENTER);
                t.setPadding((int) (12 * density), 0, (int) (12 * density), 0);
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(first ? 0xF03D7BF7 : 0xF03C4A66);
                bg.setCornerRadius(15 * density);
                t.setBackground(new android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(0x55FFFFFF), bg, null));
                t.setOnTouchListener((v, ev) -> {
                    int a = ev.getActionMasked();
                    if (a == android.view.MotionEvent.ACTION_DOWN) {
                        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).start();
                    } else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                    }
                    return false;
                });
                first = false;
                String insert = card[1];
                t.setOnClickListener(v -> insertToolResult(insert));
                android.widget.LinearLayout.LayoutParams lp =
                        new android.widget.LinearLayout.LayoutParams(-2, (int) (30 * density));
                lp.setMargins((int) (3 * density), 0, (int) (3 * density), 0);
                cardRow.addView(t, lp);
            }
            cardRow.measure(android.view.View.MeasureSpec.UNSPECIFIED,
                    android.view.View.MeasureSpec.makeMeasureSpec((int) (30 * density), android.view.View.MeasureSpec.EXACTLY));
            int screen = service.getResources().getDisplayMetrics().widthPixels;
            int width = Math.min(cardRow.getMeasuredWidth(), screen - (int) (8 * density));
            android.view.View decor = service.getWindow().getWindow().getDecorView();
            if (cardWindow.getWindowToken() == null) {
                cardManager = (android.view.WindowManager) service.getSystemService(android.content.Context.WINDOW_SERVICE);
                android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                        width, (int) (30 * density),
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        android.graphics.PixelFormat.TRANSLUCENT);
                lp.token = decor.getWindowToken();
                lp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
                placeCards(lp, width, density);
                cardWindow.setAlpha(0f);
                cardManager.addView(cardWindow, lp);
            } else {
                android.view.WindowManager.LayoutParams lp =
                        (android.view.WindowManager.LayoutParams) cardWindow.getLayoutParams();
                lp.width = width;
                placeCards(lp, width, density);
                cardManager.updateViewLayout(cardWindow, lp);
            }
            if (appearing) {
                cardsShown = true;
                cardWindow.scrollTo(0, 0);
                cardWindow.setTranslationY(8 * density);
                cardWindow.animate().alpha(1f).translationY(0f).setDuration(150)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                InputConnection ic = inputConnection();
                if (ic != null) {
                    try {
                        ic.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE
                                | InputConnection.CURSOR_UPDATE_MONITOR);
                    } catch (RuntimeException ignored) {
                        // the editor does not report the caret
                    }
                }
            }
        } catch (RuntimeException e) {
            debug("cards overlay failed: " + e);
        }
    }

    /** Window coordinates of the cards: above the caret line, else above the keyboard. */
    private void placeCards(android.view.WindowManager.LayoutParams lp, int width, float density) {
        int[] origin = new int[2];
        service.getWindow().getWindow().getDecorView().getLocationOnScreen(origin);
        int screen = service.getResources().getDisplayMetrics().widthPixels;
        int gap = (int) (6 * density);
        float cx;
        float topScreen;
        if (!Float.isNaN(anchorX) && !Float.isNaN(anchorTop)) {
            cx = anchorX;
            topScreen = anchorTop;
        } else {
            cx = screen / 2f;
            int kb = keyboardTop();
            topScreen = kb > 0 ? origin[1] + kb
                    : service.getResources().getDisplayMetrics().heightPixels - 330 * density;
        }
        int x = Math.round(cx - width / 2f);
        x = Math.max((int) (4 * density), Math.min(x, screen - width - (int) (4 * density)));
        int y = Math.round(topScreen - lp.height - gap);
        lp.x = x - origin[0];
        lp.y = Math.max(0, y) - origin[1];
    }

    private void positionCards() {
        if (cardWindow == null || cardManager == null || cardWindow.getWindowToken() == null) return;
        try {
            android.view.WindowManager.LayoutParams lp =
                    (android.view.WindowManager.LayoutParams) cardWindow.getLayoutParams();
            int x = lp.x;
            int y = lp.y;
            placeCards(lp, lp.width, service.getResources().getDisplayMetrics().density);
            if (x != lp.x || y != lp.y) cardManager.updateViewLayout(cardWindow, lp);
        } catch (RuntimeException e) {
            debug("cards reposition failed: " + e);
        }
    }

    /** Tapping the tool result types it in place of the expression (when that is still right before the cursor). */
    private void insertToolResult(String insert) {
        ToolSuggester.Result r = toolResult;
        InputConnection ic = inputConnection();
        if (r == null || ic == null) return;
        commitComposing();
        boolean previous = internalEdit;
        internalEdit = true;
        try {
            ic.beginBatchEdit();
            ic.finishComposingText();
            // Look a little further back than the expression: trailing blanks and the like shift it.
            CharSequence before = ic.getTextBeforeCursor(r.consumed + 8, 0);
            if (before != null) {
                String text = before.toString();
                int at = text.lastIndexOf(r.expression);
                if (at >= 0) {
                    int remove = text.length() - at;
                    ic.deleteSurroundingText(remove, 0);
                }
            }
            ic.commitText(insert, 1);
            rememberEdit("입력 " + insert);
            lastEditAt = SystemClock.uptimeMillis();
            debug("tool inserted " + insert + " for " + r.expression);
        } finally {
            try {
                ic.endBatchEdit();
            } catch (RuntimeException ignored) {
                // the connection is gone
            }
            internalEdit = previous;
        }
        recentText.setLength(0);
        externalComposing = "";
        toolResult = null;
        updateTypingMeter();
    }

    String typingSummary() {
        return typingStats.summary();
    }

    /** Shows recent edits inside the keyboard so shaking opens a selectable history. */
    void showUndoHistory() {
        if (service == null || service.getWindow() == null || undoPanel != null) return;
        android.view.View decor = service.getWindow().getWindow().getDecorView();
        android.widget.LinearLayout box = new android.widget.LinearLayout(service);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(24, 16, 24, 10);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xF51F1F1F);
        bg.setCornerRadius(18);
        box.setBackground(bg);
        android.widget.TextView title = new android.widget.TextView(service);
        title.setText("실행 기록 · " + typingStats.summary());
        title.setTextColor(android.graphics.Color.WHITE);
        title.setTextSize(17);
        title.setPadding(8, 4, 8, 14);
        box.addView(title);
        int count = 0;
        for (String item : editHistory) {
            android.widget.TextView row = new android.widget.TextView(service);
            row.setText(item + "    되돌리기");
            row.setTextColor(android.graphics.Color.WHITE);
            row.setTextSize(15);
            row.setPadding(8, 12, 8, 12);
            android.graphics.drawable.GradientDrawable rowBg =
                    new android.graphics.drawable.GradientDrawable();
            rowBg.setColor(0x24FFFFFF);
            rowBg.setCornerRadius(12);
            row.setBackground(rowBg);
            row.setOnClickListener(v -> { undoOnce(); dismissUndoHistory(); });
            android.widget.LinearLayout.LayoutParams rowParams =
                    new android.widget.LinearLayout.LayoutParams(-1, -2);
            rowParams.setMargins(0, 3, 0, 3);
            box.addView(row, rowParams);
            if (++count >= 8) break;
        }
        if (count == 0) {
            android.widget.TextView empty = new android.widget.TextView(service);
            empty.setText("최근 입력 기록이 없습니다");
            empty.setTextColor(0xBFFFFFFF);
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setPadding(8, 20, 8, 20);
            box.addView(empty);
        }
        android.widget.TextView close = new android.widget.TextView(service);
        close.setText("닫기");
        close.setTextColor(0xFF9CC2FF);
        close.setGravity(android.view.Gravity.CENTER);
        close.setPadding(8, 12, 8, 4);
        close.setOnClickListener(v -> dismissUndoHistory());
        box.addView(close);
        undoPanel = new android.widget.PopupWindow(box, -1, -2, true);
        undoPanel.setBackgroundDrawable(bg);
        undoPanel.setOutsideTouchable(true);
        undoPanel.setOnDismissListener(() -> undoPanel = null);
        undoPanel.showAtLocation(decor, android.view.Gravity.BOTTOM, 12, 12);
    }

    private void dismissUndoHistory() {
        if (undoPanel != null) undoPanel.dismiss();
    }

    void undoFromBee() {
        if (!undoButtonEnabled()) {
            debug("undo Bee tapped while disabled");
            return;
        }
        undoOnce();
    }

    private void removeTypingMeter() {
        handler.removeCallbacks(meterTick);
        meterPending = false;
        if (typingOverlay != null && typingOverlayManager != null) {
            try {
                typingOverlayManager.removeView(typingOverlay);
            } catch (RuntimeException ignored) {
                // Already removed.
            }
        }
        typingOverlay = null;
        typingOverlayManager = null;
        lastMeterText = "";
        toolResult = null;
        updateCards();
    }

    private void undoOnce() {
        InputConnection ic = inputConnection();
        if (ic == null) return;

        // Our composing text is owned by this controller: drop it directly so
        // the underline never survives an undo. This path also works in Termux
        // and WebView editors that have no Undo menu.
        if (!composer.isEmpty()) {
            debug("undo: clearing our composing=" + composing);
            ic.beginBatchEdit();
            boolean previousInternalEdit = internalEdit;
            internalEdit = true;
            try {
                if (directMode) {
                    int length = composing.codePointCount(0, composing.length());
                    if (length > 0) ic.deleteSurroundingTextInCodePoints(length, 0);
                } else {
                    ic.setComposingText("", 1);
                    ic.finishComposingText();
                }
                composer.reset();
                composing = "";
                composingMaybeLost = false;
                inWord = false;
            } finally {
                internalEdit = previousInternalEdit;
                ic.endBatchEdit();
            }
            if (!editHistory.isEmpty()) editHistory.removeFirst();
            updateTypingMeter();
            return;
        }

        // Otherwise behave exactly like Ctrl+Z in the editor: ask the editor
        // for its own Undo first (p040b8/b delegates performContextMenuAction
        // straight to the app), then send Ctrl+Z as a key event. Never delete
        // text by hand here: the old code removed one code point for a grouped
        // "입력 작업" entry, so the amount undone never matched the label.
        boolean undone = false;
        try {
            undone = ic.performContextMenuAction(android.R.id.undo);
        } catch (RuntimeException e) {
            debug("undo: performContextMenuAction failed: " + e);
            undone = false;
        }
        debug("undo: editor undo menu -> " + undone);
        if (!undone) {
            long now = SystemClock.uptimeMillis();
            boolean down = false;
            boolean up = false;
            try {
                down = ic.sendKeyEvent(new android.view.KeyEvent(now, now,
                        android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_Z, 0,
                        android.view.KeyEvent.META_CTRL_ON));
                up = ic.sendKeyEvent(new android.view.KeyEvent(now, now,
                        android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_Z, 0,
                        android.view.KeyEvent.META_CTRL_ON));
            } catch (RuntimeException e) {
                debug("undo: sendKeyEvent Ctrl+Z failed: " + e);
            }
            debug("undo: Ctrl+Z down=" + down + " up=" + up);
        }
        if (!editHistory.isEmpty()) editHistory.removeFirst();
        updateTypingMeter();
    }

    /** Handles Android Inline Suggestions API responses for candidate preview. */
    void onInlineSuggestionsResponse(Object response) {
        if (debugLog) {
            debug("onInlineSuggestionsResponse received");
        }
        updateTypingMeter();
    }

    boolean blockSamsungComposition() {
        return isComposing() && !internalEdit;
    }

    /**
     * Samsung is about to set composing text or commit {@code text} while a word of
     * ours is composing. Returns true to block it (Samsung still agrees on Korean).
     * Latin text means Samsung's multilingual engine decided the word is English:
     * our composition is dropped and Samsung's text is let through.
     */
    boolean blockSamsungText(CharSequence text) {
        if (samsungWordPending) {
            // The word is already in the editor: only the blank this key adds (space, newline) may pass.
            return !isBlank(text);
        }
        if (!blockSamsungComposition()) {
            return false;
        }
        if (multilingual && containsAsciiLetter(String.valueOf(text))) {
            debug("multilingual: Samsung decided English '" + text + "', dropping composing=" + composing);
            composer.reset();
            composing = "";
            composingMaybeLost = false;
            inWord = false;
            samsungWord = true;
            return false;
        }
        return true;
    }

    /** Samsung/the toolbar finished the editor's composing span externally. */
    void externalFinishComposing() {
        if (internalEdit) return;
        if (composer.isEmpty()) return;
        if (isComposing()) {
            // Our own call was blocked (see ModuleMain's finishComposingText
            // hook), so the word is still ours. Xposed runs the after hook even
            // for a blocked call, and clearing here dropped the word on every
            // keystroke, so Hangul never composed past one syllable.
            debug("finishComposingText blocked, keeping composing=" + composing);
            return;
        }
        clearWordLanguage();
        // The text may already be committed by the editor.  Keep the model in
        // sync and let the next Korean key start a fresh composition.
        composer.reset();
        composing = "";
        composingMaybeLost = false;
        inWord = false;
        lastSelection = -1;
    }
}
