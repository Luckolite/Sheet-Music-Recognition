// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class BoldSlurWorkLimitOriginalTest {
    @Test
    public void unsearchedWideThickComponentPropagatesUnknown() {
        int w = 9000, h = 310;
        byte[] g = new byte[w * h];
        Arrays.fill(g, (byte) 255);
        var track =
                StaffPitchTrack.fromVerifiedSamples(
                        List.of(new float[] {0, 170, 20}, new float[] {w - 1, 170, 20}));
        var heads = new ArrayList<PrintedSlurOwners.GeometryHead>();
        for (int k = 0; k < 2; k++) {
            int cx = k == 0 ? 58 : 8412, cy = 180;
            heads.add(new PrintedSlurOwners.GeometryHead(k, cx, cy, 0, 1));
            for (int y = cy - 5; y <= cy + 5; y++)
                for (int x = cx - 9; x <= cx + 9; x++)
                    if ((x - cx) * (x - cx) / 81d + (y - cy) * (y - cy) / 25d <= 1)
                        g[y * w + x] = 0;
        }
        for (int x = 70; x <= 8400; x++) {
            double t = 2d * (x - 70) / 8330 - 1, y = 225 - 30 * t * t, a = 1 + 5 * (1 - t * t);
            for (int yy = (int) Math.ceil(y - a); yy <= Math.floor(y + a); yy++) g[yy * w + x] = 0;
        }
        var hints = PrintedSlurOwners.headEvidence(heads, g, w, h, track);
        assertTrue(
                PrintedSlurContours.find(g, null, w, h, 0, 10, w - 1, h - 1, track, null, hints)
                        .limitReached());
    }

    @Test
    public void optionalRibbonRefusalKeepsAnIndependentlyCompletedSlur() {
        int w = 9000, h = 310;
        byte[] gray = new byte[w * h];
        Arrays.fill(gray, (byte) 255);
        var track =
                StaffPitchTrack.fromVerifiedSamples(
                        List.of(new float[] {0, 170, 20}, new float[] {w - 1, 170, 20}));
        for (int x = 70; x <= 8400; x++) {
            double t = 2d * (x - 70) / 8330 - 1;
            double y = 225 - 30 * t * t, a = 1 + 5 * (1 - t * t);
            for (int yy = (int) Math.ceil(y - a); yy <= Math.floor(y + a); yy++)
                gray[yy * w + x] = 0;
        }
        for (int x = 8620; x <= 8780; x++) {
            double t = 2d * (x - 8620) / 160 - 1;
            gray[(int) Math.round(52 + 21 * t * t) * w + x] = 0;
        }
        var heads = List.of(
                new PrintedSlurOwners.Head(0, 58, 180, 0, 1, 1, 0, 60),
                new PrintedSlurOwners.Head(1, 8412, 180, 0, 1, 1, 100, 62),
                new PrintedSlurOwners.Head(2, 8620, 90, 0, 1, 1, 200, 64),
                new PrintedSlurOwners.Head(3, 8780, 93, 0, 1, 1, 300, 65));
        for (var head : heads) {
            int x = Math.round(head.x()), y = Math.round(head.y());
            for (int row = y - 6; row <= y + 6; row++)
                Arrays.fill(gray, row * w + x - 9, row * w + x + 10, (byte) 0);
        }
        var hints = PrintedSlurOwners.headEvidence(heads, gray, w, h, track);
        var contour = PrintedSlurContours.find(gray, null, w, h, 0, 10, w - 1, h - 1, track, null, hints);
        assertTrue(contour.limitReached());
        assertFalse(contour.requiredLimitReached());

        var frame = new PrintedSlurPageCapture.Frame<>(0, 10, w - 1, h - 1, track, heads);
        var captured = PrintedSlurPageCapture.capture(0, 4, gray, null, w, h, List.of(frame), Set.of());
        assertFalse(captured.limitReached());
        assertEquals(1, captured.source().spans().size());
        var span = captured.source().spans().get(0);
        assertEquals(new ScoreSlurPages.Anchor(0, 2), span.start());
        assertEquals(new ScoreSlurPages.Anchor(0, 3), span.end());
    }
}
