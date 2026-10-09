package com.qoder.sogousym;

import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Spanned;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class PasteUndoHook {
    private static final PasteUndo HISTORY = new PasteUndo();
    private static final Set<Class<?>> INSTALLED = new HashSet<>();
    private static volatile WeakReference<InputMethodService> service = new WeakReference<>(null);
    private static boolean frameworkInstalled;
    private static volatile long editorSession;
    private static final ThreadLocal<Boolean> restoring = new ThreadLocal<>();

    static boolean isRestoring() {
        return Boolean.TRUE.equals(restoring.get());
    }

    static boolean enabled() {
        return "1".equals(MainHook.MAP.get("#undoPaste")) && MainHook.VER == 2;
    }

    private static String undoLetter() {
        String letter = MainHook.MAP.get("#undoKey");
        return letter == null || letter.isEmpty() ? "X" : letter;
    }

    static Mapping.Key undoKey() {
        String letter = undoLetter();
        for (Mapping.Key k : Mapping.KEYS) {
            if (k.row != 4 && k.letter.equals(letter)) {
                return k;
            }
        }
        return null;
    }

    static boolean isUndoKey(Mapping.Key k) {
        return enabled() && k.row != 4 && k.letter.equals(undoLetter());
    }

    static boolean isUndoSection(String section) {
        Mapping.Key k = undoKey();
        return enabled() && k != null && (section.equals(k.pySection) || section.equals(k.enSection));
    }

    static boolean isUndoOriginal(String text) {
        Mapping.Key k = undoKey();
        return k != null && (text.equals(k.pyOrig) || text.equals(k.enOrig));
    }

    static synchronized void installFramework() {
        if (frameworkInstalled) {
            return;
        }
        frameworkInstalled = true;
        XposedBridge.hookAllMethods(InputMethodService.class, "doStartInput", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                HISTORY.clear();
                editorSession++;
                service = new WeakReference<>((InputMethodService) p.thisObject);
            }
        });
        XposedBridge.hookAllMethods(InputMethodService.class, "doFinishInput", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                HISTORY.clear();
                editorSession++;
            }
        });
        XposedHelpers.findAndHookMethod("android.inputmethodservice.InputMethodService$InputMethodSessionImpl",
                null, "updateSelection", int.class, int.class, int.class, int.class, int.class,
                int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (enabled() && !Boolean.TRUE.equals(restoring.get())) {
                            HISTORY.selection((Integer) p.args[2], (Integer) p.args[3],
                                    (Integer) p.args[4] >= 0 || (Integer) p.args[5] >= 0);
                        }
                    }
                });
    }

    static synchronized void install(ClassLoader loader) {
        Class<?> dispatcher = XposedHelpers.findClassIfExists(
                "com.sogou.imskit.core.input.inputconnection.z", loader);
        if (dispatcher == null || !INSTALLED.add(dispatcher)) {
            return;
        }
        XC_MethodHook primaryInput = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (enabled()) {
                    invalidate();
                }
            }
        };
        // Pinyin can stay entirely in the engine buffer, without editing the target app.
        Class<?> pinyin = XposedHelpers.findClassIfExists(
                "com.sogou.core.input.chinese.inputsession.logic.PinyinInputLogic", loader);
        if (pinyin != null) {
            XposedHelpers.findAndHookMethod(pinyin, "z0", int.class, int.class, int.class,
                    int.class, boolean.class, primaryInput);
        }
        Class<?> english = XposedHelpers.findClassIfExists("com.typany.shell.Interface", loader);
        if (english != null) {
            XposedBridge.hookAllMethods(english, "handlePrimaryInput", primaryInput);
        }
        // z.h posts the clipboard text to the input thread; its caller still identifies the UI.
        XposedHelpers.findAndHookMethod(dispatcher, "h", String.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (enabled() && clipboardSource(new Throwable().getStackTrace())) {
                            arm((String) p.args[0]);
                        }
                    }
                });
        Class<?> candidate = XposedHelpers.findClassIfExists(
                "com.sohu.inputmethod.sogou.clipboard.ClipboardCandidateCopyPhraseProxy", loader);
        if (candidate != null) {
            XposedHelpers.findAndHookMethod(candidate, "v", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled()) {
                        return;
                    }
                    try {
                        Object items = XposedHelpers.getObjectField(p.thisObject, "h");
                        int index = candidateIndex((Integer) p.args[0],
                                XposedHelpers.getIntField(p.thisObject, "p"),
                                XposedHelpers.getIntField(p.thisObject, "x"),
                                items instanceof List ? ((List<?>) items).size() : 0);
                        if (items instanceof List && index >= 0 && index < ((List<?>) items).size()) {
                            Object text = ((List<?>) items).get(index);
                            if (text instanceof CharSequence) {
                                arm(text.toString());
                            }
                        }
                    } catch (Throwable ignored) {
                        HISTORY.clear();
                    }
                }
            });
        }
        MainHook.ilog("hooked clipboard paste undo (v20)");
    }

    static boolean clipboardSource(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            String type = frame.getClassName();
            String method = frame.getMethodName();
            if (("com.sogou.clipboard.spage.ClipboardPage".equals(type)
                    && "onItemClick".equals(method))
                    || ("com.sogou.clipboard.repository.utils.q".equals(type)
                    && "a".equals(method))) {
                return true;
            }
        }
        return false;
    }

    static int candidateIndex(int slot, int layout, int kind, int count) {
        // v20's compact clipboard strip reserves slot zero for opening the clipboard panel.
        if (slot < 0 || (layout < 3 && (slot == 0 || kind == 5))) {
            return -1;
        }
        int index = layout < 3 ? slot - 1 : slot;
        return index < count ? index : -1;
    }

    private static InputConnection editor() {
        InputMethodService ime = service.get();
        return ime != null && ime.isInputViewShown() ? ime.getCurrentInputConnection() : null;
    }

    private static void arm(String pasted) {
        try {
            InputConnection connection = editor();
            PasteUndo.Snapshot before = snapshot(connection);
            PasteUndo.Record record = HISTORY.arm(connection, before, pasted);
            if (record != null) {
                // Input dispatch is asynchronous. Bounded checks also cover a paste that bypasses
                // CachedInputConnection; every check uses the target editor rather than its cache.
                Handler handler = new Handler(Looper.getMainLooper());
                for (int delay : new int[]{100, 300, 700}) {
                    handler.postDelayed(() -> verify(record), delay);
                }
                handler.postDelayed(() -> {
                    if (HISTORY.current() == record && !record.confirmed) {
                        HISTORY.clear();
                    }
                }, 1200);
            }
        } catch (Throwable ignored) {
            HISTORY.clear();
        }
    }

    private static void verify(PasteUndo.Record record) {
        if (HISTORY.current() != record || record.confirmed) {
            return;
        }
        try {
            InputConnection connection = editor();
            PasteUndo.Snapshot actual = snapshot(connection);
            HISTORY.confirm(record, connection, actual);
        } catch (Throwable ignored) {
            HISTORY.clear();
        }
    }

    static void beforeCommit(CharSequence text) {
        if (enabled() && !Boolean.TRUE.equals(restoring.get())) {
            HISTORY.acceptsCommit(text == null ? null : text.toString());
        }
    }

    static void afterCommit(boolean success) {
        if (!enabled() || Boolean.TRUE.equals(restoring.get())) {
            return;
        }
        if (!success) {
            HISTORY.clear();
        } else {
            PasteUndo.Record record = HISTORY.current();
            if (record != null) {
                verify(record);
            }
        }
    }

    static void invalidate() {
        if (!Boolean.TRUE.equals(restoring.get())) {
            HISTORY.clear();
        }
    }

    static void installEdits(Class<?> connection) {
        XC_MethodHook edited = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (enabled()) {
                    invalidate();
                }
            }
        };
        for (String method : new String[]{"deleteSurroundingText", "deleteSurroundingTextInCodePoints",
                "setComposingText", "setComposingRegion", "setSelection", "sendKeyEvent",
                "commitCompletion", "commitCorrection", "performContextMenuAction",
                "performEditorAction", "closeConnection"}) {
            XposedBridge.hookAllMethods(connection, method, edited);
        }
    }

    static void undo(Object cachedConnection) {
        try {
            long session = editorSession;
            InputConnection connection = editor();
            PasteUndo.Record pending = HISTORY.current();
            if (pending == null) {
                return;
            }
            if (!pending.confirmed) {
                verify(pending);
            }
            PasteUndo.Record record = HISTORY.take(connection, snapshot(connection));
            if (record == null || editorSession != session || editor() != connection
                    || XposedHelpers.callMethod(cachedConnection, "z") != connection) {
                return;
            }
            restoring.set(true);
            XposedHelpers.callMethod(cachedConnection, "beginBatchEdit");
            boolean committed = false;
            try {
                // Use Sogou's public editing methods to keep its cursor/text cache in sync.
                if (!Boolean.TRUE.equals(XposedHelpers.callMethod(cachedConnection, "setSelection",
                        record.position, record.position + record.pasted.length()))) {
                    return;
                }
                CharSequence selected = connection.getSelectedText(0);
                if (selected == null || !record.pasted.contentEquals(selected)
                        || editorSession != session || editor() != connection) {
                    return;
                }
                if (Boolean.TRUE.equals(XposedHelpers.callMethod(cachedConnection, "commitText",
                        record.replaced, 1))) {
                    committed = true;
                    XposedHelpers.callMethod(cachedConnection, "setSelection",
                            record.before.start, record.before.end);
                }
            } finally {
                if (!committed && editorSession == session && editor() == connection) {
                    XposedHelpers.callMethod(cachedConnection, "setSelection",
                            record.after.start, record.after.end);
                }
                XposedHelpers.callMethod(cachedConnection, "endBatchEdit");
            }
        } catch (Throwable ignored) {
            HISTORY.clear();
        } finally {
            restoring.remove();
        }
    }

    private static PasteUndo.Snapshot snapshot(InputConnection connection) {
        InputMethodService ime = service.get();
        EditorInfo info = ime == null ? null : ime.getCurrentInputEditorInfo();
        if (connection == null || info == null || password(info.inputType)) {
            return null;
        }
        ExtractedTextRequest request = new ExtractedTextRequest();
        request.hintMaxChars = PasteUndo.MAX_TEXT + 1;
        request.hintMaxLines = 10000;
        request.flags = InputConnection.GET_TEXT_WITH_STYLES;
        ExtractedText extracted = connection.getExtractedText(request, 0);
        if (extracted == null || extracted.text == null || extracted.startOffset != 0
                || extracted.text.length() > PasteUndo.MAX_TEXT
                || extracted.partialStartOffset >= 0 || extracted.partialEndOffset >= 0) {
            return null;
        }
        if (extracted.text instanceof Spanned) {
            Spanned spans = (Spanned) extracted.text;
            for (Object span : spans.getSpans(0, spans.length(), Object.class)) {
                if ((spans.getSpanFlags(span) & Spanned.SPAN_COMPOSING) != 0) {
                    return null;
                }
            }
        }
        PasteUndo.Snapshot result = new PasteUndo.Snapshot(extracted.text.toString(),
                extracted.selectionStart, extracted.selectionEnd);
        if (!result.valid()) {
            return null;
        }
        int left = Math.min(result.start, result.end);
        int right = Math.max(result.start, result.end);
        CharSequence before = connection.getTextBeforeCursor(left + 1, 0);
        CharSequence after = connection.getTextAfterCursor(result.text.length() - right + 1, 0);
        return before != null && after != null
                && result.text.substring(0, left).contentEquals(before)
                && result.text.substring(right).contentEquals(after) ? result : null;
    }

    static boolean password(int inputType) {
        int type = inputType & InputType.TYPE_MASK_CLASS;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        return (type == InputType.TYPE_CLASS_TEXT
                && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
                || (type == InputType.TYPE_CLASS_NUMBER
                && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    }

    private PasteUndoHook() {
    }
}
