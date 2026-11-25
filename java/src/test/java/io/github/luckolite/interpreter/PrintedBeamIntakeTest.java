// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;

/** Original ordinary/cue/grace rasters and barline/rival boundary controls; full staff scale stays fixed. */
public final class PrintedBeamIntakeTest {
    @org.junit.Test
    public void originalRawGlyphAndRoleControls() {
        main(new String[0]);
    }

    static int checks;

    static void check(boolean x, String m) {
        checks++;
        if (!x) throw new AssertionError(m);
    }

    static void pixel(byte[] a, int w, int x, int y) {
        if (x >= 0 && x < w && y >= 0 && y < a.length / w) a[y * w + x] = 0;
    }

    static byte[] raw(float scale, boolean body, boolean rail, boolean down) {
        int w = 120, h = 128;
        byte[] a = new byte[w * h];
        Arrays.fill(a, (byte) 255);
        for (int y : new int[] {40, 52, 64, 76, 88})
            for (int x = 4; x < 116; x++) pixel(a, w, x, y);
        float g = 12 * scale;
        for (int cx : new int[] {20, 88}) {
            if (body)
                for (int y = 80; y <= 96; y++)
                    for (int x = cx - 10; x <= cx + 10; x++)
                        if ((x - cx) * (x - cx) / (.6 * g * .6 * g)
                                        + (y - 88) * (y - 88) / (.4 * g * .4 * g)
                                <= 1) pixel(a, w, x, y);
            int sx = Math.round(cx + .6f * g);
            for (int y = 24; y <= 88; y++) pixel(a, w, sx, y);
            if (down) {
                int dx = Math.round(cx - .6f * g);
                for (int y = 88; y <= 117; y++) pixel(a, w, dx, y);
            }
        }
        if (rail)
            for (int y = 24; y < 24 + Math.max(3, Math.round(g * .45f)); y++)
                for (int x = Math.round(20 + .6f * g); x <= Math.round(88 + .6f * g); x++)
                    pixel(a, w, x, y);
        return a;
    }

    static ScorePageInterpretation score(int kind) {
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int x : new int[] {20, 88}) {
            var n = new ScoreNoteEvent(0, x / 120f, 0, 0, 1, 88 / 128f, false, 0, 1);
            if (kind == 1) n = n.withVisualSize(ScoreNoteEvent.VisualSize.CUE);
            if (kind == 2) n = n.withArticulations(NoteOrnament.GRACE);
            notes.add(n);
        }
        return new ScorePageInterpretation(List.of(new MeasureRegion(0, 1, 0, 1)), notes);
    }

    public static void main(String[] args) {
        var staffs = List.of(new PlayingTechniqueDetector.Staff(40, 88, 12, 0, 1));
        for (int kind = 0; kind < 3; kind++) {
            float scale = kind == 0 ? 1 : kind == 1 ? .7f : .65f;
            var s = score(kind);
            byte[] r = raw(scale, true, true, false);
            var c =
                    PrintedBeamSource.capture(
                            s, new byte[r.length], r, 120, 128, staffs, 0, 8_000_000, 256_000_000);
            check(
                    c.statistics().refusal().isEmpty(),
                    "glyph refusal " + kind + ":" + c.statistics());
            check(
                    c.statistics().bound() == 2
                            && c.statistics().bodies() == 2
                            && c.statistics().shaftOwners() == 2,
                    "glyph ownership " + kind);
            check(c.source().groups().size() == 1, "lost raw glyph beam " + kind);
            check(
                    c.source().groups().get(0).bridges().equals(List.of(1)),
                    "glyph changed beam topology");
            check(c.source().noteOrigins().size() == 2, "source count");
            for (byte label : new byte[] {4, 2, 1}) {
                byte[] wrong = new byte[r.length];
                Arrays.fill(wrong, label);
                check(
                        PrintedBeamSource.capture(
                                        s, wrong, r, 120, 128, staffs, 0, 8_000_000, 256_000_000)
                                .source()
                                .equals(c.source()),
                        "semantic label affected raw glyph proof");
            }
            var applied = PrintedBeamSource.apply(s, new byte[r.length], r, 120, 128, staffs, 0);
            check(
                    applied.withBeamSource(s.beamSource()).equals(s),
                    "glyph altered scalar note fields");
            var noRail = raw(scale, true, false, false);
            var unknown =
                    PrintedBeamSource.capture(
                            s,
                            new byte[noRail.length],
                            noRail,
                            120,
                            128,
                            staffs,
                            0,
                            8_000_000,
                            256_000_000);
            check(
                    unknown.source().groups().isEmpty()
                            && unknown.source().detachedColumns().isEmpty(),
                    "missing rail became known flag");
        }
        byte[] nearBar = raw(1, false, true, false);
        var barred =
                PrintedBeamSource.capture(
                        score(0),
                        new byte[nearBar.length],
                        nearBar,
                        120,
                        128,
                        staffs,
                        0,
                        8_000_000,
                        256_000_000);
        check(
                barred.statistics().bodies() == 0 && barred.source().groups().isEmpty(),
                "non-head barline supplied body proof");
        byte[] rival = raw(1, true, true, true);
        var rivals =
                PrintedBeamSource.capture(
                        score(0),
                        new byte[rival.length],
                        rival,
                        120,
                        128,
                        staffs,
                        0,
                        8_000_000,
                        256_000_000);
        check(
                rivals.statistics().shaftOwners() == 0 && rivals.source().groups().isEmpty(),
                "opposing raw shaft rivals silently chose one");
        byte[] ordinary = raw(1, true, true, false);
        var mismatch =
                PrintedBeamSource.capture(
                        score(1),
                        new byte[ordinary.length],
                        ordinary,
                        120,
                        128,
                        staffs,
                        0,
                        8_000_000,
                        256_000_000);
        check(mismatch.source().groups().isEmpty(), "cue sizing ignored actual glyph geometry");
        System.out.println(
                "PASS " + checks + " original ordinary/cue/grace/raw-body/rival assertions");
    }

    @org.junit.Test
    public void supplementsOnlyUnclaimedAndUnambiguousBeamOrigins() {
        var claimed = group("proved", 40, 41);
        var existing = new ScoreBeamSource(
                List.of(claimed), List.of(column(53)),
                List.of(Optional.of(anchor(40)), Optional.of(anchor(41)), Optional.empty(),
                        Optional.empty(), Optional.of(anchor(48)), Optional.of(anchor(48)),
                        Optional.of(anchor(53)), Optional.empty(), Optional.empty(), Optional.empty()));
        var recovered = ScoreBeamSource.capture(3, 10,
                List.of(group("overlap", 1, 8), group("new", 2, 3),
                        group("ambiguous", 4, 7), group("detached-rival", 6, 9)),
                List.of());
        var merged = PrintedBeamSource.supplement(existing, recovered, 3);
        org.junit.Assert.assertEquals(List.of(claimed, group("new", 54, 55)), merged.groups());
        org.junit.Assert.assertEquals(existing.detachedColumns(), merged.detachedColumns());
        org.junit.Assert.assertEquals(Optional.of(anchor(54)), merged.noteOrigins().get(2));
        org.junit.Assert.assertEquals(Optional.of(anchor(55)), merged.noteOrigins().get(3));
        org.junit.Assert.assertEquals(Optional.empty(), merged.noteOrigins().get(8));
        org.junit.Assert.assertSame(merged, PrintedBeamSource.supplement(merged, recovered, 3));
    }

    @org.junit.Test
    public void supplementalBeamCaptureRequiresPrintedInk() {
        var score = score(0);
        var staffs = List.of(new PlayingTechniqueDetector.Staff(40, 88, 12, 0, 1));
        var printed = raw(1, true, true, false);
        var erased = raw(1, true, false, false);
        org.junit.Assert.assertSame(score,
                PrintedBeamSource.recoverDensePage(score, new byte[erased.length],
                        erased, 120, 128, staffs, 3));
        var recovered = PrintedBeamSource.recoverDensePage(score, new byte[printed.length],
                printed, 120, 128, staffs, 3);
        org.junit.Assert.assertEquals(1, recovered.beamSource().groups().size());
        org.junit.Assert.assertEquals(score.notes(), recovered.notes());
    }

    private static ScoreBeamSource.Anchor anchor(int index) {
        return new ScoreBeamSource.Anchor(3, index);
    }

    private static ScoreBeamSource.Column column(int index) {
        return new ScoreBeamSource.Column(List.of(anchor(index)));
    }

    private static ScoreBeamSource.Group group(String id, int a, int b) {
        return new ScoreBeamSource.Group(id, List.of(column(a), column(b)), List.of(1), List.of());
    }
}
