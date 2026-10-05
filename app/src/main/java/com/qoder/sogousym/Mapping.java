package com.qoder.sogousym;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in table of the Sogou 26-key letter keys, arranged like the on-screen keyboard.
 *
 * The 26-key QWERTY keyboard uses different key-section names per input mode:
 *  - Chinese (pinyin) layout: assets/theme/theme_default/1080/port/26.ini
 *  - English layout:          assets/foreign/1/theme/1080/phone/port/65536.ini
 *
 * Most letters share one section name across both modes; S/H/J/L use a "_PY" / "_EN"
 * variant because Sogou assigns them different long-press symbols. Comma/period use the
 * code-driven "UDSymbol" keys of the 26-key layout.
 *
 * Section names come from the theme's layout files (26.ini for Chinese, 65536.ini for
 * English). The original symbols are the characters a long-press actually commits, measured
 * on device: they differ from the theme's MINOR_LABEL for Chinese punctuation, where Sogou
 * converts the half-width label to full-width at commit time (e.g. key Z shows "(" but
 * commits "\uFF08"). Keys Z/X/B/N share one section across modes yet still commit different
 * characters per mode, so their pyOrig is the Chinese (full-width) output and enOrig the
 * English one.
 */
public final class Mapping {

    /** One key (a letter or a punctuation key) of the 26-key keyboard. */
    public static final class Key {
        public final String letter;
        public final String pySection;
        public final String pyOrig;
        public final String enSection;
        public final String enOrig;
        public final int row;

        Key(int row, String letter, String pySection, String pyOrig, String enSection, String enOrig) {
            this.row = row;
            this.letter = letter;
            this.pySection = pySection;
            this.pyOrig = pyOrig;
            this.enSection = enSection;
            this.enOrig = enOrig;
        }

        /**
         * Config keys this key's symbols are stored under: Chinese first, English second.
         * Always two distinct keys, so every key can be customised per mode.
         */
        public List<String> sections() {
            List<String> out = new ArrayList<String>(2);
            out.add(pyKey());
            out.add(enKey());
            return out;
        }

        /**
         * Keys Z/X/B/N/C/V/M and most letters report the SAME section name to the theme parser in
         * both modes, so a "_PY"/"_EN" suffix is invented to keep their two symbols apart. Keys
         * that already have distinct section names (S/H/J/L, comma/period) use those names as-is.
         */
        public String pyKey() {
            return shared() ? pySection + "_PY" : pySection;
        }

        public String enKey() {
            return shared() ? pySection + "_EN" : enSection;
        }

        public boolean shared() {
            return pySection.equals(enSection);
        }
    }

    /** All keys in QWERTY order (3 letter rows + a comma/period row). */
    public static final List<Key> KEYS;

    static {
        List<Key> k = new ArrayList<Key>(28);
        // Row 1
        k.add(new Key(1, "Q", "Key_Q", "1", "Key_Q", "1"));
        k.add(new Key(1, "W", "Key_W", "2", "Key_W", "2"));
        k.add(new Key(1, "E", "Key_E", "3", "Key_E", "3"));
        k.add(new Key(1, "R", "Key_R", "4", "Key_R", "4"));
        k.add(new Key(1, "T", "Key_T", "5", "Key_T", "5"));
        k.add(new Key(1, "Y", "Key_Y", "6", "Key_Y", "6"));
        k.add(new Key(1, "U", "Key_U", "7", "Key_U", "7"));
        k.add(new Key(1, "I", "Key_I", "8", "Key_I", "8"));
        k.add(new Key(1, "O", "Key_O", "9", "Key_O", "9"));
        k.add(new Key(1, "P", "Key_P", "0", "Key_P", "0"));
        // Row 2
        k.add(new Key(2, "A", "Key_A", "~", "Key_A", "~"));
        k.add(new Key(2, "S", "Key_S_PY", "\uFF01", "Key_S_EN", "!"));
        k.add(new Key(2, "D", "Key_D", "@", "Key_D", "@"));
        k.add(new Key(2, "F", "Key_F", "#", "Key_F", "#"));
        k.add(new Key(2, "G", "Key_G", "%", "Key_G", "%"));
        k.add(new Key(2, "H", "Key_H_PY", "\u201C", "Key_H_EN", "'"));
        k.add(new Key(2, "J", "Key_J_PY", "\u201D", "Key_J_EN", "&"));
        k.add(new Key(2, "K", "Key_K", "*", "Key_K", "*"));
        k.add(new Key(2, "L", "Key_L_PY", "\uFF1F", "Key_L_EN", "?"));
        // Row 3
        k.add(new Key(3, "Z", "Key_Z", "\uFF08", "Key_Z", "("));
        k.add(new Key(3, "X", "Key_X", "\uFF09", "Key_X", ")"));
        k.add(new Key(3, "C", "Key_C", "-", "Key_C", "-"));
        k.add(new Key(3, "V", "Key_V", "_", "Key_V", "_"));
        k.add(new Key(3, "B", "Key_B", "\uFF1A", "Key_B", ":"));
        k.add(new Key(3, "N", "Key_N", "\uFF1B", "Key_N", ";"));
        k.add(new Key(3, "M", "Key_M", "/", "Key_M", "/"));
        // Row 4 - comma / period of the 26-key layout
        k.add(new Key(4, "\uFF0C", "Key_UDSymbol1_Qwerty", "\uFF0C", "Key_CommaEn_T", ","));
        k.add(new Key(4, "\u3002", "Key_UDSymbol2_Qwerty", "\u3002", "Key_PeriodEn_T", "."));
        KEYS = Collections.unmodifiableList(k);
    }

    public static List<Key> row(int n) {
        List<Key> out = new ArrayList<Key>();
        for (Key k : KEYS) {
            if (k.row == n) {
                out.add(k);
            }
        }
        return out;
    }

    /** Row 4 is the comma/period row: those keys have no long-press, only a direct output. */
    public static boolean hasLongPress(String section) {
        for (Key k : KEYS) {
            if (k.pySection.equals(section) || k.enSection.equals(section)) {
                return k.row != 4;
            }
        }
        return true;
    }

    /** Row 4 is the comma/period row: those keys have no long-press, only a direct output. */
    public static boolean hasLongPress(Key k) {
        return k.row != 4;
    }

    /** Runtime section name -> config key, per mode (see {@link Key#pyKey()}). */
    private static final Map<String, String> RUNTIME_PY = buildRuntime(true);
    private static final Map<String, String> RUNTIME_EN = buildRuntime(false);

    private static Map<String, String> buildRuntime(boolean chinese) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (Key k : KEYS) {
            m.put(chinese ? k.pySection : k.enSection, chinese ? k.pyKey() : k.enKey());
        }
        return Collections.unmodifiableMap(m);
    }

    /**
     * The config key holding {@code section}'s custom symbol for the given mode. Shared keys
     * answer differently per mode even though the parser passes one name, which is why the
     * caller has to say which mode it is.
     */
    public static String configKeyOf(String section, boolean chinese) {
        Map<String, String> m = chinese ? RUNTIME_PY : RUNTIME_EN;
        String k = m.get(section);
        return k != null ? k : section;
    }

    /** section name -> original symbol, per input mode (for the "custom + original" display). */
    private static final Map<String, String> PY_ORIG = buildOrig(true);
    private static final Map<String, String> EN_ORIG = buildOrig(false);

    private static Map<String, String> buildOrig(boolean chinese) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (Key k : KEYS) {
            m.put(chinese ? k.pySection : k.enSection, chinese ? k.pyOrig : k.enOrig);
        }
        return Collections.unmodifiableMap(m);
    }

    /**
     * The original symbol of {@code section} in the given mode. Keys Z/X/B/N share one section
     * across modes yet commit a full-width character in Chinese and a half-width one in English,
     * so the mode has to be supplied by the caller.
     */
    public static String origOf(String section, boolean chinese) {
        return (chinese ? PY_ORIG : EN_ORIG).get(section);
    }

    private Mapping() {
    }
}
