package com.qoder.sogousym;

/** One bounded, in-memory paste transaction. Unknown editor state always cancels it. */
final class PasteUndo {
    static final int MAX_TEXT = 32768;

    static final class Snapshot {
        final String text;
        final int start;
        final int end;

        Snapshot(String text, int start, int end) {
            this.text = text;
            this.start = start;
            this.end = end;
        }

        boolean valid() {
            return text != null && text.length() <= MAX_TEXT && start >= 0 && end >= 0
                    && start <= text.length() && end <= text.length();
        }

        boolean same(Snapshot other) {
            return other != null && start == other.start && end == other.end
                    && text.equals(other.text);
        }
    }

    static final class Record {
        final Object editor;
        final Snapshot before;
        final Snapshot after;
        final String pasted;
        final String replaced;
        final int position;
        volatile boolean confirmed;
        boolean selectionSeen;

        Record(Object editor, Snapshot before, String pasted) {
            this.editor = editor;
            this.before = before;
            this.pasted = pasted;
            position = Math.min(before.start, before.end);
            int right = Math.max(before.start, before.end);
            replaced = before.text.substring(position, right);
            String result = before.text.substring(0, position) + pasted + before.text.substring(right);
            after = new Snapshot(result, position + pasted.length(), position + pasted.length());
        }
    }

    private Record record;

    synchronized Record arm(Object editor, Snapshot before, String pasted) {
        clear();
        if (editor == null || before == null || !before.valid() || pasted == null
                || pasted.isEmpty() || pasted.length() > MAX_TEXT) {
            return null;
        }
        Record next = new Record(editor, before, pasted);
        if (!next.after.valid() || next.before.text.equals(next.after.text)) {
            return null;
        }
        record = next;
        return next;
    }

    synchronized Record current() {
        return record;
    }

    synchronized boolean confirm(Record expected, Object editor, Snapshot actual) {
        if (record != expected || expected == null) {
            return false;
        }
        if (editor != expected.editor || actual == null || !actual.valid()) {
            clear();
        } else if (expected.after.same(actual)) {
            expected.confirmed = true;
            return true;
        } else if (!expected.before.same(actual) || expected.confirmed) {
            clear();
        }
        return false;
    }

    synchronized boolean acceptsCommit(String text) {
        if (record == null) {
            return false;
        }
        if (!record.confirmed && record.pasted.equals(text)) {
            return true;
        }
        clear();
        return false;
    }

    synchronized void selection(int start, int end, boolean composing) {
        if (record == null) {
            return;
        }
        if (composing) {
            clear();
        } else if (start == record.after.start && end == record.after.end) {
            record.selectionSeen = true;
        } else if (record.confirmed || record.selectionSeen
                || start != record.before.start || end != record.before.end) {
            clear();
        }
    }

    synchronized Record take(Object editor, Snapshot actual) {
        Record saved = record;
        clear();
        return saved != null && saved.confirmed && saved.editor == editor
                && saved.after.same(actual) ? saved : null;
    }

    synchronized void clear() {
        record = null;
    }
}
