// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class WideChordSlurOwnersTest {
    private static final int WIDTH = 300;
    private static final int HEIGHT = 260;
    private static final int GAP = 20;

    private PrintedSlurOwners.Binding bind(boolean sharedStem, boolean endingUpperHead) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int x : new int[] {80, 200}) {
            for (int y : new int[] {100, 170}) {
                if (x == 200 && y == 100 && !endingUpperHead) continue;
                for (int dy = -7; dy <= 7; dy++) {
                    for (int dx = -13; dx <= 13; dx++) {
                        if (dx * dx / 169.0 + dy * dy / 49.0 <= 1)
                            gray[(y + dy) * WIDTH + x + dx] = 0;
                    }
                }
            }
            if (sharedStem && (x == 80 || endingUpperHead)) {
                for (int y = 100; y <= 215; y++) {
                    gray[y * WIDTH + x - 11] = 0;
                    gray[y * WIDTH + x - 10] = 0;
                }
            }
        }
        var track = StaffPitchTrack.fromVerifiedSamples(
                List.of(new float[] {0, 190, GAP}, new float[] {WIDTH - 1, 190, GAP}));
        var heads = new ArrayList<>(List.of(
                new PrintedSlurOwners.GeometryHead(0, 80, 100, 0, 1),
                new PrintedSlurOwners.GeometryHead(1, 80, 170, 0, 1),
                new PrintedSlurOwners.GeometryHead(3, 200, 170, 0, 1)));
        if (endingUpperHead)
            heads.add(new PrintedSlurOwners.GeometryHead(2, 200, 100, 0, 1));
        var bow = new PrintedSlurContours.Bow(
                List.of(new PrintedSlurContours.Point(80, 76),
                        new PrintedSlurContours.Point(140, 57),
                        new PrintedSlurContours.Point(200, 76)),
                true, .95f, 0);
        return PrintedSlurOwners.bind("octave", 0, bow, heads, gray, WIDTH, HEIGHT, track);
    }

    @Test
    public void sharedOctaveStemSelectsTheTwoUpperHeads() {
        var bound = bind(true, true);
        assertEquals(PrintedSlurOwners.Status.GEOMETRY_OWNED, bound.status());
        assertEquals(0, bound.span().orElseThrow().start().noteIndex());
        assertEquals(2, bound.span().orElseThrow().end().noteIndex());
    }

    @Test
    public void verticalAlignmentAloneDoesNotResolveWideRivals() {
        assertEquals(PrintedSlurOwners.Status.AMBIGUOUS_START, bind(false, true).status());
    }

    @Test
    public void aDistantLowerHeadCannotReplaceTheMissingUpperEndpoint() {
        assertEquals(PrintedSlurOwners.Status.NO_END_OWNER, bind(true, false).status());
    }
}
