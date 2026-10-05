package com.qoder.sogousym;

import android.app.AndroidAppHelper;
import android.content.Context;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Module entry point. Sogou ships a different obfuscation per release, so the module resolves
 * hooks at runtime and only installs the set that actually exists in the running build:
 *
 *  - v12.x ("old"): the engine classes are short names (on/hp/k1/tn), the key model is
 *    com.sogou.theme.data.key.b, the corner label is b.O2(String) and the long-press data is
 *    b.f0().
 *  - v20.x ("new"): the theme parser moved to com.sogou.theme.parse.parseimpl.*, the key model
 *    is com.sogou.theme.data.key.c, the corner label is c.R2(CharSequence) and the long-press
 *    data is BaseKeyData.m0().
 *
 * Anything that cannot be resolved is skipped instead of throwing, which matters because the
 * old code called findClass on every hotfix loader and turned each miss into an exception.
 */
public class MainHook implements IXposedHookLoadPackage {

    static final String TAG = "SogouSym: ";
    static final String PKG = "com.sohu.inputmethod.sogou";

    /** 0 = unresolved, 1 = v12 family, 2 = v20 family. */
    static volatile int VER = 0;

    /** Turn on to log every commit (noisy, and it costs a stack capture per commit). */
    static final boolean DEBUG_COMMIT = false;

    /** Loaded at hook-install time from the module app's shared prefs. */
    static volatile Map<String, String> MAP = Collections.emptyMap();

    /**
     * original symbol -> custom symbol, for the swipe-up substitution, one map per input mode.
     * Needed because keys like C/V/M commit the same original character in both modes while
     * carrying different customs, so a single map would let English overwrite Chinese.
     */
    static volatile Map<String, String> ORIG2CUSTOM_PY = Collections.emptyMap();
    static volatile Map<String, String> ORIG2CUSTOM_EN = Collections.emptyMap();

    /** original symbols of the comma/period keys (no long-press, direct output), per mode. */
    static volatile Set<String> PUNCT_PY = Collections.emptySet();
    static volatile Set<String> PUNCT_EN = Collections.emptySet();

    static Map<String, String> origMap() {
        return CN_MODE ? ORIG2CUSTOM_PY : ORIG2CUSTOM_EN;
    }

    static Set<String> punctSet() {
        return CN_MODE ? PUNCT_PY : PUNCT_EN;
    }

    /** Which gesture the custom symbol applies to: "both" | "longpress" | "swipe". */
    static volatile String MODE = "both";

    /**
     * True while the keyboard is in Chinese mode. Sogou gives S/H/J/L and a few layout-only
     * keys distinct section names per mode ("Key_S_PY" vs "Key_S_EN", "Key_Apostrophe_Shift"
     * vs "Key_Shift_Qwerty"), so observing any of them reveals the mode; keys Z/X/B/N then
     * inherit it even though they share one section name across both modes.
     */
    static volatile boolean CN_MODE = true;

    static boolean longPressEnabled() {
        return !"swipe".equals(MODE);
    }

    static boolean swipeEnabled() {
        return !"longpress".equals(MODE);
    }

    /**
     * Swipe-only mode parks each letter key's long-press value on its own private-use sentinel
     * (see {@link #buildSentinels()}) so the commit hook can tell a long-press from a swipe-up.
     *
     * The engine truncates a key's value to its FIRST character — appending a zero-width or
     * visible marker to the symbol does not survive — and it draws that same character into the
     * long-press popup, so the sentinel has to be a single code point with no glyph. Consequence:
     * the popup shows a "NO GLYPH" box on customised keys while this mode is active.
     */
    static volatile Map<String, String> SENT_BY_SECTION = Collections.emptyMap();
    static volatile Map<String, String> SENT2SECTION = Collections.emptyMap();

    /**
     * The custom symbol configured for a runtime section name, in the mode the keyboard is in.
     * Shared-section keys resolve to different config keys per mode; older configs that stored
     * the value under the bare section name still work through the fallback.
     */
    static String customOf(String section) {
        String v = MAP.get(Mapping.configKeyOf(section, CN_MODE));
        return v != null ? v : MAP.get(section);
    }

    /**
     * Update {@link #CN_MODE} from a section name seen while the layout is being parsed.
     *
     * Only the "_PY"/"_EN" suffixes count: those belong to S/H/J/L, which are the keys a layout
     * declares per mode. Other candidates such as "Key_Shift_Qwerty" also show up in the Chinese
     * layout (as the base section of Key_Apostrophe_Shift), so keying on them flips the mode back
     * and forth within a single parse.
     */
    static void noteMode(String sec) {
        if (sec == null) {
            return;
        }
        if (sec.endsWith("_PY")) {
            CN_MODE = true;
        } else if (sec.endsWith("_EN")) {
            CN_MODE = false;
        }
    }

    /**
     * Load the config through the module app's ContentProvider, off the main thread.
     *
     * Two reasons it must not run inline here: (1) the target Application (and thus a
     * Context) does not exist yet at process start, so we wait for it; (2) doing file or
     * class work inside the ClassLoader.loadClass hook re-enters class initialization and
     * deadlocks the target's main thread (Sogou IME service then ANRs).
     */
    static void refreshMapAsync() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Context ctx = null;
                    for (int i = 0; i < 200 && ctx == null; i++) {
                        ctx = AndroidAppHelper.currentApplication();
                        if (ctx == null) {
                            Thread.sleep(50);
                        }
                    }
                    Map<String, String> m = Prefs.loadForHook(ctx);
                    MODE = m.containsKey("#mode") ? m.get("#mode") : "both";
                    MAP = m;
                    buildOrigIndex();
                    buildSentinels();
                    XposedBridge.log(TAG + "config loaded: " + m + " mode=" + MODE);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "config load failed: " + t);
                }
            }
        }, "SogouSym-cfg");
        t.setDaemon(true);
        t.start();
    }

    static final Set<String> SEEN = new HashSet<String>();
    static final Set<Integer> INSTALLED = new HashSet<Integer>();

    /** loader identity -> how many times it has been probed for Sogou's classes. */
    static final Map<Integer, Integer> PROBED = new LinkedHashMap<Integer, Integer>();
    /** Probes allowed per loader before we conclude it does not host Sogou's classes. */
    static final int MAX_PROBES = 3;

    /** Log a message once per process (keeps hot paths from flooding the LSPosed log). */
    static void ilog(String s) {
        synchronized (SEEN) {
            if (!SEEN.add(s)) {
                return;
            }
        }
        XposedBridge.log(TAG + s);
    }

    static void buildOrigIndex() {
        Map<String, String> idxPy = new LinkedHashMap<String, String>();
        Map<String, String> idxEn = new LinkedHashMap<String, String>();
        Set<String> punctPy = new HashSet<String>();
        Set<String> punctEn = new HashSet<String>();
        for (Mapping.Key k : Mapping.KEYS) {
            boolean lp = Mapping.hasLongPress(k);
            String cp = MAP.get(k.pyKey());
            if (cp != null && !cp.isEmpty() && k.pyOrig != null && !k.pyOrig.isEmpty()) {
                idxPy.put(k.pyOrig, cp);
                if (!lp) {
                    punctPy.add(k.pyOrig);
                }
            }
            String ce = MAP.get(k.enKey());
            if (ce != null && !ce.isEmpty() && k.enOrig != null && !k.enOrig.isEmpty()) {
                idxEn.put(k.enOrig, ce);
                if (!lp) {
                    punctEn.add(k.enOrig);
                }
            }
        }
        ORIG2CUSTOM_PY = idxPy;
        ORIG2CUSTOM_EN = idxEn;
        PUNCT_PY = punctPy;
        PUNCT_EN = punctEn;
    }

    /** Assign each letter key a private-use sentinel for the swipe-only mode. */
    static void buildSentinels() {
        Map<String, String> bySec = new LinkedHashMap<String, String>();
        Map<String, String> s2sec = new LinkedHashMap<String, String>();
        int i = 0;
        for (Mapping.Key k : Mapping.KEYS) {
            if (!Mapping.hasLongPress(k)) {
                continue;
            }
            for (String sec : new String[]{k.pySection, k.enSection}) {
                if (bySec.containsKey(sec)) {
                    continue;
                }
                String sent = String.valueOf((char) (0xE000 + i++));
                bySec.put(sec, sent);
                s2sec.put(sent, sec);
            }
        }
        SENT_BY_SECTION = bySec;
        SENT2SECTION = s2sec;
    }

    /** Corner always shows "custom + original" (space separated); long-press input depends on mode. */
    static void setSymbol(Object key, String section, String sym) {
        String orig = Mapping.origOf(section, CN_MODE);
        String display = (orig == null || orig.isEmpty()) ? sym : (sym + " " + orig);
        if (VER == 2) {
            // v20: R2(CharSequence) is the minor-label setter (was O2(String) in v12).
            call(key, "R2", display);
        } else {
            call(key, "O2", display);
        }
        if (!Mapping.hasLongPress(section) || longPressEnabled()) {
            writeForeign(key, sym);
            return;
        }
        // Swipe-only: the corner still advertises the custom symbol, but a long-press has to behave
        // as if the key were untouched — so its submitted value is parked on this key's sentinel.
        String sent = SENT_BY_SECTION.get(section);
        if (sent != null) {
            writeForeign(key, sent);
        }
    }

    // The long-press holder is b.f0() in v12 and BaseKeyData.m0() in v20; both return the same
    // ForeignKeyInfo class whose fields carry the popup label and submitted code.
    //
    // v12 needed all five fields written together. In v20 only `g` (the label the popup draws and
    // the long-press submits) is used; `h`/`j`/`l` there are backing arrays we do not control, and
    // overwriting them with a foreign value made the engine do extra work on every long-press of a
    // customised key, which showed up as lag.
    private static void writeForeign(Object key, String value) {
        try {
            Object fk = call(key, VER == 2 ? "m0" : "f0");
            if (fk == null) {
                return;
            }
            XposedHelpers.setObjectField(fk, "g", value);
            if (VER == 2) {
                return;
            }
            XposedHelpers.setObjectField(fk, "h", new int[]{value.charAt(0)});
            XposedHelpers.setObjectField(fk, "i", value);
            XposedHelpers.setObjectField(fk, "j", new int[]{value.charAt(0)});
            XposedHelpers.setIntField(fk, "l", value.charAt(0));
        } catch (Throwable ignored) {
        }
    }

    /** callMethod but silent: a missing method must not break the parse. */
    private static Object call(Object o, String m, Object... args) {
        try {
            return XposedHelpers.callMethod(o, m, args);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (!PKG.equals(lp.packageName)) {
            return;
        }
        XposedBridge.log(TAG + "attached " + lp.packageName + " proc=" + lp.processName);
        refreshMapAsync();
        installAll(lp.classLoader);
        // The global ClassLoader.loadClass hook only exists to discover v12's hotfix
        // DelegateLastClassLoader. On v20 the real classes are on the app loader, so installing it
        // would add an Xposed trampoline on every class load for nothing — and that shows up as
        // typing jank. Skip it whenever the app loader already resolved v20.
        if (VER != 2) {
            hookLoaderDiscovery();
        }
    }

    /** Discover the real (hotfix) ClassLoader and hook its classes. */
    private void hookLoaderDiscovery() {
        try {
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (VER == 2) {
                            return;
                        }
                        Object ldr = p.thisObject;
                        if (!(ldr instanceof ClassLoader)) {
                            return;
                        }
                        String lc = ldr.getClass().getName();
                        if (lc.contains("DelegateLastClassLoader") || lc.contains("PathClassLoader")
                                || lc.contains("DexClassLoader") || lc.contains("InMemoryDexClassLoader")) {
                            installAll((ClassLoader) ldr);
                        }
                        String n = (String) p.args[0];
                        // Lazily hook the commit class once it is actually loaded.
                        if ("com.sogou.imskit.core.input.inputconnection.CachedInputConnection".equals(n)) {
                            Object c = p.getResult();
                            if (c instanceof Class) {
                                hookCommitOn((Class<?>) c);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + "hooked loader discovery");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook loader discovery failed: " + t);
        }
    }

    private void installAll(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        int id = System.identityHashCode(cl);
        synchronized (INSTALLED) {
            if (INSTALLED.contains(id)) {
                return;
            }
        }
        // installAll runs from the ClassLoader.loadClass after-hook, i.e. on the class-load hot
        // path, for every loader type it recognises. Probing costs class loads of its own, so cap
        // the number of probes per loader: a loader that never hosts Sogou's classes (the dynamic
        // feature / kuikly dex loaders) must not be re-probed on every single loadClass.
        synchronized (PROBED) {
            Integer n = PROBED.get(id);
            int c = n == null ? 0 : n.intValue();
            if (c >= MAX_PROBES) {
                return;
            }
            PROBED.put(id, c + 1);
        }
        // Which obfuscation family this loader hosts. Probes are non-throwing.
        Class<?> newParser = XposedHelpers.findClassIfExists(
                "com.sogou.theme.parse.parseimpl.a", cl);
        Class<?> oldParser = newParser == null
                ? XposedHelpers.findClassIfExists("hp", cl) : null;
        int ver = newParser != null ? 2 : (oldParser != null ? 1 : 0);
        if (ver == 0) {
            return;
        }
        synchronized (INSTALLED) {
            if (!INSTALLED.add(id)) {
                return;
            }
        }
        VER = ver;
        XposedBridge.log(TAG + "installAll ver=" + ver + " on " + cl.getClass().getName()
                + "@" + Integer.toHexString(id));
        if (ver == 2) {
            hookAttrNew(cl);
            hookPopupNew(cl);
        } else {
            hookHpJ(cl);
            hookOnE(cl);
            hookK1B(cl);
            hookTnP(cl);
        }
        hookCommit(cl);
    }

    /** Hook the real commit class once it is loaded. */
    private void hookCommitOn(final Class<?> c) {
        try {
            XposedBridge.hookAllMethods(c, "commitText", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    substituteCommit(p);
                }
            });
            ilog("hooked commitText (lazy) on " + c.getName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + "lazy hook commitText failed: " + t);
        }
    }

    /**
     * Log every text commit with its caller, and substitute the swipe-up symbol.
     *
     * Found via probe: a swipe-up commits through BaseInputLogic#z0 -> BaseInputLogic#C ->
     * commitText, while normal pinyin goes through BaseInputLogic#z. Long-press shares z0, so the
     * substitution keys off the committed text instead of the entry method.
     */
    private void hookCommit(ClassLoader cl) {
        try {
            Class<?> cc = XposedHelpers.findClassIfExists(
                    "com.sogou.imskit.core.input.inputconnection.CachedInputConnection", cl);
            if (cc == null) {
                return;
            }
            XC_MethodHook cb = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    substituteCommit(p);
                }
            };
            int n = 0;
            for (Class<?> c = cc; c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if ("commitText".equals(m.getName())) {
                        XposedBridge.hookMethod(m, cb);
                        n++;
                    }
                }
            }
            ilog("hooked commitText, overloads=" + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook commitText failed: " + t);
        }
    }

    /**
     * Text substitution for commits.
     *
     * Fast path: most commits (normal typing) carry text that is in neither map, so they are
     * dropped before any stack capture. Only a commit whose text looks like a configured original
     * symbol, a sentinel, or a punctuation key reaches the stack walk.
     */
    private static void substituteCommit(XC_MethodHook.MethodHookParam p) {
        try {
            if (p.args.length == 0 || !(p.args[0] instanceof CharSequence)) {
                return;
            }
            String t = String.valueOf(p.args[0]);
            if (t.isEmpty()) {
                return;
            }
            String sentSec = SENT2SECTION.get(t);
            boolean interesting = sentSec != null
                    || ORIG2CUSTOM_PY.containsKey(t) || ORIG2CUSTOM_EN.containsKey(t)
                    || PUNCT_PY.contains(t) || PUNCT_EN.contains(t);
            if (!interesting && !DEBUG_COMMIT) {
                return;
            }
            StackTraceElement[] st = new Throwable().getStackTrace();
            Boolean commitMode = modeFromStack(st);
            if (commitMode != null) {
                CN_MODE = commitMode.booleanValue();
            }
            boolean slideUp = isSlideUp(st);
            if (DEBUG_COMMIT) {
                XposedBridge.log(TAG + "COMMIT[" + t + "] interesting=" + interesting
                        + " slideUp=" + slideUp + " cn=" + CN_MODE + " from " + callerOf(st));
            }
            if (!interesting) {
                return;
            }
            if (sentSec != null) {
                // A long-press in swipe-only mode: behave as if unmodified.
                String back = Mapping.origOf(sentSec, CN_MODE);
                if (back != null && !back.isEmpty()) {
                    p.args[0] = back;
                    XposedBridge.log(TAG + "sentinel -> " + back);
                }
            }
            String rep = origMap().get(t);
            if (rep != null && !rep.equals(t)) {
                // Comma/period have no long-press, so they always apply; letters only on the
                // gesture path, and only if that gesture is enabled. Either way the Sogou symbol
                // panel must be left alone — otherwise typing the original symbol from it would
                // come out as the custom one.
                boolean punct = punctSet().contains(t);
                if (!isSymbolPanelPath(st) && (punct || (slideUp && swipeEnabled()))) {
                    p.args[0] = rep;
                    XposedBridge.log(TAG + "replace " + t + " -> " + rep);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * True when the current commit came from the Sogou symbol panel (symbols list) rather than
     * from the keyboard keys. Both end up in {@code BaseInputLogic#C}, but the panel's stack runs
     * through {@code PinyinInputLogic#p0} / the symbol-page message handler, while a key or a
     * swipe runs through {@code PinyinInputLogic#x0} (gesture) or {@code b54#L0} (punctuation key).
     */
    private static boolean isSymbolPanelPath(StackTraceElement[] st) {
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            String mn = e.getMethodName();
            if (cn.endsWith("PinyinInputLogic") && "p0".equals(mn)) {
                return true;
            }
            if (cn.endsWith("inputsession.h3") && "handleMessage".equals(mn)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Which input mode the current call belongs to, read off the stack: Chinese commits run
     * through {@code BaseInputLogic}, English ones through the Typany engine
     * ({@code com.typany.shell}). Returns null when the stack says nothing.
     */
    private static Boolean modeFromStack(StackTraceElement[] st) {
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            if (cn.contains("typany.shell")) {
                return Boolean.FALSE;
            }
            if (cn.endsWith("BaseInputLogic")) {
                return Boolean.TRUE;
            }
        }
        return null;
    }

    /**
     * True when the current commit came from the gesture path rather than plain typing.
     *
     * Chinese routes long-press and swipe-up through `BaseInputLogic#z0` (v12) / `#D0` (v20);
     * English (the Typany engine) routes both through `com.typany.shell.Interface#handleSecondaryInput`. Neither
     * separates long-press from swipe, which is fine: in "both" mode a long-press commits the
     * custom symbol already, so only the swipe-up can commit a key's original symbol.
     */
    private static boolean isSlideUp(StackTraceElement[] st) {
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            String mn = e.getMethodName();
            // v12 routes long-press and swipe through BaseInputLogic#z0; v20 renamed that same gate
            // to BaseInputLogic#D0 (observed stack: commitText <- BaseInputLogic#D <- #D0).
            if (cn.endsWith("BaseInputLogic")
                    && (VER == 2 ? "D0".equals(mn) : "z0".equals(mn))) {
                return true;
            }
            if (cn.endsWith("typany.shell.Interface") && "handleSecondaryInput".equals(mn)) {
                return true;
            }
        }
        return false;
    }

    /** Skip Xposed dispatch/reflection frames and return the first few real callers. */
    private static String callerOf(StackTraceElement[] st) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            String mn = e.getMethodName();
            if (cn.startsWith("de.robv") || cn.contains("MainHook")
                    || cn.startsWith("java.lang.ClassLoader") || cn.startsWith("java.lang.reflect")
                    || "intercept".equals(mn) || "proceed".equals(mn) || "proceedWith".equals(mn)
                    || "callback".equals(mn) || "callBeforeHookedMethod".equals(mn)
                    || "callAfterHookedMethod".equals(mn) || "invokeOriginalMethod".equals(mn)
                    || "loadClass".equals(mn)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            sb.append(cn).append('#').append(mn);
            if (++n >= 3) {
                break;
            }
        }
        return sb.length() == 0 ? "(unknown)" : sb.toString();
    }

    // ------------------------------------------------------------------ v12 family

    /** hp.j(int, ArrayList, String, String, tn) - per-section resolver. */
    private void hookHpJ(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("hp", cl, "j",
                    int.class, java.util.ArrayList.class, String.class, String.class, "tn",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Object sec = p.args[3];
                                Object res = p.getResult();
                                if (res != null && sec instanceof String) {
                                    noteMode((String) sec);
                                    String sym = customOf((String) sec);
                                    if (sym != null) {
                                        setSymbol(res, (String) sec, sym);
                                    }
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "hp.j err " + t);
                            }
                        }
                    });
            ilog("hooked hp.j");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook hp.j failed: " + t);
        }
    }

    /** on.e(String, ArrayMap, tn) - base section parser. */
    private void hookOnE(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("on", cl, "e",
                    String.class, "androidx.collection.ArrayMap", "tn",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                Object sec = p.args[0];
                                if (sec instanceof String) {
                                    String s = (String) sec;
                                    // MINOR_LABEL drives the corner AND the long-press output, so in
                                    // swipe-only mode leave letter keys completely alone.
                                    if (longPressEnabled() || !Mapping.hasLongPress(s)) {
                                        String sym = customOf(s);
                                        Object a = p.args[1];
                                        if (sym != null && a instanceof Map) {
                                            ((Map) a).put("MINOR_LABEL", sym);
                                        }
                                    }
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "on.e err " + t);
                            }
                        }
                    });
            ilog("hooked on.e");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook on.e failed: " + t);
        }
    }

    /** k1.B(b, keyName, attr, value, tn) - attribute setter. */
    private void hookK1B(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("k1", cl, "B",
                    "com.sogou.theme.data.key.b", String.class, String.class, String.class, "tn",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                String key = (String) p.args[1];
                                String attr = (String) p.args[2];
                                if (!"MINOR_LABEL".equalsIgnoreCase(attr)) {
                                    return;
                                }
                                noteMode(key);
                                String sym = customOf(key);
                                if (sym != null) {
                                    setSymbol(p.args[0], key, sym);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "k1.B err " + t);
                            }
                        }
                    });
            ilog("hooked k1.B");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook k1.B failed: " + t);
        }
    }

    private void hookTnP(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("tn", cl, "P", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    Object s = p.args[0];
                    if (s instanceof String && ((String) s).startsWith("Key")) {
                        // tn.P re-registers every key name each time a layout is loaded, and a
                        // layout only declares its own mode's keys, so this is where the
                        // Chinese/English mode becomes known.
                        noteMode((String) s);
                    }
                }
            });
            ilog("hooked tn.P");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook tn.P failed: " + t);
        }
    }

    // ------------------------------------------------------------------ v20 family

    /**
     * com.sogou.theme.parse.parseimpl.a#B(key, keyName, attr, value, view) - the attribute setter.
     * The MINOR_LABEL branch writes the key's corner label via c.R2(CharSequence).
     */
    private void hookAttrNew(ClassLoader cl) {
        try {
            Class<?> keyCls = XposedHelpers.findClassIfExists("com.sogou.theme.data.key.c", cl);
            Class<?> ctxCls = XposedHelpers.findClassIfExists("com.sogou.theme.data.view.a", cl);
            if (keyCls == null || ctxCls == null) {
                XposedBridge.log(TAG + "hookAttrNew: key/ctx class missing");
                return;
            }
            XposedHelpers.findAndHookMethod("com.sogou.theme.parse.parseimpl.a", cl, "B",
                    keyCls, String.class, String.class, String.class, ctxCls,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                String key = (String) p.args[1];
                                String attr = (String) p.args[2];
                                if (key == null || !"MINOR_LABEL".equalsIgnoreCase(attr)) {
                                    return;
                                }
                                noteMode(key);
                                String sym = customOf(key);
                                if (sym != null) {
                                    setSymbol(p.args[0], key, sym);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "new attr err " + t);
                            }
                        }
                    });
            ilog("hooked parseimpl.a.B (v20)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook parseimpl.a.B failed: " + t);
        }
    }

    /**
     * com.sogou.theme.parse.parseimpl.q#B - attribute setter for the long-press popup data
     * (POPUP_LONGPRESS_LABELS / _UNICODES ...). When a key has a custom symbol and the theme
     * supplies its own popup label afterwards, our value would be overwritten, so re-assert it.
     */
    private void hookPopupNew(ClassLoader cl) {
        try {
            Class<?> keyCls = XposedHelpers.findClassIfExists("com.sogou.theme.data.key.c", cl);
            Class<?> ctxCls = XposedHelpers.findClassIfExists("com.sogou.theme.data.view.a", cl);
            if (keyCls == null || ctxCls == null) {
                return;
            }
            XposedHelpers.findAndHookMethod("com.sogou.theme.parse.parseimpl.q", cl, "B",
                    keyCls, String.class, String.class, String.class, ctxCls,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                String key = (String) p.args[1];
                                String attr = (String) p.args[2];
                                if (key == null || !"POPUP_LONGPRESS_LABELS".equalsIgnoreCase(attr)) {
                                    return;
                                }
                                noteMode(key);
                                if (!Mapping.hasLongPress(key) || !longPressEnabled()) {
                                    return;
                                }
                                String sym = customOf(key);
                                if (sym != null) {
                                    writeForeign(p.args[0], sym);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "new popup err " + t);
                            }
                        }
                    });
            ilog("hooked parseimpl.q.B (v20)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook parseimpl.q.B failed: " + t);
        }
    }
}
