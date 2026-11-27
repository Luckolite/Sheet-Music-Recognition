// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class CourtesyKeyTailInkTest {
    @org.junit.Test
    public void originalControls() {
        main(new String[0]);
    }

    static final int W = 220, H = 120;

    record Page(byte[] labels, byte[] gray) {}

    static void rect(Page p, int x, int y, int w, int h, int label) {
        for (int yy = y; yy < y + h; yy++)
            for (int xx = x; xx < x + w; xx++) {
                p.labels[yy * W + xx] = (byte) label;
                p.gray[yy * W + xx] = 0;
            }
    }

    static Page page() {
        var p = new Page(new byte[W * H], new byte[W * H]);
        Arrays.fill(p.gray, (byte) 255);
        for (int y = 30; y <= 82; y += 13) rect(p, 80, y, 90, 1, 4);
        rect(p, 99, 30, 2, 53, 1);
        rect(p, 105, 30, 2, 53, 1);
        rect(p, 112, 20, 4, 26, 3);
        return p;
    }

    static boolean read(Page p) {
        return CourtesyKeyTailInk.matches(p.labels, p.gray, W, H, 106, 160, 30, 13);
    }

    @org.junit.Test
    public void boundedKeyProofExcludesFollowingRepeatAndNote() {
        var p = page();
        rect(p, 166, 30, 5, 53, 1);
        rect(p, 182, 51, 8, 5, 2);
        org.junit.Assert.assertTrue(CourtesyKeyTailInk.matchesBeforeRepeat(
                p.labels, p.gray, W, H, 106, 160, 30, 13));
        org.junit.Assert.assertFalse(read(p));
        rect(p, 145, 51, 8, 5, 0);
        org.junit.Assert.assertFalse(CourtesyKeyTailInk.matchesBeforeRepeat(
                p.labels, p.gray, W, H, 106, 160, 30, 13));
    }

    @org.junit.Test
    public void keyHeaderBeforePrintedRepeatRemapsWithoutDroppingNotes() {
        var p = page();
        for (int y = 30; y <= 82; y += 13) {
            rect(p, 23, y, 57, 1, 4);
            rect(p, 170, y, 43, 1, 4);
        }
        rect(p, 166, 30, 5, 53, 1);
        rect(p, 182, 51, 8, 5, 2);
        var before = new MeasureRegion(23f / W, 98f / W, 16f / H, 99f / H);
        var header = new MeasureRegion(106f / W, 160f / W, 16f / H, 99f / H);
        var after = new MeasureRegion(172f / W, 213f / W, 16f / H, 99f / H);
        String id = "printed-repeat:0:2:3:start";
        var repeat = new ScorePlaybackDirection(2, ScorePlaybackDirection.Kind.REPEAT_START,
                new ScorePlaybackDirection.Details(0, id, "", "", "printed-repeat:0:2:3",
                        2, List.of(), Optional.empty(), ScorePlaybackDirection.AfterJumpRepeats.DEFAULT,
                        "", List.of(new ScoreExpressiveEvent.Evidence(id, 0, 170f / W, 0, 1, ""))));
        var note = new ScoreNoteEvent(2, .25f, 2, 0, 1, 51f / H, false, 0, 0, 2, 1);
        var score = new ScorePageInterpretation(List.of(before, header, after), List.of(note), 1,
                List.of(new ScoreKeyChange(1, 2)), List.of());
        var unproved = VerifiedCourtesyKeyTail.apply(score, p.labels, p.gray, W, H);
        org.junit.Assert.assertSame(score, unproved);
        var withRepeat = score.withPlaybackDirections(List.of(repeat));
        var withRest = withRepeat.withRests(List.of(new ScoreRestEvent(1, .5f, .45f, .03f, 0, 1, 3)));
        org.junit.Assert.assertSame(withRest, VerifiedCourtesyKeyTail.apply(
                withRest, p.labels, p.gray, W, H));
        var resolved = VerifiedCourtesyKeyTail.apply(withRepeat, p.labels, p.gray, W, H);
        org.junit.Assert.assertEquals(List.of(before, after), resolved.measures());
        org.junit.Assert.assertEquals(List.of(new ScoreKeyChange(1, 2)), resolved.keyChanges());
        org.junit.Assert.assertEquals(1, resolved.notes().get(0).measureIndex());
        org.junit.Assert.assertEquals(note.staffStep(), resolved.notes().get(0).staffStep());
        org.junit.Assert.assertEquals(1, resolved.playbackDirections().get(0).measureBoundary());
    }

    public static void main(String[] args) {
        int checks = 0;
        var p = page();
        if (!read(p)) throw new AssertionError("key tail");
        checks++;
        for (int label : new int[] {0, 1, 2, 5}) {
            p = page();
            rect(p, 145, 50, 8, 5, label);
            if (read(p)) throw new AssertionError("missed rest/head label=" + label);
            checks++;
        }
        p = page();
        for (int i = 0; i < p.labels.length; i++) if (p.labels[i] == 3) p.labels[i] = 0;
        if (read(p)) throw new AssertionError("missing key");
        checks++;
        p = page();
        for (int y = 30; y <= 82; y++)
            for (int x = 99; x <= 100; x++) {
                p.gray[y * W + x] = (byte) 255;
                p.labels[y * W + x] = 0;
            }
        if (read(p)) throw new AssertionError("single bar");
        checks++;
        p = page();
        byte[] old = p.gray.clone(), labels = p.labels.clone();
        read(p);
        if (!Arrays.equals(old, p.gray) || !Arrays.equals(labels, p.labels))
            throw new AssertionError("mutated input");
        checks++;
        System.out.println(
                "PASS "
                        + checks
                        + " courtesy ink controls, including missed rests under four label classes");
    }
}
