// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class FollowingFlatBowlDotTest {
    private static final int WIDTH = 116;
    private static final int HEIGHT = 116;
    private final byte[] gray = new byte[WIDTH * HEIGHT];
    private final byte[] labels = new byte[gray.length];

    public FollowingFlatBowlDotTest() {
        Arrays.fill(gray, (byte) 255);
        paint(gray, 30, 32, 25, 64, 230);
        bowl(30, 41, 49, 50);
        bowl(30, 41, 60, 61);
        bowl(40, 41, 49, 61);
    }

    @Test
    public void followingFlatOwnsItsOwnBowl() throws Exception {
        var fragment = component(15, 39, 41, 53, 57, 40f, 55f);
        assertTrue(owns(gray, fragment));
    }

    @Test
    public void bowlNeedsTheSpineInBothImages() throws Exception {
        var fragment = component(15, 39, 41, 53, 57, 40f, 55f);
        var erased = gray.clone();
        paint(erased, 30, 32, 25, 47, 255);
        assertFalse(owns(erased, fragment));
    }

    @Test
    public void completeFlatDoesNotClaimAnIndependentDot() throws Exception {
        paint(gray, 23, 26, 52, 56, 0);
        var detached = component(18, 23, 26, 52, 56, 24.5f, 54f);
        assertFalse(owns(gray, detached));
    }

    private boolean owns(byte[] original, Object dot) throws Exception {
        var types = OmrScoreInterpreter.class;
        var head = component(120, 47, 63, 49, 61, 55f, 55f);
        var seed = component(56, 30, 41, 49, 64, 34f, 56f);
        var glyph = create("AccidentalCandidate", seed, OmrMeasurePostProcessor.CLEF_OR_KEY);
        var context = create("DotAccidentalSource", labels, original, List.of(glyph));
        var call = types.getDeclaredMethod("followingFlatBowlOwnsDot",
                context.getClass(), byte[].class, int.class, int.class, dot.getClass(), float.class, List.class);
        call.setAccessible(true);
        return (boolean) call.invoke(null, context, gray, WIDTH, HEIGHT, dot, 16f, List.of(head));
    }

    private Object component(int area, int left, int right, int top, int bottom, float x, float y)
            throws Exception {
        return create("Component", area, left, right, top, bottom, x, y);
    }

    private Object create(String name, Object... args) throws Exception {
        var ctor = Class.forName(OmrScoreInterpreter.class.getName() + "$" + name)
                .getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(args);
    }

    private void bowl(int left, int right, int top, int bottom) {
        paint(gray, left, right, top, bottom, 0);
        paint(labels, left, right, top, bottom, OmrMeasurePostProcessor.CLEF_OR_KEY);
    }

    private void paint(byte[] image, int left, int right, int top, int bottom, int value) {
        for (int y = top; y <= bottom; y++) {
            Arrays.fill(image, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
        }
    }
}
