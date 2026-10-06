package com.qoder.sogousym;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;

/** Runs on a connected phone without adding a testing library to the module APK. */
public class LifecycleInstrumentation extends Instrumentation {
    private MainActivity activity;
    private int failures;
    private int current;
    private Bundle arguments;
    private final StringBuilder results = new StringBuilder();

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments == null ? new Bundle() : arguments;
        start();
    }

    @Override
    public void onStart() {
        Map<String, String> original = null;
        try {
            original = Prefs.load(getTargetContext());
            String config = arguments.getString("config");
            if (config != null) {
                org.json.JSONObject decoded = new org.json.JSONObject(new String(
                        android.util.Base64.decode(config, android.util.Base64.DEFAULT),
                        java.nio.charset.StandardCharsets.UTF_8));
                original = new LinkedHashMap<>();
                Iterator<String> keys = decoded.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    original.put(key, decoded.getString(key));
                }
            }
            Map<String, String> testing = new LinkedHashMap<>(original);
            testing.put("#mode", arguments.getString("mode", testing.getOrDefault("#mode", "both")));
            Prefs.save(getTargetContext(), testing);
            if ("true".equals(arguments.getString("configOnly"))) {
                return;
            }
            boolean releaseSmoke = "true".equals(arguments.getString("releaseSmoke"));
            Intent intent = new Intent(getTargetContext(), MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = (MainActivity) startActivitySync(intent);
            waitForIdleSync();
            Map<String, String> expected = new LinkedHashMap<>(testing);
            if (releaseSmoke) {
                test("releasePunctuationAndNormalTyping", () -> {
                    android.widget.EditText box = firstEditText(activity.getWindow().getDecorView());
                    check(box != null, "Test input missing");
                    onUi(() -> box.requestFocusFromTouch());
                    await(() -> {
                        android.view.WindowInsets insets = box.getRootWindowInsets();
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                                && insets != null && insets.isVisible(android.view.WindowInsets.Type.ime())) {
                            return true;
                        }
                        ((android.view.inputmethod.InputMethodManager) activity.getSystemService(
                                android.content.Context.INPUT_METHOD_SERVICE)).showSoftInput(box, 0);
                        return false;
                    }, 12000);
                    SystemClock.sleep(1500);
                    AtomicReference<int[]> boxCenter = new AtomicReference<>();
                    onUi(() -> {
                        int[] position = new int[2];
                        box.getLocationOnScreen(position);
                        position[0] += box.getWidth() / 2;
                        position[1] += box.getHeight() / 2;
                        boxCenter.set(position);
                    });
                    tap(boxCenter.get()[0], boxCenter.get()[1]);
                    await(() -> box.hasFocus() && activity.hasWindowFocus()
                            && ((android.view.inputmethod.InputMethodManager) activity.getSystemService(
                                    android.content.Context.INPUT_METHOD_SERVICE)).isActive(box), 3000);
                    onUi(() -> box.setText(""));
                    String cn = testing.getOrDefault("Key_UDSymbol1_Qwerty", "\uFF0C")
                            + testing.getOrDefault("Key_UDSymbol2_Qwerty", "\u3002");
                    String en = testing.getOrDefault("Key_CommaEn_T", ",")
                            + testing.getOrDefault("Key_PeriodEn_T", ".");
                    tap(350, 2260);
                    tap(725, 2260);
                    await(() -> cn.contentEquals(box.getText()) || en.contentEquals(box.getText()), 3000);
                    AtomicReference<String> initial = new AtomicReference<>();
                    onUi(() -> initial.set(box.getText().toString()));
                    if (!cn.equals(en) && en.equals(initial.get())) {
                        tap(835, 2260);
                        SystemClock.sleep(1500);
                    }
                    onUi(() -> box.setText(""));
                    shell("screencap -p /sdcard/sogousym-release-cn.png");
                    tap(350, 2260);
                    tap(725, 2260);
                    await(() -> cn.contentEquals(box.getText()), 3000);
                    tap(835, 2260);
                    SystemClock.sleep(1500);
                    shell("screencap -p /sdcard/sogousym-release-en.png");
                    tap(350, 2260);
                    tap(725, 2260);
                    await(() -> (cn + en).contentEquals(box.getText()), 3000);
                    tap(220, 1945);
                    SystemClock.sleep(300);
                    String text = cn + en + "s";
                    onUi(() -> check(text.contentEquals(box.getText()), "Release output: " + box.getText()));
                    tap(835, 2260);
                    SystemClock.sleep(1500);
                    if ("true".equals(arguments.getString("latency"))) {
                        int rounds = Integer.parseInt(arguments.getString("stressRounds", "1"));
                        for (int round = 0; round < rounds; round++) {
                            onUi(() -> box.setText(""));
                            String comma = testing.getOrDefault("Key_UDSymbol1_Qwerty", "\uFF0C");
                            StringBuilder expectedText = new StringBuilder();
                            long total = 0;
                            long maximum = 0;
                            for (int i = 0; i < 20; i++) {
                                expectedText.append(comma);
                                String expectedCommit = expectedText.toString();
                                long begin = SystemClock.uptimeMillis();
                                tap(350, 2260);
                                await(() -> expectedCommit.contentEquals(box.getText()), 1500);
                                long elapsed = SystemClock.uptimeMillis() - begin;
                                total += elapsed;
                                maximum = Math.max(maximum, elapsed);
                            }
                            results.append("Comma latency including input injection/polling: count=20 mean=")
                                    .append(total / 20).append("ms max=").append(maximum).append("ms\n");
                            SystemClock.sleep(20000);
                            expectedText.append(comma);
                            String expectedCommit = expectedText.toString();
                            tap(350, 2260);
                            await(() -> expectedCommit.contentEquals(box.getText()), 1500);
                            results.append("PASS comma after 20s idle\n");
                        }
                    }
                });
                return;
            }

            test("restartCompletesAndShowsKeyboard", () -> {
                onUi(() -> {
                    View box = (View) get("testBox");
                    box.requestFocusFromTouch();
                    call("doRestart");
                    check((Boolean) get("restartInProgress"), "Restart not guarded");
                    call("doRestart");
                });
                await(() -> !(Boolean) get("restartInProgress"), 12000);
                await(() -> (Boolean) call("isImeVisible") && get("imeRetry") == null, 12000);
                if ("true".equals(arguments.getString("punctuation"))) {
                    if ("true".equals(arguments.getString("startEnglish"))) {
                        tap(835, 2260);
                        SystemClock.sleep(300);
                    }
                    onUi(() -> ((android.widget.EditText) get("testBox")).setText(""));
                    shell("screencap -p /sdcard/sogousym-punct-cn.png");
                    tap(350, 2260);
                    tap(725, 2260);
                    SystemClock.sleep(500);
                    String cn = testing.getOrDefault("Key_UDSymbol1_Qwerty", "\uFF0C")
                            + testing.getOrDefault("Key_UDSymbol2_Qwerty", "\u3002");
                    onUi(() -> check(cn.contentEquals(
                            ((android.widget.EditText) get("testBox")).getText()), "Chinese punctuation mismatch"));
                    tap(835, 2260);
                    SystemClock.sleep(500);
                    shell("screencap -p /sdcard/sogousym-punct-en.png");
                    tap(350, 2260);
                    tap(725, 2260);
                    SystemClock.sleep(500);
                    String all = cn + testing.getOrDefault("Key_CommaEn_T", ",")
                            + testing.getOrDefault("Key_PeriodEn_T", ".");
                    onUi(() -> check(all.contentEquals(
                            ((android.widget.EditText) get("testBox")).getText()), "English punctuation mismatch"));
                    tap(835, 2260);
                    results.append("PASS punctuation CN/EN in mode ").append(testing.get("#mode")).append('\n');
                    if ("true".equals(arguments.getString("gesture"))) {
                        long down = SystemClock.uptimeMillis();
                        touch(down, android.view.MotionEvent.ACTION_DOWN, 220, 1945);
                        try {
                            SystemClock.sleep(900);
                            shell("screencap -p /sdcard/sogousym-popup.png");
                        } finally {
                            touch(down, android.view.MotionEvent.ACTION_UP, 220, 1945);
                        }
                        SystemClock.sleep(300);
                        String symbol = "swipe".equals(testing.get("#mode")) ? "\uFF01"
                                : testing.getOrDefault("Key_S_PY", "\uFF01");
                        onUi(() -> check((all + symbol).contentEquals(
                                ((android.widget.EditText) get("testBox")).getText()), "Letter long-press mismatch"));
                        onUi(() -> ((android.widget.EditText) get("testBox")).setText(all));
                        results.append("PASS letter long-press in mode ").append(testing.get("#mode")).append('\n');
                    }
                    if ("true".equals(arguments.getString("restorePanelLock"))) {
                        tap(84, 2260);
                        SystemClock.sleep(300);
                        tap(985, 2120);
                        shell("screencap -p /sdcard/sogousym-panel-restored.png");
                        tap(985, 1615);
                    }
                    if ("true".equals(arguments.getString("panel"))) {
                        tap(84, 2260);
                        SystemClock.sleep(500);
                        tap(300, 2260);
                        SystemClock.sleep(300);
                        shell("screencap -p /sdcard/sogousym-punct-panel.png");
                        tap(985, 2120);
                        tap(110, 1615);
                        tap(330, 1615);
                        SystemClock.sleep(300);
                        shell("screencap -p /sdcard/sogousym-punct-panel-after.png");
                        String cnPanel = all + "\uFF0C\u3002";
                        onUi(() -> check(cnPanel.contentEquals(
                                ((android.widget.EditText) get("testBox")).getText()), "Chinese panel result: "
                                + ((android.widget.EditText) get("testBox")).getText()));
                        tap(985, 1615);
                        tap(835, 2260);
                        SystemClock.sleep(300);
                        tap(84, 2260);
                        SystemClock.sleep(300);
                        shell("screencap -p /sdcard/sogousym-punct-panel-en.png");
                        tap(350, 2260);
                        tap(870, 2260);
                        SystemClock.sleep(300);
                        onUi(() -> check((cnPanel + ",.").contentEquals(
                                ((android.widget.EditText) get("testBox")).getText()), "English panel result: "
                                + ((android.widget.EditText) get("testBox")).getText()));
                        tap(84, 2260);
                        tap(835, 2260);
                        results.append("PASS original punctuation in CN/EN panels\n");
                    }
                }
            });

            test("visibleKeyboardStopsRetry", () -> {
                onUi(() -> call("showImeSoon"));
                await(() -> (Boolean) call("isImeVisible") && get("imeRetry") == null, 12000);
            });

            test("retryDoesNotStealChangedFocus", () -> {
                AtomicReference<View> other = new AtomicReference<>();
                onUi(() -> {
                    call("showImeSoon");
                    Map<?, ?> fields = (Map<?, ?>) get("fields");
                    View field = (View) fields.values().iterator().next();
                    other.set(field);
                    field.requestFocusFromTouch();
                });
                SystemClock.sleep(1600);
                onUi(() -> {
                    check(other.get().hasFocus(), "Retry stole focus from a config field");
                    check(get("imeRetry") == null, "Unneeded retry remained queued");
                });
            });

            test("leavingActivityCancelsRetry", () -> {
                onUi(() -> {
                    call("showImeSoon");
                    check(get("imeRetry") != null, "Retry was not scheduled");
                });
                try (ParcelFileDescriptor ignored =
                             getUiAutomation().executeShellCommand("input keyevent KEYCODE_HOME")) {
                    await(() -> !(Boolean) get("activityResumed"), 3000);
                }
                onUi(() -> check(get("imeRetry") == null, "Retry survived onPause"));
            });

            test("configurationIsPreserved", () -> {
                check(expected.equals(Prefs.load(getTargetContext())), "Configuration changed during tests");
            });
        } catch (Throwable t) {
            failures++;
            results.append("Setup failed: ").append(t).append('\n');
        } finally {
            if (original != null) {
                Prefs.save(getTargetContext(), original);
            }
            Bundle report = new Bundle();
            report.putString("stream", "\n" + results + "Tests: " + current + ", failures: " + failures + "\n");
            finish(failures == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, report);
        }
    }

    private interface Action { void run() throws Exception; }
    private interface Condition { boolean get() throws Exception; }

    private android.widget.EditText firstEditText(View view) {
        if (view instanceof android.widget.EditText) {
            return (android.widget.EditText) view;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.EditText found = firstEditText(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void shell(String command) throws Exception {
        try (java.io.InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(
                getUiAutomation().executeShellCommand(command))) {
            byte[] buffer = new byte[1024];
            while (output.read(buffer) != -1) { }
        }
    }

    private void tap(int x, int y) {
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_UP}) {
            touch(now, action, x, y);
        }
    }

    private void touch(long down, int action, int x, int y) {
        android.view.MotionEvent event = android.view.MotionEvent.obtain(
                down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try {
            getUiAutomation().injectInputEvent(event, true);
        } finally {
            event.recycle();
        }
    }

    private void test(String name, Action action) {
        Bundle status = new Bundle();
        status.putString("class", getClass().getName());
        status.putString("test", name);
        status.putInt("numtests", "true".equals(arguments.getString("releaseSmoke")) ? 1 : 5);
        status.putInt("current", ++current);
        sendStatus(1, status);
        try {
            action.run();
            status.putString("stream", ".");
            sendStatus(0, status);
            results.append("PASS ").append(name).append('\n');
        } catch (Throwable t) {
            failures++;
            status.putString("stack", android.util.Log.getStackTraceString(t));
            status.putString("stream", "FAIL " + name + ": " + t + "\n");
            sendStatus(-2, status);
            results.append("FAIL ").append(name).append(": ").append(t).append('\n');
        }
    }

    private Object get(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(activity);
    }

    private Object call(String name) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(activity);
    }

    private void onUi(Action action) throws Exception {
        AtomicReference<Exception> failure = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                action.run();
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    private void await(Condition condition, long timeoutMs) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            onUi(() -> ready.set(condition.get()));
            if (ready.get()) {
                return;
            }
            SystemClock.sleep(100);
        }
        throw new IllegalStateException("Condition timed out after " + timeoutMs + "ms");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
