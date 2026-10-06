package com.qoder.sogousym;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Execute the actual shell transaction against fake system commands, including exit traps. */
public class InputMethodRecoveryTest {
    private static final String SOGOU = "com.sohu.inputmethod.sogou/.SogouIME";
    private static final String HELPER = "com.qoder.sogousym/android.inputmethodservice.InputMethodService";

    @After
    public void releaseGuard() {
        InputMethodRecovery.finish();
    }

    @Test
    public void concurrentRecoveryIsRejectedUntilTheFirstFinishes() {
        assertTrue(InputMethodRecovery.begin());
        assertFalse(InputMethodRecovery.begin());
        assertTrue(InputMethodRecovery.isRunning());
        InputMethodRecovery.finish();
        assertFalse(InputMethodRecovery.isRunning());
        assertTrue(InputMethodRecovery.begin());
    }

    @Test
    public void successRestoresDefaultAndDisablesTemporaryComponent() throws Exception {
        Result result = run("normal", SOGOU);
        assertEquals(0, result.code);
        assertEquals(SOGOU, result.current);
        assertEquals("0", result.enabled);
        assertEquals("0", result.component);
        assertTrue(result.operations.contains("set " + HELPER));
        assertTrue(result.operations.contains("set " + SOGOU));
    }

    @Test
    public void anotherDefaultIsLeftUntouchedWithoutEnablingHelper() throws Exception {
        Result result = run("normal", "other.keyboard/.Ime");
        assertNotEquals(0, result.code);
        assertEquals("other.keyboard/.Ime", result.current);
        assertFalse(result.operations.contains("set "));
        assertFalse(result.operations.contains("enable "));
    }

    @Test
    public void failedEnableCleansUpWithoutChangingDefault() throws Exception {
        Result result = run("fail_enable", SOGOU);
        assertNotEquals(0, result.code);
        assertEquals(SOGOU, result.current);
        assertEquals("0", result.enabled);
        assertEquals("0", result.component);
    }

    @Test
    public void failedReturnToSogouIsRetriedByExitTrap() throws Exception {
        Result result = run("fail_return_once", SOGOU);
        assertNotEquals(0, result.code);
        assertEquals(SOGOU, result.current);
        assertEquals("0", result.enabled);
        assertEquals("0", result.component);
    }

    @Test
    public void signalDuringSwitchStillRestoresSogou() throws Exception {
        Result result = run("signal", SOGOU);
        assertNotEquals(0, result.code);
        assertEquals(SOGOU, result.current);
        assertEquals("0", result.enabled);
        assertEquals("0", result.component);
    }

    @Test
    public void userSwitchDuringRecoveryIsPreserved() throws Exception {
        Result result = run("user_switch", SOGOU);
        assertNotEquals(0, result.code);
        assertEquals("other.keyboard/.Ime", result.current);
        assertEquals("0", result.enabled);
        assertEquals("0", result.component);
    }

    @Test
    public void unrecoverableSwitchKeepsCurrentHelperAvailableForManualRecovery() throws Exception {
        Result result = run("fail_return_always", SOGOU);
        assertNotEquals(0, result.code);
        assertEquals(HELPER, result.current);
        assertEquals("1", result.enabled);
        assertEquals("1", result.component);
    }

    private static Result run(String scenario, String original) throws Exception {
        String bash = System.getProperty("os.name").startsWith("Windows")
                ? "C:/Program Files/Git/bin/bash.exe" : "/bin/bash";
        Assume.assumeTrue("Shell transaction tests require bash", new File(bash).isFile());
        Path folder = Files.createTempDirectory("ime-recovery-test-");
        try {
            write(folder.resolve("current"), original);
            write(folder.resolve("enabled"), "0");
            write(folder.resolve("component"), "1");
            write(folder.resolve("operations"), "");
            String fixture = "scenario='" + scenario + "'\n"
                    + "settings() { cat current; }\n"
                    + "am() { echo cleanup >> operations; echo 0 > component; }\n"
                    + "ime() {\n"
                    + "  echo \"$*\" >> operations\n"
                    + "  case \"$1\" in\n"
                    + "    list) echo '" + HELPER + "' ;;\n"
                    + "    enable) [ \"$scenario\" != fail_enable ] || return 1; echo 1 > enabled ;;\n"
                    + "    disable) echo 0 > enabled ;;\n"
                    + "    set)\n"
                    + "      if [ \"$2\" = '" + SOGOU + "' ]; then\n"
                    + "        [ \"$scenario\" != fail_return_always ] || return 1\n"
                    + "        if [ \"$scenario\" = fail_return_once ] && [ ! -f failed ]; then\n"
                    + "          touch failed; return 1\n"
                    + "        fi\n"
                    + "      fi\n"
                    + "      echo \"$2\" > current ;;\n"
                    + "  esac\n"
                    + "}\n"
                    + "sleep() {\n"
                    + "  [ \"$1\" = 2 ] || return 0\n"
                    + "  case \"$scenario\" in\n"
                    + "    signal) command kill -TERM \"$$\" ;;\n"
                    + "    user_switch) echo 'other.keyboard/.Ime' > current ;;\n"
                    + "  esac\n"
                    + "}\n"
                    + "kill() { return 0; }\n";
            write(folder.resolve("transaction.sh"), fixture + InputMethodRecovery.script());
            Process process = new ProcessBuilder(bash, "transaction.sh")
                    .directory(folder.toFile()).redirectErrorStream(true)
                    .redirectOutput(folder.resolve("output").toFile()).start();
            try {
                assertTrue("Transaction did not finish", process.waitFor(10, TimeUnit.SECONDS));
                Result result = new Result();
                result.code = process.exitValue();
                result.current = read(folder.resolve("current"));
                result.enabled = read(folder.resolve("enabled"));
                result.component = read(folder.resolve("component"));
                result.operations = read(folder.resolve("operations"));
                return result;
            } finally {
                process.destroyForcibly();
            }
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(folder)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
            }
        }
    }

    private static void write(Path path, String value) throws Exception {
        Files.write(path, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
    }

    private static final class Result {
        int code;
        String current;
        String enabled;
        String component;
        String operations;
    }
}
