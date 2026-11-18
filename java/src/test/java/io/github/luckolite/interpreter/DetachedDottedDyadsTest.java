// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class DetachedDottedDyadsTest {
    private static final int WIDTH = 1150, HEIGHT = 430, PAGE = 0;
    private static final float GAP = 16f;
    private static final int FIRST = 160, SPACING = 39;

    @Test
    public void provesDownwardThirdsAndUpwardOctavesFromTheirSharedShafts() {
        for (var fixture : List.of(new Fixture(1, 16), new Fixture(-1, 56))) {
            var page = fixture.projects(fixture.image);
            assertEquals(4, page.groups().size());
            for (var group : page.groups()) {
                assertEquals(List.of(3, 3, 3), group.bridges());
                assertEquals(List.of(
                        new ScoreBeamSource.Hook(1, 3, ScoreBeamSource.Direction.BACKWARD),
                        new ScoreBeamSource.Hook(3, 3, ScoreBeamSource.Direction.BACKWARD)),
                        group.hooks());
            }
            var cut = fixture.image.clone();
            fill(cut, shaft(1) - 13, shaft(1) - 2, 312, 325, (byte) 255, fixture.direction);
            assertEquals(page.groups().subList(1, 4), fixture.projects(cut).groups());
        }
    }

    @Test
    public void refusesToSplitAnExistingRibbonAtAnArtificialFourColumnCut() {
        var fixture = new Fixture(-1, 56);
        var raw = fixture.image.clone();
        fill(raw, shaft(3), shaft(4), 342, 349, (byte) 0, fixture.direction);
        var groups = fixture.projects(raw).groups();
        assertFalse(groups.isEmpty());
        assertFalse(groups.stream().anyMatch(g -> g.id().endsWith(":0") || g.id().endsWith(":8")));
        assertEquals(2, groups.size());
    }

    private static final class Fixture {
        private final int direction;
        private final byte[] image = new byte[WIDTH * HEIGHT];
        private final List<ClosedOctaveBeamSource.Attack> attacks = new ArrayList<>();

        Fixture(int direction, int interval) {
            this.direction = direction;
            Arrays.fill(image, (byte) 255);
            for (int column = 0; column < 16; column++) {
                int x = shaft(column);
                int noteX = x + 10 * direction;
                int top = 218;
                int bottom = top + interval;
                fill(image, x, x + 2, top, 349, (byte) 0, direction);
                for (int y : new int[] {top, bottom})
                    fill(image, noteX - 9, noteX + 9, y - 5, y + 5, (byte) 0, direction);
                if (column % 2 != 0)
                    fill(image, x - 11, x + 2, 314, 323, (byte) 0, direction);
                if (column % 4 == 0) {
                    fill(image, x, shaft(column + 3) + 2, 342, 349, (byte) 0, direction);
                    fill(image, x, shaft(column + 3) + 2, 328, 335, (byte) 0, direction);
                }
                int writtenUpper = y(direction > 0 ? top : bottom, direction);
                int writtenLower = y(direction > 0 ? bottom : top, direction);
                attacks.add(new ClosedOctaveBeamSource.Attack(
                        2 * column, 2 * column + 1, noteX, writtenUpper, noteX, writtenLower));
            }
        }

        ScoreBeamSource projects(byte[] paper) {
            return ClosedOctaveBeamSource.captureDetachedDottedDyads(ScoreBeamSource.empty(32),
                    PAGE, image, paper, WIDTH, HEIGHT, GAP, attacks,
                    Collections.nCopies(attacks.size(), direction));
        }
    }

    private static int shaft(int column) {
        return FIRST + column * SPACING;
    }

    private static int y(int y, int direction) {
        return direction > 0 ? y : HEIGHT - 1 - y;
    }

    private static void fill(
            byte[] raw, int left, int right, int top, int bottom, byte ink, int direction) {
        for (int i = top; i <= bottom; i++)
            Arrays.fill(raw, y(i,direction) * WIDTH + left, y(i,direction) * WIDTH + right + 1, ink);
    }
}
