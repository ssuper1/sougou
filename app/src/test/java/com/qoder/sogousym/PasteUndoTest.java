package com.qoder.sogousym;

import org.junit.Test;

import static org.junit.Assert.*;

public class PasteUndoTest {
    private final PasteUndo history = new PasteUndo();
    private final Object editor = new Object();

    private static PasteUndo.Snapshot state(String text, int cursor) {
        return new PasteUndo.Snapshot(text, cursor, cursor);
    }

    @Test
    public void appendUndoPreservesExistingTextAndIsOneShot() {
        PasteUndo.Record record = history.arm(editor, state("original", 8), "paste");
        assertEquals("originalpaste", record.after.text);
        assertTrue(history.confirm(record, editor, state("originalpaste", 13)));
        assertSame(record, history.take(editor, state("originalpaste", 13)));
        assertEquals("", record.replaced);
        assertEquals(8, record.position);
        assertNull(history.take(editor, state("originalpaste", 13)));
    }

    @Test
    public void pasteInMiddlePreservesBothSidesIncludingUnicodeAndNewlines() {
        String pasted = "\u4f60\u597d\n\uD83D\uDE00 ";
        PasteUndo.Record record = history.arm(editor, state("prefixsuffix", 6), pasted);
        assertEquals("prefix" + pasted + "suffix", record.after.text);
        assertTrue(history.confirm(record, editor, record.after));
        assertEquals(pasted.length(), record.after.start - record.position);
        assertSame(record, history.take(editor, record.after));
    }

    @Test
    public void replacementRemembersOriginalTextAndReversedSelection() {
        PasteUndo.Snapshot before = new PasteUndo.Snapshot("leftOLDright", 7, 4);
        PasteUndo.Record record = history.arm(editor, before, "NEW");
        assertEquals("leftNEWright", record.after.text);
        assertEquals("OLD", record.replaced);
        assertEquals(4, record.position);
        assertTrue(history.confirm(record, editor, record.after));
        assertSame(before, history.take(editor, record.after).before);
    }

    @Test
    public void asynchronousPasteWaitsForActualEditorChange() {
        PasteUndo.Record record = history.arm(editor, state("a", 1), "b");
        assertFalse(history.confirm(record, editor, record.before));
        assertSame(record, history.current());
        assertTrue(history.acceptsCommit("b"));
        history.selection(2, 2, false);
        assertTrue(history.confirm(record, editor, state("ab", 2)));
        assertSame(record, history.take(editor, state("ab", 2)));
    }

    @Test
    public void ordinaryCommitDeletionAndLifecycleInvalidateHistory() {
        PasteUndo.Record record = history.arm(editor, state("a", 1), "b");
        history.confirm(record, editor, record.after);
        assertFalse(history.acceptsCommit("c"));
        assertNull(history.current());
        record = history.arm(editor, state("a", 1), "b");
        history.confirm(record, editor, record.after);
        history.clear();
        assertNull(history.take(editor, state("ab", 2)));
    }

    @Test
    public void cursorMoveAndCompositionInvalidateEvenIfCursorMovesBack() {
        PasteUndo.Record record = history.arm(editor, state("a", 1), "b");
        history.confirm(record, editor, record.after);
        history.selection(1, 1, false);
        history.selection(2, 2, false);
        assertNull(history.take(editor, record.after));
        record = history.arm(editor, state("a", 1), "b");
        history.selection(2, 2, true);
        assertNull(history.current());
    }

    @Test
    public void wrongEditorChangedTextAndMissingReadsNeverAllowUndo() {
        for (PasteUndo.Snapshot actual : new PasteUndo.Snapshot[]{state("ac", 2),
                state("ab!", 2), state("ab", 1), null}) {
            PasteUndo.Record record = history.arm(editor, state("a", 1), "b");
            history.confirm(record, editor, record.after);
            assertNull(history.take(editor, actual));
        }
        PasteUndo.Record record = history.arm(editor, state("a", 1), "b");
        history.confirm(record, editor, record.after);
        assertNull(history.take(new Object(), record.after));
    }

    @Test
    public void latestPasteReplacesHistoryAndOldCallbacksCannotAffectIt() {
        PasteUndo.Record first = history.arm(editor, state("a", 1), "b");
        history.confirm(first, editor, first.after);
        PasteUndo.Record second = history.arm(editor, first.after, "c");
        assertFalse(history.confirm(first, editor, state("wrong", 0)));
        assertSame(second, history.current());
        assertTrue(history.confirm(second, editor, state("abc", 3)));
        assertEquals(2, history.take(editor, state("abc", 3)).position);
    }

    @Test
    public void invalidOversizedAndNoChangePastesAreRejected() {
        assertNull(history.arm(null, state("", 0), "a"));
        assertNull(history.arm(editor, null, "a"));
        assertNull(history.arm(editor, state("a", 2), "b"));
        assertNull(history.arm(editor, state("a", -1), "b"));
        assertNull(history.arm(editor, state("a", 1), ""));
        assertNull(history.arm(editor, new PasteUndo.Snapshot("a", 0, 1), "a"));
        assertNull(history.arm(editor, state("a".repeat(PasteUndo.MAX_TEXT), 0), "b"));
        assertNull(history.arm(editor, state("", 0), "a".repeat(PasteUndo.MAX_TEXT + 1)));
    }

    @Test
    public void arbitraryCandidatesAndOtherClipboardUtilitiesAreNotPasteSources() {
        assertTrue(PasteUndoHook.clipboardSource(new StackTraceElement[]{
                new StackTraceElement("com.sogou.clipboard.spage.ClipboardPage", "onItemClick", "", 1)}));
        assertTrue(PasteUndoHook.clipboardSource(new StackTraceElement[]{
                new StackTraceElement("com.sogou.clipboard.repository.utils.q", "a", "", 1)}));
        assertFalse(PasteUndoHook.clipboardSource(new StackTraceElement[]{
                new StackTraceElement("com.sogou.clipboard.spage.ClipboardPage", "onKeyDown", "", 1),
                new StackTraceElement("com.sogou.CandidateView", "onItemClick", "", 1)}));
    }

    @Test
    public void clipboardCandidateSlotsMatchCompactAndExpandedLayouts() {
        assertEquals(0, PasteUndoHook.candidateIndex(1, 1, 0, 1));
        assertEquals(-1, PasteUndoHook.candidateIndex(0, 1, 0, 1));
        assertEquals(-1, PasteUndoHook.candidateIndex(2, 1, 0, 1));
        assertEquals(-1, PasteUndoHook.candidateIndex(1, 1, 5, 1));
        assertEquals(0, PasteUndoHook.candidateIndex(0, 3, 0, 1));
        assertEquals(-1, PasteUndoHook.candidateIndex(-1, 3, 0, 1));
    }
}
