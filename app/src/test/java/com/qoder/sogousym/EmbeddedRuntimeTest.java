package com.qoder.sogousym;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EmbeddedRuntimeTest {
    @Test
    public void restartOnlyTargetsAnotherKeyboardProcessOwnedByTheHost() {
        assertTrue(EmbeddedRuntime.isKeyboardProcess(10348, 101, MainHook.PKG, 10348, 202));
        assertFalse(EmbeddedRuntime.isKeyboardProcess(10349, 101, MainHook.PKG, 10348, 202));
        assertFalse(EmbeddedRuntime.isKeyboardProcess(10348, 202, MainHook.PKG, 10348, 202));
        assertFalse(EmbeddedRuntime.isKeyboardProcess(10348, 101,
                EmbeddedRuntime.SETTINGS_PROCESS, 10348, 202));
        assertFalse(EmbeddedRuntime.isKeyboardProcess(10348, 101, "other.app", 10348, 202));
        assertFalse(EmbeddedRuntime.isKeyboardProcess(10348, 101, null, 10348, 202));
    }
}
