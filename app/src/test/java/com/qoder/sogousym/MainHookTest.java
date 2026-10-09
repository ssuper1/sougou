package com.qoder.sogousym;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.Assert.*;

public class MainHookTest {
    private static final Supplier<StackTraceElement[]> NO_STACK = () -> {
        throw new AssertionError("This commit must not capture a stack");
    };

    @Before
    public void setUp() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("Key_S_PY", "+");
        config.put("Key_S_EN", "=");
        config.put("Key_C_PY", "q");
        config.put("Key_C_EN", "w");
        config.put("Key_CommaEn_T", "+");
        config.put("Key_PeriodEn_T", "y");
        config.put("Key_UDSymbol1_Qwerty", "8");
        config.put("Key_UDSymbol2_Qwerty", "9");
        MainHook.MAP = config;
        MainHook.MODE = "both";
        MainHook.VER = 2;
        MainHook.CN_MODE = true;
        MainHook.buildOrigIndex();
        MainHook.buildSentinels();
    }

    private static StackTraceElement frame(String type, String method) {
        return new StackTraceElement(type, method, "Input.java", 1);
    }

    private static StackTraceElement[] chineseGesture(int version) {
        return new StackTraceElement[]{
                frame("com.sogou.BaseInputLogic", version == 2 ? "D" : "C"),
                frame("com.sogou.BaseInputLogic", version == 2 ? "D0" : "z0"),
                frame("com.sogou.PinyinInputLogic", version == 2 ? "B0" : "x0")};
    }

    private static StackTraceElement[] chinesePanel(int version) {
        return new StackTraceElement[]{
                frame("com.sogou.BaseInputLogic", version == 2 ? "D0" : "z0"),
                frame("com.sogou.PinyinInputLogic", version == 2 ? "J0" : "p0"),
                frame("com.sogou.PinyinInputLogic", version == 2 ? "t0" : "p0")};
    }

    private static StackTraceElement[] english(boolean secondary) {
        return new StackTraceElement[]{frame("com.typany.shell.Interface",
                secondary ? "handleSecondaryInput" : "handleInput")};
    }

    @Test
    public void hotfixLoaderIsDiscoveredEvenWhenEngineNamesBypassBaseLoadClass() {
        assertTrue(MainHook.isDiscoveryTrigger("dalvik.system.DelegateLastClassLoader",
                "android.view.View"));
        for (String loader : Arrays.asList("dalvik.system.PathClassLoader",
                "dalvik.system.DexClassLoader", "dalvik.system.InMemoryDexClassLoader")) {
            assertFalse(MainHook.isDiscoveryTrigger(loader, "android.view.View"));
            assertTrue(MainHook.isDiscoveryTrigger(loader, "hp"));
            assertTrue(MainHook.isDiscoveryTrigger(loader,
                    "com.sogou.imskit.core.input.inputconnection.CachedInputConnection"));
        }
    }

    @Test
    public void discoveryRetiresOnBaseEngineButOnlyWhenLateHotfixesCanStillBeObserved() {
        for (int version : new int[]{1, 2}) {
            assertFalse(MainHook.canRetireDiscovery(version, false, true, true));
            assertFalse(MainHook.canRetireDiscovery(version, true, false, true));
            assertTrue(MainHook.canRetireDiscovery(version, true, true, true));
        }
        assertFalse(MainHook.canRetireDiscovery(1, true, true, false));
        assertTrue(MainHook.canRetireDiscovery(2, true, true, false));
    }

    @Test
    public void punctuationKeysReplaceInEveryModeButPanelAndSharedLogicDoNot() {
        for (int version : new int[]{1, 2}) {
            MainHook.VER = version;
            for (String mode : new String[]{"both", "swipe", "longpress"}) {
                MainHook.MODE = mode;
                StackTraceElement[] direct = {
                        frame("com.sogou.BaseInputLogic", version == 2 ? "D0" : "z0"),
                        frame("com.sogou.PinyinInputLogic", version == 2 ? "J0" : "F0"),
                        frame("com.sogou.core.input.chinese.inputsession."
                                + (version == 2 ? "d3" : "w0"), "handleMessage")};
                assertEquals("8", MainHook.replacementFor("\uFF0C", () -> direct));
                assertEquals("9", MainHook.replacementFor("\u3002", () -> direct));
                assertEquals("\uFF0C", MainHook.replacementFor("\uFF0C", () -> chinesePanel(version)));
                assertEquals("\u3002", MainHook.replacementFor("\u3002", () -> chinesePanel(version)));
                assertEquals("\uFF0C", MainHook.replacementFor("\uFF0C",
                        () -> Arrays.copyOf(direct, 2)));
                StackTraceElement[] primary = {frame("com.typany.shell.Interface", "handlePrimaryInput")};
                assertEquals("+", MainHook.replacementFor(",", () -> primary));
                assertEquals("y", MainHook.replacementFor(".", () -> primary));
                assertEquals(",", MainHook.replacementFor(",", () -> english(false)));
                assertEquals(".", MainHook.replacementFor(".", () -> english(false)));
            }
        }
    }

    @Test
    public void ordinaryTypingAndUnconfiguredSymbolsNeverCaptureStack() {
        for (String text : Arrays.asList("", "hello", "\u4f60\u597d", "12345", "\uFF1F", "+")) {
            assertSame(text, MainHook.replacementFor(text, NO_STACK));
        }
    }

    @Test
    public void pasteUndoOnlyConsumesXGesturesAllowedByTheSelectedMode() {
        for (String mode : Arrays.asList("both", "swipe", "longpress")) {
            MainHook.MODE = mode;
            boolean swipe = !"longpress".equals(mode);
            boolean longPress = !"swipe".equals(mode);
            assertEquals(swipe, MainHook.isUndoPasteTrigger("\uFF09", () -> chineseGesture(2)));
            assertEquals(swipe, MainHook.isUndoPasteTrigger(")", () -> english(true)));
            assertEquals(longPress, MainHook.isUndoPasteTrigger(MainHook.UNDO_PASTE, () -> chineseGesture(2)));
            assertFalse(MainHook.isUndoPasteTrigger("\uFF09", () -> chinesePanel(2)));
            assertFalse(MainHook.isUndoPasteTrigger(")", () -> english(false)));
            assertFalse(MainHook.isUndoPasteTrigger(MainHook.UNDO_PASTE, () -> english(false)));
            assertFalse(MainHook.isUndoPasteTrigger("x", NO_STACK));
            assertFalse(MainHook.isUndoPasteTrigger("normal typing", NO_STACK));
        }
        assertEquals(MainHook.UNDO_LABEL, MainHook.popupDisplay(MainHook.UNDO_PASTE));
    }

    @Test
    public void undoCornersShowTheOriginalSymbolForEveryLetterAndInputMode() {
        for (Mapping.Key key : Mapping.KEYS) {
            if (key.row == 4) {
                continue;
            }
            MainHook.CN_MODE = true;
            assertEquals(MainHook.UNDO_LABEL + " " + key.pyOrig,
                    MainHook.cornerDisplay(key.pySection, MainHook.UNDO_PASTE));
            MainHook.CN_MODE = false;
            assertEquals(MainHook.UNDO_LABEL + " " + key.enOrig,
                    MainHook.cornerDisplay(key.enSection, MainHook.UNDO_PASTE));
        }
        assertEquals("+ !", MainHook.cornerDisplay("Key_S_EN", "+"));
        assertEquals(MainHook.UNDO_LABEL, MainHook.popupDisplay(MainHook.UNDO_PASTE));
    }

    @Test
    public void pasteUndoCanUseAnyLetterKeyAsItsTrigger() {
        Map<String, String> saved = MainHook.MAP;
        Map<String, String> config = new LinkedHashMap<>(saved);
        config.put("#undoPaste", "1");
        config.put("#undoKey", "Z");
        try {
            MainHook.MAP = config;
            MainHook.buildOrigIndex();
            MainHook.buildSentinels();
            MainHook.MODE = "both";
            assertEquals(MainHook.UNDO_PASTE, MainHook.customOf("Key_Z"));
            assertEquals(null, MainHook.customOf("Key_X"));
            assertTrue(MainHook.isUndoPasteTrigger(MainHook.UNDO_PASTE, () -> chineseGesture(2)));
            assertTrue(MainHook.isUndoPasteTrigger("（", () -> chineseGesture(2)));
            assertFalse(MainHook.isUndoPasteTrigger("）", () -> chineseGesture(2)));
        } finally {
            MainHook.MAP = saved;
            MainHook.buildOrigIndex();
            MainHook.buildSentinels();
        }
    }

    @Test
    public void longPressOnlyDoesNotRewriteOriginalsOrCaptureStack() {
        MainHook.MODE = "longpress";
        for (String text : Arrays.asList("\uFF01", "!", "-", "+")) {
            assertSame(text, MainHook.replacementFor(text, NO_STACK));
        }
    }

    @Test
    public void unchangedMappingsDoNotCaptureStack() {
        MainHook.ORIG2CUSTOM_PY = java.util.Collections.singletonMap("!", "!");
        MainHook.ORIG2CUSTOM_EN = java.util.Collections.singletonMap("!", "!");
        assertEquals("!", MainHook.replacementFor("!", NO_STACK));
    }

    @Test
    public void chineseSwipeWorksInBothSupportedVersionsAndModes() {
        for (int version : new int[]{1, 2}) {
            MainHook.VER = version;
            for (String mode : new String[]{"both", "swipe"}) {
                MainHook.MODE = mode;
                MainHook.CN_MODE = false;
                assertEquals("+", MainHook.replacementFor("\uFF01", () -> chineseGesture(version)));
                assertTrue(MainHook.CN_MODE);
            }
        }
    }

    @Test
    public void chineseSymbolPanelsKeepOriginalsInEveryModeAndVersion() {
        for (int version : new int[]{1, 2}) {
            MainHook.VER = version;
            for (String mode : new String[]{"both", "swipe", "longpress"}) {
                MainHook.MODE = mode;
                assertEquals("\uFF01", MainHook.replacementFor("\uFF01", () -> chinesePanel(version)));
            }
        }
    }

    @Test
    public void secondarySubmissionAloneDoesNotCountAsKeyboardGesture() {
        assertEquals("\uFF01", MainHook.replacementFor("\uFF01",
                () -> new StackTraceElement[]{frame("com.sogou.BaseInputLogic", "D0")}));
    }

    @Test
    public void englishGesturesReplaceButSymbolSelectionsDoNot() {
        for (String mode : new String[]{"both", "swipe"}) {
            MainHook.MODE = mode;
            assertEquals("=", MainHook.replacementFor("!", () -> english(true)));
            assertFalse(MainHook.CN_MODE);
            assertEquals("!", MainHook.replacementFor("!", () -> english(false)));
            assertEquals(",", MainHook.replacementFor(",", () -> english(false)));
        }
    }

    @Test
    public void switchingModesUsesSeparateMappingsForSharedOriginal() {
        assertEquals("q", MainHook.replacementFor("-", () -> chineseGesture(2)));
        assertEquals("w", MainHook.replacementFor("-", () -> english(true)));
        assertEquals("q", MainHook.replacementFor("-", () -> chineseGesture(2)));
    }

    @Test
    public void normalTypingRouteDoesNotRewriteMatchingOriginal() {
        assertEquals("-", MainHook.replacementFor("-", () -> new StackTraceElement[]{
                frame("com.sogou.BaseInputLogic", "z")}));
        assertEquals("!", MainHook.replacementFor("!", () -> new StackTraceElement[0]));
    }

    @Test
    public void everySentinelRestoresItsOwnModeWithoutStackOrFurtherSubstitution() {
        MainHook.MODE = "swipe";
        assertEquals(52, MainHook.SENT_BY_CONFIG_KEY.size());
        for (Mapping.Key key : Mapping.KEYS) {
            if (!Mapping.hasLongPress(key)) {
                continue;
            }
            for (boolean currentMode : new boolean[]{true, false}) {
                MainHook.CN_MODE = currentMode;
                String py = MainHook.SENT_BY_CONFIG_KEY.get(key.pyKey());
                String en = MainHook.SENT_BY_CONFIG_KEY.get(key.enKey());
                assertNotEquals(py, en);
                assertEquals(key.pyOrig, MainHook.replacementFor(py, NO_STACK));
                assertEquals(key.enOrig, MainHook.replacementFor(en, NO_STACK));
            }
        }
    }

    @Test
    public void popupConvertsAllSentinelsAndCachesConvertedStrings() {
        for (Map.Entry<String, String> e : MainHook.SENT2ORIGINAL.entrySet()) {
            String input = "prefix" + e.getKey() + "\uD83D\uDE00";
            String display = MainHook.popupDisplay(input);
            assertEquals("prefix" + e.getValue() + "\uD83D\uDE00", display);
            assertSame(display, MainHook.popupDisplay(input));
        }
        String ordinary = "abc\uFF01\uD83D\uDE00\uE100";
        assertSame(ordinary, MainHook.popupDisplay(ordinary));
    }

    @Test
    public void popupPreservesMixedLabelsAndDoesNotMutateInputList() {
        String sentinel = MainHook.SENT_BY_CONFIG_KEY.get("Key_S_PY");
        Object marker = new Object();
        List<Object> original = Arrays.asList("normal", sentinel, marker, null);
        Object display = MainHook.popupDisplayValue(original);
        assertEquals(Arrays.asList("normal", "\uFF01", marker, null), display);
        assertEquals(sentinel, original.get(1));
        List<String> normal = Arrays.asList("normal", "!");
        assertSame(normal, MainHook.popupDisplayValue(normal));
    }

    public static class PopupFixture {
        public Object c;
        public Object j;
        public Object a;
    }

    @Test
    public void drawRestoresExactInputObjectsEvenWhenDrawingThrowsAndStateIsReused() throws Exception {
        PopupFixture popup = new PopupFixture();
        String sentinel = MainHook.SENT_BY_CONFIG_KEY.get("Key_S_PY");
        popup.c = sentinel;
        popup.j = "normal";
        popup.a = Arrays.asList(sentinel, "other");
        Object originalLabels = popup.a;
        MainHook.PopupDrawState state = new MainHook.PopupDrawState();
        for (int pass = 0; pass < 3; pass++) {
            try {
                state.replace(popup, PopupFixture.class.getField("c"), 0);
                state.replace(popup, PopupFixture.class.getField("j"), 1);
                state.replace(popup, PopupFixture.class.getField("a"), 2);
                assertEquals("\uFF01", popup.c);
                assertEquals(Arrays.asList("\uFF01", "other"), popup.a);
                throw new IllegalStateException("Simulated onDraw failure");
            } catch (IllegalStateException expected) {
                assertEquals("Simulated onDraw failure", expected.getMessage());
            } finally {
                state.restore();
            }
            assertSame(sentinel, popup.c);
            assertSame(originalLabels, popup.a);
            for (Object owner : state.owners) {
                assertNull(owner);
            }
        }
    }

    @Test
    public void partialDrawSetupStillRestoresEarlierChanges() throws Exception {
        PopupFixture popup = new PopupFixture();
        String sentinel = MainHook.SENT_BY_CONFIG_KEY.get("Key_S_EN");
        popup.c = sentinel;
        MainHook.PopupDrawState state = new MainHook.PopupDrawState();
        try {
            state.replace(popup, PopupFixture.class.getField("c"), 0);
            Field wrong = String.class.getDeclaredField("value");
            state.replace(popup, wrong, 1);
            fail("Expected inaccessible or wrong-owner field");
        } catch (IllegalAccessException | IllegalArgumentException expected) {
            // A changed popup schema must not leave previously swapped input fields behind.
        } finally {
            state.restore();
        }
        assertSame(sentinel, popup.c);
    }

    @Test
    public void reusedMutableLabelListReflectsModeChangesAndRestoresItsIdentity() throws Exception {
        PopupFixture popup = new PopupFixture();
        String py = MainHook.SENT_BY_CONFIG_KEY.get("Key_S_PY");
        String en = MainHook.SENT_BY_CONFIG_KEY.get("Key_S_EN");
        List<String> labels = new java.util.ArrayList<>(Arrays.asList(py));
        popup.a = labels;
        MainHook.PopupDrawState state = new MainHook.PopupDrawState();
        Field field = PopupFixture.class.getField("a");
        state.replace(popup, field, 2);
        assertEquals(Arrays.asList("\uFF01"), popup.a);
        state.restore();
        assertSame(labels, popup.a);
        labels.set(0, en);
        state.replace(popup, field, 2);
        assertEquals(Arrays.asList("!"), popup.a);
        state.restore();
        assertSame(labels, popup.a);
        assertEquals(en, labels.get(0));
    }
}
