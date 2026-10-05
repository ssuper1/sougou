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
 * Module v6.
 *
 * ROOT CAUSE FOUND: Sogou loads its real classes with a secondary
 * `dalvik.system.DelegateLastClassLoader` (hotfix loader), NOT with lp.classLoader
 * (PathClassLoader). Hooks installed on lp.classLoader therefore never fire.
 *
 * Fix: hook ClassLoader.loadClass on all loaders, and when a class load happens on a
 * DelegateLastClassLoader (or any non-default loader), install the real hooks onto that
 * loader's classes.
 */
public class MainHook implements IXposedHookLoadPackage {

    static final String TAG = "SogouSym: ";
    static final String PKG = "com.sohu.inputmethod.sogou";

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
    static final Set<Integer> INSTALLED = Collections.synchronizedSet(new HashSet<Integer>());
    static int cap = 0;

    static void dlog(String s) {
        if (cap < 300) {
            cap++;
            XposedBridge.log(TAG + s);
        }
    }

    static void ilog(String s) {
        if (SEEN.add(s)) {
            XposedBridge.log(TAG + s);
        }
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
    static void setSymbol(Object b, String key, String sym) {
        String orig = Mapping.origOf(key, CN_MODE);
        String display = (orig == null || orig.isEmpty()) ? sym : (sym + " " + orig);
        try {
            XposedHelpers.callMethod(b, "O2", display);   // MINOR_LABEL (corner)
        } catch (Throwable ignored) {
        }
        if (!Mapping.hasLongPress(key) || longPressEnabled()) {
            writeForeignKey(b, sym);
            return;
        }
        // Swipe-only: the corner still advertises the custom symbol, but a long-press has to behave
        // as if the key were untouched — so its submitted value is parked on this key's sentinel.
        String sent = SENT_BY_SECTION.get(key);
        if (sent != null) {
            writeForeignKey(b, sent);
        }
    }

    private static void writeForeignKey(Object b, String value) {
        try {
            Object fk = XposedHelpers.callMethod(b, "f0");
            if (fk != null) {
                // All five fields carry the same value on purpose: a long-press submits `g` (the
                // label the popup draws), so a separate display/commit pair is not possible.
                XposedHelpers.setObjectField(fk, "g", value);
                XposedHelpers.setObjectField(fk, "h", new int[]{value.charAt(0)});
                XposedHelpers.setObjectField(fk, "i", value);
                XposedHelpers.setObjectField(fk, "j", new int[]{value.charAt(0)});
                XposedHelpers.setIntField(fk, "l", value.charAt(0));
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (!PKG.equals(lp.packageName)) {
            return;
        }
        XposedBridge.log(TAG + "attached " + lp.packageName + " proc=" + lp.processName);
        refreshMapAsync();
        hookLoaderDiscovery();
        installAll(lp.classLoader);
    }

    /** Discover the real (hotfix) ClassLoader and hook its classes. */
    private void hookLoaderDiscovery() {
        try {
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object ldr = p.thisObject;
                        String lc = ldr.getClass().getName();
                        if (lc.contains("DelegateLastClassLoader") || lc.contains("PathClassLoader")
                                || lc.contains("DexClassLoader") || lc.contains("InMemoryDexClassLoader")) {
                            installAll((ClassLoader) ldr);
                        }
                        String n = (String) p.args[0];
                        if (n != null && n.contains("Theme") && n.contains("ogou")) {
                            ilog("loadClass " + n + " ldr=" + lc);
                        }
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
        if (cl == null || !INSTALLED.add(System.identityHashCode(cl))) {
            return;
        }
        XposedBridge.log(TAG + "installAll on " + cl.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(cl)));
        hookHpJ(cl);
        hookOnE(cl);
        hookK1B(cl);
        hookHpH(cl);
        hookTnP(cl);
        hookKeyCtor(cl);
        for (String c : new String[]{"ws", "ma2", "tf4", "cw7", "i40", "gn"}) {
            hookSectionParser(cl, c);
        }
        hookF0(cl);
        hookCommit(cl);
    }

    /** Hook the real commit class once it is loaded. */
    private void hookCommitOn(final Class<?> c) {
        if (!INSTALLED.add("commit".hashCode())) {
            return;
        }
        try {
            XposedBridge.hookAllMethods(c, "commitText", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        String t = String.valueOf(p.args[0]);
                        String caller = "";
                        StackTraceElement[] st = new Throwable().getStackTrace();
                        for (int i = 1; i < st.length && i < 8; i++) {
                            String cn = st[i].getClassName();
                            if (!cn.startsWith("de.robv") && !cn.equals("com.qoder.sogousym.MainHook")) {
                                caller = cn + "#" + st[i].getMethodName();
                                break;
                            }
                        }
                        XposedBridge.log(TAG + "COMMIT[" + t + "] from " + caller);
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + "hooked commitText (lazy) on " + c.getName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + "lazy hook commitText failed: " + t);
        }
    }

    /** Log the minor-label getter result (what the popup/render reads). */
    private void hookF0(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("com.sogou.theme.data.key.b", cl, "F0",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Object r = p.getResult();
                                String k = String.valueOf(XposedHelpers.callMethod(p.thisObject, "j"));
                                ilog("F0[" + k + "]=" + r);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            XposedBridge.log(TAG + "hooked b.F0");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook b.F0 failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod("com.sogou.theme.data.key.BaseKeyData", cl, "F0",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                String k = String.valueOf(XposedHelpers.callMethod(p.thisObject, "j"));
                                ilog("baseF0[" + k + "]=" + p.getResult());
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            XposedBridge.log(TAG + "hooked BaseKeyData.F0");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook BaseKeyData.F0 failed: " + t);
        }
    }

    /**
     * Log every text commit with its caller, and substitute the swipe-up symbol.
     *
     * Found via probe: a swipe-up commits through
     * BaseInputLogic#z0 -> BaseInputLogic#C -> commitText, while normal pinyin goes
     * through BaseInputLogic#z. So a commit whose stack contains BaseInputLogic#z0 is the
     * swipe-up one, and its text can be swapped for the configured symbol.
     */
    private void hookCommit(ClassLoader cl) {
        try {
            Class<?> cc = XposedHelpers.findClass(
                    "com.sogou.imskit.core.input.inputconnection.CachedInputConnection", cl);
            XC_MethodHook cb = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args.length == 0 || !(p.args[0] instanceof CharSequence)) {
                            return;
                        }
                        String t = String.valueOf(p.args[0]);
                        // The mode has to be resolved per commit: tn.P only registers a layout's
                        // key names the FIRST time it is parsed, so after an English/Chinese round
                        // trip the cached layout is reused without re-registering and CN_MODE would
                        // stay stuck on the previous mode.
                        Boolean commitMode = modeFromStack();
                        if (commitMode != null) {
                            CN_MODE = commitMode.booleanValue();
                        }
                        boolean slideUp = isSlideUp();
                        XposedBridge.log(TAG + "COMMIT[" + t + "] slideUp=" + slideUp
                                + " cn=" + CN_MODE + " from " + callerOf());
                        String sentSec = SENT2SECTION.get(t);
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
                            // Comma/period have no long-press, so they always apply; letters only on
                            // the gesture path, and only if that gesture is enabled. Either way the
                            // Sogou symbol panel must be left alone — otherwise typing the original
                            // symbol from it would come out as the custom one.
                            boolean punct = punctSet().contains(t);
                            if (!isSymbolPanelPath() && (punct || (slideUp && swipeEnabled()))) {
                                p.args[0] = rep;
                                XposedBridge.log(TAG + "replace " + t + " -> " + rep);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            // The process rewrites class names at runtime, so walk the whole hierarchy and hook
            // every commitText overload by reflection rather than trusting one resolved class.
            int n = 0;
            for (Class<?> c = cc; c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if ("commitText".equals(m.getName())) {
                        XposedBridge.hookMethod(m, cb);
                        n++;
                    }
                }
            }
            XposedBridge.log(TAG + "hooked commitText, overloads=" + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook commitText failed: " + t);
        }
    }

    /**
     * True when the current commit came from the Sogou symbol panel (符号列表) rather than from the
     * keyboard keys. Both end up in {@code BaseInputLogic#C}, but the panel's stack runs through
     * {@code PinyinInputLogic#p0} / the symbol-page message handler, while a key or a swipe runs
     * through {@code PinyinInputLogic#x0} (gesture) or {@code b54#L0} (punctuation key).
     */
    private static boolean isSymbolPanelPath() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
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
    private static Boolean modeFromStack() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
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
     * Chinese routes long-press and swipe-up through `BaseInputLogic#z0`; English (the Typany
     * engine) routes both through `com.typany.shell.Interface#handleSecondaryInput`. Neither
     * separates long-press from swipe, which is fine: in "both" mode a long-press commits the
     * custom symbol already, so only the swipe-up can commit a key's original symbol.
     */
    private static boolean isSlideUp() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String cn = e.getClassName();
            String mn = e.getMethodName();
            if (cn.endsWith("BaseInputLogic") && "z0".equals(mn)) {
                return true;
            }
            if (cn.endsWith("typany.shell.Interface") && "handleSecondaryInput".equals(mn)) {
                return true;
            }
        }
        return false;
    }

    /** Skip Xposed dispatch/reflection frames and return the first few real callers. */
    private static String callerOf() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (StackTraceElement e : new Throwable().getStackTrace()) {
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
                                dlog("hp.j type=" + p.args[0] + " sec=" + sec);
                                Object res = p.getResult();
                                if (res != null && sec instanceof String) {
                                    noteMode((String) sec);
                                    String sym = customOf((String) sec);
                                    if (sym != null) {
                                        setSymbol(res, (String) sec, sym);
                                        ilog("hp.j FORCE " + sec + " -> " + sym);
                                    }
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "hp.j err " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + "hooked hp.j");
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
                                dlog("on.e sec=" + sec);
                                if (sec instanceof String) {
                                    String s = (String) sec;
                                    // MINOR_LABEL drives the corner AND the long-press output, so in
                                    // swipe-only mode leave letter keys completely alone.
                                    if (longPressEnabled() || !Mapping.hasLongPress(s)) {
                                        String sym = customOf(s);
                                        Object a = p.args[1];
                                        if (sym != null && a instanceof Map) {
                                            ((Map) a).put("MINOR_LABEL", sym);
                                            ilog("on.e INJECT " + s);
                                        }
                                    }
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "on.e err " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + "hooked on.e");
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
                                if ("MINOR_LABEL".equalsIgnoreCase(attr)) {
                                    dlog("k1.B key=" + key + " val=" + p.args[3]);
                                }
                                String sym = customOf(key);
                                if (sym != null && "MINOR_LABEL".equalsIgnoreCase(attr)) {
                                    setSymbol(p.args[0], key, sym);
                                    ilog("k1.B FORCE " + key + " -> " + sym);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "k1.B err " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + "hooked k1.B");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook k1.B failed: " + t);
        }
    }

    private void hookHpH(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("hp", cl, "h", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    dlog("hp.h path=" + p.args[0]);
                }
            });
            XposedBridge.log(TAG + "hooked hp.h");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook hp.h failed: " + t);
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
                        ilog("tn.P name=" + s);
                    }
                }
            });
            XposedBridge.log(TAG + "hooked tn.P");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook tn.P failed: " + t);
        }
    }

    private void hookKeyCtor(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookConstructor("com.sogou.theme.data.key.b", cl, new XC_MethodHook() {
            });
            XposedBridge.log(TAG + "hooked b.<init>");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook b.<init> failed: " + t);
        }
    }

    private void hookSectionParser(ClassLoader cl, final String cls) {
        try {
            XposedHelpers.findAndHookMethod(cls, cl, "e",
                    String.class, "androidx.collection.ArrayMap", "tn",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            dlog(cls + ".e sec=" + p.args[0]);
                        }
                    });
            XposedBridge.log(TAG + "hooked " + cls + ".e");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook " + cls + ".e failed: " + t);
        }
    }
}
