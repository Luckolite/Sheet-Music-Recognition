// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ObservedLedgerSlurOriginalsTest {
    private static final class Staff {
        private static final int WIDTH = 240, HEIGHT = 300, GAP = 16;
        private static final int RULE = 148;
        final byte[] gray = new byte[WIDTH * HEIGHT];
        final List<PrintedSlurOwners.GeometryHead> heads =
                List.of(head(0, 77, RULE), head(1, 109, RULE - 24), head(2, 141, RULE));
        final List<ScoreNoteEvent> notes = List.of(note(0, 14), note(1, 17), note(2, 14));
        final StaffPitchTrack track =
                StaffPitchTrack.fromVerifiedSamples(
                        List.of(new float[] {0, 260, GAP}, new float[] {WIDTH - 1, 260, GAP}));
        final ScoreSlurPages.Span span =
                new ScoreSlurPages.Span(
                        "ribbon",
                        new ScoreSlurPages.Anchor(0, 0),
                        new ScoreSlurPages.Anchor(0, 2),
                        ScoreSlurSpan.Placement.ABOVE,
                        ScoreSlurSpan.LineType.SOLID,
                        List.of(new ScoreSlurSpan.ArcEvidence(0, .37f, .49f, .56f, .52f)));

        Staff() {
            Arrays.fill(gray, (byte) 255);
            for (var head : heads) {
                int cx = (int) head.x(), cy = (int) head.y();
                for (int y = cy - 7; y <= cy + 7; y++)
                    for (int x = cx - 10; x <= cx + 10; x++)
                        if (Math.pow((x - cx) / 10., 2) + Math.pow((y - cy) / 7., 2) <= 1)
                            gray[y * WIDTH + x] = 0;
                paint(cx - 10, cx - 8, cy, 190, 25);
            }
            paint(70, 150, RULE - 1, RULE + 1, 35);
        }

        private static PrintedSlurOwners.GeometryHead head(int owner, int x, int y) {
            return new PrintedSlurOwners.GeometryHead(owner, x, y, 0, 1);
        }

        private ScoreNoteEvent note(int owner, int step) {
            var head = heads.get(owner);
            return new ScoreNoteEvent(
                            0,
                            head.x() / WIDTH,
                            step,
                            0,
                            1,
                            head.y() / HEIGHT,
                            false,
                            0,
                            1,
                            ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                            0)
                    .withStemDirection(-1);
        }

        void paint(int left, int right, int top, int bottom, int tone) {
            for (int y = top; y <= bottom; y++)
                for (int x = left; x <= right; x++) gray[y * WIDTH + x] = (byte) tone;
        }

        PrintedSlurContours.Bow ribbon(boolean curved) {
            var points = new ArrayList<PrintedSlurContours.Point>();
            int left = 89, right = 134;
            for (int x = left; x <= right; x++) {
                float t = (x - left) / (float) (right - left);
                float y = curved ? RULE - 40 * t * (1 - t) : RULE + (x >= 130 ? 9 : 0);
                if (curved) paint(x, x, Math.round(y), Math.round(y), 30);
                points.add(new PrintedSlurContours.Point(x, y));
            }
            return new PrintedSlurContours.Bow(points, true, .3f, .1f);
        }

        boolean owns(PrintedSlurContours.Bow bow) {
            byte[] before = gray.clone();
            boolean owned =
                    new ObservedStemBeam(notes, gray, WIDTH, HEIGHT)
                            .ownsLedger(bow, span, heads, track);
            assertArrayEquals(before, gray);
            return owned;
        }
    }

    @Test
    public void horizontalLedgerWithAnOwnedDownstrokeRejectsAnAttachedRibbon() {
        var staff = new Staff();
        assertTrue(staff.owns(staff.ribbon(false)));
    }

    @Test
    public void separatePrintedArcSurvivesTheSameThreeHeadsAndLedger() {
        var staff = new Staff();
        assertFalse(staff.owns(staff.ribbon(true)));
    }

    @Test
    public void horizontalInkWithoutItsOwnDownstrokeDoesNotClaimTheRibbon() {
        var staff = new Staff();
        staff.paint(96, 103, 130, 144, 255);
        assertFalse(staff.owns(staff.ribbon(false)));
    }

    @Test
    public void oneSidedLedgerInkDoesNotClaimTheRibbon() {
        var staff = new Staff();
        staff.paint(102, 117, 146, 150, 255);
        assertFalse(staff.owns(staff.ribbon(false)));
    }
}
