package com.qoder.sogousym;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * UI: a keyboard-shaped panel for customising the Sogou 26-key long-press / swipe symbols.
 * Visual language mirrors the superMi app: gradient AppBar, white 16dp cards, a segmented
 * control, and a small set of shape drawables (primary / soft / ghost / hint / seg).
 */
public class MainActivity extends Activity {

    private static final String SOGOU = "com.sohu.inputmethod.sogou";
    private static final String[] SU_CANDIDATES = {
            "/product/bin/su", "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/data/adb/ksu/bin/su", "su"};

    /** Per-key views, so a customised key can be highlighted. */
    private static final class KeyViews {
        View cell;
        TextView letter;
        final List<EditText> fields = new ArrayList<EditText>();
        /** Parallel to {@link #fields}; the 中/英 label, or null for a shared field. */
        final List<TextView> labels = new ArrayList<TextView>();
    }

    private final Map<String, EditText> fields = new LinkedHashMap<String, EditText>();
    private final Map<Mapping.Key, KeyViews> keyViews = new LinkedHashMap<Mapping.Key, KeyViews>();
    private TextView rootStatus;
    private EditText testBox;
    private View hintBar;
    private Runnable imeRetry;
    private boolean activityResumed;
    private boolean restartInProgress;

    /** Which gesture the custom symbol applies to: "both" | "longpress" | "swipe". */
    private String mode = "both";
    private Button modeBtnLong;
    private Button modeBtnSwipe;
    private Button modeBtnBoth;
    private Button rotateBtn;
    private LinearLayout keyboardCardView;
    /** Last seen night-mode bit, so a dark-mode switch can rebuild the activity's colours. */
    private int lastNightMode;

    /** "大键" layout: roomier key cells with clear row separation. */
    private boolean bigKey;
    private int keyFieldH;
    private int keyPadV;
    private int keyRowPad;
    private int keyMargin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lastNightMode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(c(R.color.page_bg));
        final View topBar = header();
        wrap.addView(topBar);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(12), 0, dp(8));

        Map<String, String> current = Prefs.load(this);
        bigKey = "1".equals(current.get("#bigKey"));
        applyKeyMetrics();
        String m = current.get("#mode");
        if (m != null && !m.isEmpty()) {
            mode = m;
        }

        hintBar = hintBar();
        if (Prefs.isHintSeen(this) || !hasLegacyPunctuationDisplay()) {
            hintBar.setVisibility(View.GONE);
        }
        content.addView(hintBar);
        content.addView(testCard());
        content.addView(keyboardCard(current));
        content.addView(modeCard());
        content.addView(actionCard());
        content.addView(rootCard());
        View nav = navCard();
        LinearLayout.LayoutParams nlp = (LinearLayout.LayoutParams) nav.getLayoutParams();
        if (nlp != null) {
            nlp.bottomMargin = 0;
        }
        content.addView(nav);
        wrap.addView(content);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(c(R.color.page_bg));
        sv.setFillViewport(true);
        sv.addView(wrap);
        final LinearLayout contentRef = content;
        // Android 15+ forces edge-to-edge for apps targeting SDK 35+, so the app bar would sit under
        // the status bar. Older releases hand the insets to the decor instead, which means this
        // listener sees 0 there and adds nothing.
        sv.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top;
                int bottom;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                    top = bars.top;
                    bottom = bars.bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                topBar.setPadding(dp(18), dp(12) + top, dp(14), dp(12));
                contentRef.setPadding(0, dp(12), 0, dp(8) + bottom);
                return insets;
            }
        });
        setContentView(sv);
        sv.requestApplyInsets();

        checkRoot();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        cancelImeRetry();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cancelImeRetry();
        super.onDestroy();
    }

    // ------------------------------------------------------------------------ top bar

    /** Plain solid AppBar: logo + title/subtitle + the "!" help toggle + the rotate action. */
    private View header() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(dr(R.drawable.bg_header));
        bar.setMinimumHeight(dp(64));
        bar.setPadding(dp(18), dp(12), dp(14), dp(12));

        FrameLayout logo = new FrameLayout(this);
        View tile = new View(this);
        tile.setBackground(dr(R.drawable.bg_btn_header));
        logo.addView(tile, new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER));
        TextView glyph = new TextView(this);
        glyph.setText("搜");
        glyph.setTextColor(c(R.color.header_ctl_text));
        glyph.setTextSize(15);
        glyph.setTypeface(Typeface.DEFAULT_BOLD);
        glyph.setGravity(Gravity.CENTER);
        logo.addView(glyph, new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER));
        bar.addView(logo, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), 0, dp(8), 0);
        TextView title = new TextView(this);
        title.setText("搜狗 26 键 · 符号自定义");
        title.setTextColor(c(R.color.header_text));
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(title);
        TextView sub = new TextView(this);
        sub.setText("长按 / 上划 · 出你设的符号");
        sub.setTextColor(c(R.color.header_sub));
        sub.setTextSize(11);
        col.addView(sub);
        bar.addView(col, weight1());

        bar.addView(headerCircle("!", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (hintBar == null) {
                    return;
                }
                boolean show = hintBar.getVisibility() != View.VISIBLE;
                hintBar.setVisibility(show ? View.VISIBLE : View.GONE);
                if (!show) {
                    Prefs.setHintSeen(MainActivity.this);
                }
            }
        }), new LinearLayout.LayoutParams(dp(22), dp(22)));

        rotateBtn = headerPill("", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleOrientation();
            }
        });
        rotateBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
                toast("已恢复跟随系统旋转");
                updateRotateLabel();
                return true;
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        rlp.leftMargin = dp(8);
        bar.addView(rotateBtn, rlp);
        updateRotateLabel();
        return bar;
    }

    private void toggleOrientation() {
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        setRequestedOrientation(landscape
                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        updateRotateLabel();
    }

    /** Label shows what a tap will switch to. */
    private void updateRotateLabel() {
        if (rotateBtn == null) {
            return;
        }
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        rotateBtn.setText(landscape ? "竖屏" : "横屏");
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // uiMode is claimed in the manifest (needed for the orientation handling), so a system
        // dark-mode switch does not recreate the activity on its own and the colours would go stale.
        int night = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (night != lastNightMode) {
            lastNightMode = night;
            recreate();
            return;
        }
        updateRotateLabel();
        applyKeyboardInsets();
    }

    private Button headerCircle(String glyph, View.OnClickListener l) {
        Button b = plainButton(glyph, l);
        b.setTextSize(11);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(dr(R.drawable.bg_circle_header));
        b.setTextColor(c(R.color.header_ctl_text));
        return b;
    }

    private Button headerPill(String text, View.OnClickListener l) {
        Button b = plainButton(text, l);
        b.setTextSize(12);
        b.setBackground(dr(R.drawable.bg_btn_header));
        b.setTextColor(c(R.color.header_ctl_text));
        b.setPadding(dp(12), 0, dp(12), 0);
        return b;
    }

    /** Inline help banner (accent-tinted), toggled by the "!" in the AppBar. */
    private View hintBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(dr(R.drawable.bg_hint));
        bar.setPadding(dp(12), dp(8), dp(6), dp(8));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(dp(16), 0, dp(16), dp(12));
        bar.setLayoutParams(blp);

        TextView msg = new TextView(this);
        msg.setText("搜狗 v12 的逗号、句号键面暂保留原符号。");
        msg.setTextSize(11);
        msg.setTextColor(c(R.color.blue_text));
        msg.setLineSpacing(dp(2), 1f);
        bar.addView(msg, weight1());

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(13);
        close.setTextColor(c(R.color.blue_text));
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(8), dp(4), dp(8), dp(4));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.setHintSeen(MainActivity.this);
                if (hintBar != null) {
                    hintBar.setVisibility(View.GONE);
                }
            }
        });
        bar.addView(close);
        return bar;
    }

    // ------------------------------------------------------------------------ cards

    private View testCard() {
        LinearLayout card = rowCard();
        LinearLayout r = row();
        r.addView(label("测试输入框"));

        testBox = new EditText(this);
        testBox.setHint("点这里弹搜狗键盘测试");
        testBox.setHintTextColor(c(R.color.text_tertiary));
        testBox.setTextColor(c(R.color.text_primary));
        testBox.setTextSize(14);
        testBox.setInputType(InputType.TYPE_CLASS_TEXT);
        testBox.setMaxLines(1);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(c(R.color.seg_bg));
        inputBg.setCornerRadius(dp(10));
        testBox.setBackground(inputBg);
        testBox.setPadding(dp(12), dp(4), dp(12), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        lp.leftMargin = dp(10);
        r.addView(testBox, lp);
        card.addView(r, matchLp());
        return card;
    }

    private View keyboardCard(Map<String, String> current) {
        LinearLayout card = card();
        keyboardCardView = card;
        applyKeyboardInsets();
        Button resetKb = softButton("重置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReset();
            }
        });
        resetKb.setTextSize(12);
        Button sizeKb = softButton(bigKey ? "标准" : "大键", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bigKey = !bigKey;
                persist();
                recreate();
            }
        });
        sizeKb.setTextSize(12);
        card.addView(sectionHeader("26 键面板", "长按某键单独清空", sizeKb, resetKb));
        LinearLayout board = new LinearLayout(this);
        board.setOrientation(LinearLayout.VERTICAL);
        board.setPadding(0, dp(4), 0, dp(2));
        if (bigKey) {
            addGrid(board, current);
        } else {
            addRow(board, 1, 0f, current);
            addRow(board, 2, 0.55f, current);
            addRow(board, 3, 1.3f, current);
            addBottomRow(board, current);
        }
        card.addView(board);

        TextView warn = new TextView(this);
        warn.setText("搜狗 v12 的逗号、句号键面暂保留原符号。");
        warn.setTextSize(10);
        warn.setTextColor(c(R.color.text_tertiary));
        warn.setPadding(0, dp(6), 0, 0);
        if (hasLegacyPunctuationDisplay()) {
            card.addView(warn);
        }
        return card;
    }

    private boolean hasLegacyPunctuationDisplay() {
        try {
            String version = getPackageManager().getPackageInfo(SOGOU, 0).versionName;
            return version != null && version.startsWith("12.");
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private View modeCard() {
        LinearLayout card = rowCard();
        LinearLayout r = row();
        r.addView(label("生效方式"));

        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(dr(R.drawable.bg_seg));
        int sp = dp(3);
        seg.setPadding(sp, sp, sp, sp);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        slp.leftMargin = dp(10);
        seg.setLayoutParams(slp);

        modeBtnLong = segItem("仅长按", "longpress");
        modeBtnSwipe = segItem("仅上划", "swipe");
        modeBtnBoth = segItem("都生效", "both");
        seg.addView(modeBtnLong, segLp());
        seg.addView(modeBtnSwipe, segLp());
        seg.addView(modeBtnBoth, segLp());
        r.addView(seg);
        card.addView(r, matchLp());
        updateModeButtons();
        return card;
    }

    /** Segmented item: fills the container's inner height so the highlight matches it. */
    private LinearLayout.LayoutParams segLp() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
    }

    /** Portrait: let the keyboard use nearly the whole width. Landscape: keep margins. */
    private void applyKeyboardInsets() {
        if (keyboardCardView == null) {
            return;
        }
        boolean land = isLandscape();
        int side = dp(land ? 24 : 6);
        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) keyboardCardView.getLayoutParams();
        if (lp != null) {
            lp.setMargins(side, 0, side, dp(12));
        }
        int inner = dp(land ? 14 : 8);
        keyboardCardView.setPadding(inner, dp(12), inner, dp(10));
    }

    private Button segItem(String text, final String value) {
        Button b = plainButton(text, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mode = value;
                updateModeButtons();
            }
        });
        b.setTextSize(12);
        return b;
    }

    private void updateModeButtons() {
        styleSeg(modeBtnLong, "longpress".equals(mode));
        styleSeg(modeBtnSwipe, "swipe".equals(mode));
        styleSeg(modeBtnBoth, "both".equals(mode));
    }

    private void styleSeg(Button b, boolean on) {
        if (b == null) {
            return;
        }
        if (on) {
            b.setBackground(dr(R.drawable.bg_seg_active));
            b.setTextColor(c(R.color.blue_text));
            b.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            b.setBackground(null);
            b.setTextColor(c(R.color.text_secondary));
            b.setTypeface(Typeface.DEFAULT);
        }
    }

    private View actionCard() {
        LinearLayout card = rowCard();
        LinearLayout r = row();
        r.addView(label("应用修改"));
        r.addView(primaryButton("保存", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        }), weightH(40, dp(6)));
        r.addView(spacerW(dp(6)));
        r.addView(primaryButton("重启搜狗生效", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doRestart();
            }
        }), weightH(40, 0, 1.45f));
        card.addView(r, matchLp());
        return card;
    }

    private View rootCard() {
        LinearLayout card = rowCard();
        LinearLayout head = row();
        head.addView(label("root 权限"));

        Button refresh = plainButton("↻", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkRoot();
            }
        });
        refresh.setTextSize(15);
        refresh.setBackground(dr(R.drawable.bg_btn_ghost));
        refresh.setTextColor(c(R.color.text_primary));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(40), dp(34));
        rlp.leftMargin = dp(10);
        head.addView(refresh, rlp);

        rootStatus = new TextView(this);
        rootStatus.setText("检测中…");
        rootStatus.setTextSize(16);
        rootStatus.setTypeface(Typeface.DEFAULT_BOLD);
        rootStatus.setGravity(Gravity.END);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp.leftMargin = dp(10);
        head.addView(rootStatus, slp);
        card.addView(head, matchLp());
        return card;
    }

    private View navCard() {
        LinearLayout card = rowCard();
        LinearLayout r = row();
        r.addView(label("快捷入口"));
        r.addView(softButton("输入法设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            }
        }), weightH(40, dp(6)));
        r.addView(spacerW(dp(6)));
        r.addView(softButton("强行停止", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + SOGOU)));
            }
        }), weightH(40, 0));
        card.addView(r, matchLp());
        return card;
    }

    /** White card whose single row holds one whole function. */
    private LinearLayout rowCard() {
        LinearLayout c = card();
        c.setPadding(dp(14), dp(8), dp(14), dp(8));
        return c;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(c(R.color.text_primary));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Key cell metrics differ between the standard and the "大键" layout. */
    private void applyKeyMetrics() {
        keyFieldH = dp(bigKey ? 46 : 34);
        keyPadV = dp(bigKey ? 7 : 3);
        keyRowPad = dp(bigKey ? 7 : 1);
        keyMargin = dp(bigKey ? 2 : 1);
    }

    private LinearLayout.LayoutParams matchLp() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** White 16dp card with a soft shadow; 16dp side margins by default. */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(dr(R.drawable.bg_card));
        c.setElevation(dp(2));
        c.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        int p = dp(14);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), 0, dp(16), dp(12));
        c.setLayoutParams(lp);
        return c;
    }

    /** Small accent icon tile + bold title (+ optional secondary line). */
    private View sectionHeader(String title, String sub, View... actions) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), 0, 0, 0);
        row.addView(col, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(c(R.color.text_primary));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(t);

        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(11);
            s.setTextColor(c(R.color.text_secondary));
            s.setPadding(0, dp(2), 0, 0);
            col.addView(s);
        }
        for (View action : actions) {
            if (action != null) {
                row.addView(action, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));
            }
        }
        return row;
    }

    // ---------------------------------------------------------------- keyboard panel

    private void addRow(LinearLayout board, int rowNum, float sideWeight, Map<String, String> current) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, keyRowPad, 0, keyRowPad);
        if (sideWeight > 0) {
            r.addView(spacer(), new LinearLayout.LayoutParams(0, 1, sideWeight));
        }
        for (Mapping.Key k : Mapping.row(rowNum)) {
            LinearLayout.LayoutParams lp = weight1();
            lp.rightMargin = keyMargin;
            lp.leftMargin = keyMargin;
            r.addView(keyCell(k, current), lp);
        }
        if (sideWeight > 0) {
            r.addView(spacer(), new LinearLayout.LayoutParams(0, 1, sideWeight));
        }
        board.addView(r);
        if (bigKey) {
            board.addView(rowDivider());
        }
    }

    /**
     * 大键：不再跟随键盘排布，改成方块网格 —— 每个键一个大方块，好点好填。
     * 但仍按原键盘的每一行分组：同一行的键排在一起，行与行之间用分隔线隔开。
     */
    private void addGrid(LinearLayout board, Map<String, String> current) {
        final int cols = 4;
        for (int rowNum = 1; rowNum <= 4; rowNum++) {
            List<Mapping.Key> ks = Mapping.row(rowNum);
            if (ks.isEmpty()) {
                continue;
            }
            if (rowNum > 1) {
                board.addView(rowDivider());
            }
            for (int i = 0; i < ks.size(); i += cols) {
                int end = Math.min(i + cols, ks.size());
                LinearLayout line = row();
                line.setPadding(0, dp(4), 0, dp(4));
                board.addView(line, matchLp());
                for (int j = i; j < end; j++) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(104), 1f);
                    lp.leftMargin = dp(4);
                    lp.rightMargin = dp(4);
                    line.addView(keyCell(ks.get(j), current), lp);
                }
                for (int j = end; j < i + cols; j++) {
                    line.addView(spacer(), new LinearLayout.LayoutParams(0, dp(104), 1f));
                }
            }
        }
    }

    /** Thin line separating key rows in the big layout. */
    private View rowDivider() {
        View v = new View(this);
        v.setBackgroundColor(c(R.color.divider));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    private void addBottomRow(LinearLayout board, Map<String, String> current) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, keyRowPad, 0, keyRowPad);
        r.addView(spacer(), new LinearLayout.LayoutParams(0, 1, 1.5f));
        for (Mapping.Key k : Mapping.row(4)) {
            LinearLayout.LayoutParams lp = weight1();
            lp.rightMargin = keyMargin;
            lp.leftMargin = keyMargin;
            r.addView(keyCell(k, current), lp);
            r.addView(spacer(), new LinearLayout.LayoutParams(0, 1, 2f));
        }
        r.addView(spacer(), new LinearLayout.LayoutParams(0, 1, 1.5f));
        board.addView(r);
    }

    private View keyCell(Mapping.Key k, Map<String, String> current) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = dp(2);
        cell.setPadding(p, keyPadV, p, keyPadV);

        TextView letter = new TextView(this);
        letter.setText(k.letter);
        letter.setTextSize(bigKey ? 16 : 14);
        letter.setTypeface(Typeface.DEFAULT_BOLD);
        letter.setGravity(Gravity.CENTER);
        cell.addView(letter);

        KeyViews kv = new KeyViews();
        kv.cell = cell;
        kv.letter = letter;

        // Every key gets both boxes: keys that share one section across modes still keep two
        // separate config keys (see Mapping.Key#pyKey), resolved per mode by the hook.
        cell.addView(labeledField("中", k.pyKey(), current, k.pyOrig, kv));
        cell.addView(labeledField("英", k.enKey(), current, k.enOrig, kv));
        keyViews.put(k, kv);

        attachKeyReset(cell, k);
        attachKeyReset(letter, k);
        for (EditText et : kv.fields) {
            attachKeyReset(et, k);
            et.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    refreshKeyStyle(k);
                }
            });
        }
        refreshKeyStyle(k);
        return cell;
    }

    /** Highlight a key whose symbol has been customised; 中/英 labels highlight individually. */
    private void refreshKeyStyle(Mapping.Key k) {
        KeyViews kv = keyViews.get(k);
        if (kv == null) {
            return;
        }
        boolean any = false;
        for (int i = 0; i < kv.fields.size(); i++) {
            boolean on = hasText(kv.fields.get(i));
            any |= on;
            TextView lab = i < kv.labels.size() ? kv.labels.get(i) : null;
            if (lab != null) {
                lab.setTextColor(on ? c(R.color.blue_text) : c(R.color.text_secondary));
                lab.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            }
        }
        GradientDrawable keyBg = new GradientDrawable();
        keyBg.setColor(c(any ? R.color.blue_light : R.color.seg_bg));
        keyBg.setCornerRadius(dp(8));
        kv.cell.setBackground(keyBg);
        kv.letter.setTextColor(any ? c(R.color.blue_text) : c(R.color.text_primary));
    }

    private static boolean hasText(EditText et) {
        CharSequence t = et.getText();
        return t != null && t.toString().trim().length() > 0;
    }

    private void attachKeyReset(View v, final Mapping.Key k) {
        v.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View x) {
                for (String section : new LinkedHashSet<String>(k.sections())) {
                    EditText et = fields.get(section);
                    if (et != null) {
                        et.setText("");
                    }
                }
                toast("已清空「" + k.letter + "」，点「保存」后生效");
                return true;
            }
        });
    }

    private View labeledField(String label, String key, Map<String, String> current,
                              String hint, KeyViews kv) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextSize(10);
        lab.setTextColor(c(R.color.text_secondary));
        lab.setPadding(dp(1), 0, dp(1), 0);
        kv.labels.add(lab);
        line.addView(lab);
        line.addView(field(key, current, hint, kv), weight1());
        return line;
    }

    private EditText field(String key, Map<String, String> current, String hint, KeyViews kv) {
        EditText et = new EditText(this);
        et.setTextSize(bigKey ? 14 : 13);
        et.setGravity(Gravity.CENTER);
        et.setIncludeFontPadding(false);
        et.setHint(hint);
        et.setHintTextColor(c(R.color.text_tertiary));
        et.setTextColor(c(R.color.text_primary));
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1)});
        et.setBackgroundColor(0x00000000);
        et.setPadding(dp(2), 0, dp(2), 0);
        et.setMinimumWidth(0);
        // Generous touch target: these key fields are the app's most-tapped controls.
        et.setMinHeight(keyFieldH);
        et.setMinimumHeight(keyFieldH);
        String cur = current.get(key);
        if (cur != null && !cur.isEmpty()) {
            et.setText(cur);
        }
        fields.put(key, et);
        kv.fields.add(et);
        return et;
    }

    // ---------------------------------------------------------------------- actions

    private void save() {
        persist();
        toast("已保存，点「重启搜狗生效」");
    }

    /** Write the current fields + mode into the config the hook reads. */
    private void persist() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (String section : allSections()) {
            EditText et = fields.get(section);
            values.put(section, et == null || et.getText() == null ? "" : et.getText().toString().trim());
        }
        values.put("#mode", mode);
        values.put("#bigKey", bigKey ? "1" : "0");
        Prefs.save(this, values);
    }

    private void doRestart() {
        if (restartInProgress) {
            return;
        }
        restartInProgress = true;
        cancelImeRetry();
        persist();   // restarting saves first, so the keyboard picks up the edits
        toast("已保存，正在请求重启…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String[] r = restartSogou();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        restartInProgress = false;
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        boolean ok = "0".equals(r[0]);
                        toast(ok ? "已重启，正在唤起键盘…" : "重启失败：" + r[1]);
                        if (ok && activityResumed) {
                            showImeSoon();
                        }
                    }
                });
            }
        }).start();
    }

    /** Bring the keyboard back up on the test box after Sogou was restarted. */
    private void showImeSoon() {
        cancelImeRetry();
        if (testBox == null || !activityResumed) {
            return;
        }
        testBox.requestFocusFromTouch();
        imeRetry = new Runnable() {
            int tries = 0;

            @Override
            public void run() {
                if (testBox == null || !activityResumed || isFinishing() || isDestroyed()
                        || !testBox.hasFocus() || isImeVisible() || tries++ >= 15) {
                    cancelImeRetry();
                    return;
                }
                InputMethodManager imm =
                        (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(testBox, 0);
                }
                testBox.postDelayed(this, 600);
            }
        };
        testBox.postDelayed(imeRetry, 500);
    }

    private void cancelImeRetry() {
        if (testBox != null && imeRetry != null) {
            testBox.removeCallbacks(imeRetry);
        }
        imeRetry = null;
    }

    private boolean isImeVisible() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsets insets = testBox.getRootWindowInsets();
            return insets != null && insets.isVisible(WindowInsets.Type.ime());
        }
        android.graphics.Rect visible = new android.graphics.Rect();
        View root = testBox.getRootView();
        root.getWindowVisibleDisplayFrame(visible);
        return root.getHeight() - visible.bottom > dp(150);
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("重置整个键盘")
                .setMessage("清空所有自定义符号，恢复搜狗默认？")
                .setNeutralButton("取消", null)
                .setNegativeButton("仅清空", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        reset();
                        toast("已清空，点「重启搜狗生效」应用");
                    }
                })
                .setPositiveButton("清空并应用", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        reset();
                        doRestart();
                    }
                })
                .show();
    }

    /** Clears every custom symbol back to the Sogou defaults; the caller decides how to apply it. */
    private void reset() {
        for (EditText et : fields.values()) {
            et.setText("");
        }
        Prefs.clear(this);
        mode = "both";
        updateModeButtons();
    }

    // ------------------------------------------------------------------- root check

    private void checkRoot() {
        if (rootStatus == null) {
            return;
        }
        rootStatus.setTextColor(c(R.color.text_secondary));
        rootStatus.setText("检测中…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = hasRoot();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        rootStatus.setText(ok ? "已授权" : "未授权");
                        rootStatus.setTextColor(c(ok ? R.color.ok : R.color.danger));
                    }
                });
            }
        }).start();
    }

    private boolean hasRoot() {
        for (String su : SU_CANDIDATES) {
            String[] r = execCapture(su, "-c", "id");
            if ("-1".equals(r[0])) {
                continue;
            }
            return "0".equals(r[0]) && r[1].contains("uid=0");
        }
        return false;
    }

    private String[] restartSogou() {
        for (String su : SU_CANDIDATES) {
            String[] r = execCapture(su, "-c", "am force-stop " + SOGOU);
            if ("0".equals(r[0])) {
                return new String[]{"0", "已重启（用 " + su + "）"};
            }
            if ("-1".equals(r[0])) {
                continue;
            }
            return new String[]{"1", "失败（" + su + "：" + r[1].replace('\n', ' ') + "）"};
        }
        String[] r = execCapture("sh", "-c", "su -c 'am force-stop " + SOGOU + "'");
        if ("0".equals(r[0])) {
            return new String[]{"0", "已重启（sh + su）"};
        }
        return new String[]{"1", "无可用的 su，需在 Magisk 里把本应用加入 SuList/白名单"};
    }

    /** Run a command and return {exitCode, mergedOutput}. Kills it after 8s. */
    private static String[] execCapture(String... cmd) {
        Process process = null;
        Thread killer = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            process = p;
            killer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(8000);
                        p.destroy();
                    } catch (InterruptedException ignored) {
                    }
                }
            });
            killer.setDaemon(true);
            killer.start();

            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            int code = p.waitFor();
            return new String[]{String.valueOf(code), out.toString().trim()};
        } catch (Throwable t) {
            return new String[]{"-1", t.toString()};
        } finally {
            if (killer != null) {
                killer.interrupt();
            }
            if (process != null) {
                process.destroy();
            }
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Throwable t) {
            toast("打不开该设置页：" + t);
        }
    }

    // ------------------------------------------------------------------------ helpers

    private Set<String> allSections() {
        Set<String> s = new LinkedHashSet<String>();
        for (Mapping.Key k : Mapping.KEYS) {
            s.addAll(k.sections());
        }
        return s;
    }

    private Button primaryButton(String text, View.OnClickListener l) {
        Button b = plainButton(text, l);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(dr(R.drawable.bg_btn_primary));
        b.setTextColor(c(R.color.white));
        return b;
    }

    private Button softButton(String text, View.OnClickListener l) {
        Button b = plainButton(text, l);
        b.setTextSize(13);
        b.setBackground(dr(R.drawable.bg_btn_soft));
        b.setTextColor(c(R.color.blue_text));
        return b;
    }

    private Button ghostButton(String text, View.OnClickListener l) {
        Button b = plainButton(text, l);
        b.setTextSize(13);
        b.setBackground(dr(R.drawable.bg_btn_ghost));
        b.setTextColor(c(R.color.text_primary));
        return b;
    }

    /** A Button stripped of the platform min sizes / elevation so shapes show exactly. */
    private Button plainButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        b.setStateListAnimator(null);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(8), 0, dp(8), 0);
        return b;
    }

    private View spacer() {
        return new View(this);
    }

    private View spacerW(int w) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(w, 1));
        return v;
    }

    private LinearLayout.LayoutParams weight1() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams weightH(int hDp) {
        return new LinearLayout.LayoutParams(0, dp(hDp), 1f);
    }

    private LinearLayout.LayoutParams weightH(int hDp, int leftMargin) {
        return weightH(hDp, leftMargin, 1f);
    }

    private LinearLayout.LayoutParams weightH(int hDp, int leftMargin, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(hDp), weight);
        lp.leftMargin = leftMargin;
        return lp;
    }

    private int c(int colorRes) {
        return getResources().getColor(colorRes, getTheme());
    }

    private Drawable dr(int drawableRes) {
        return getResources().getDrawable(drawableRes, getTheme());
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
