// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class FollowingDoubleSharpDotTest {
    private static final int WIDTH = 105;
    private static final int HEIGHT = 85;
    private final byte[] gray = new byte[WIDTH * HEIGHT];

    public FollowingDoubleSharpDotTest() {
        Arrays.fill(gray, (byte) 255);
        for (int y = 0; y < 18; y++)
            for (int x = 0; x < 20; x++)
                if (Math.abs(x - y) <= 3 || Math.abs(x + y - 18) <= 3)
                    gray[(30 + y) * WIDTH + 34 + x] = 0;
        for (int y = 40; y <= 42; y++)
            for (int x = 31; x <= 53; x++) gray[y * WIDTH + x] = 0;
    }

    @Test
    public void followingCrossOwnsItsDarkArm() throws Exception {
        assertTrue(owns(gray, arm(), followingHead(59)));
    }

    @Test
    public void crossMustBePrintedInBothImages() throws Exception {
        var missingArm = gray.clone();
        for (int y = 30; y < 36; y++)
            Arrays.fill(missingArm, y * WIDTH + 34, y * WIDTH + 45, (byte) 255);
        assertFalse(owns(missingArm, arm(), followingHead(59)));
    }

    @Test
    public void aCrossCannotTakeADetachedDotOrADistantHead() throws Exception {
        assertFalse(owns(gray, component(18, 24, 28, 42, 46, 26, 44), followingHead(59)));
        assertFalse(owns(gray, arm(), followingHead(77)));
    }

    private Object arm() throws Exception {
        return component(18, 48, 52, 42, 46, 50, 44);
    }

    private Object followingHead(int x) throws Exception {
        return component(212, x, x + 20, 31, 46, x + 10, 39);
    }

    private boolean owns(byte[] original, Object dot, Object head) throws Exception {
        var interpreter = OmrScoreInterpreter.class;
        var seed = component(221, 31, 53, 30, 47, 43, 39);
        var glyph = create("AccidentalCandidate", seed, OmrMeasurePostProcessor.SYMBOL);
        var context = create("DotAccidentalSource", new byte[gray.length], original, List.of(glyph));
        var call = interpreter.getDeclaredMethod("followingDoubleSharpOwnsDot",
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
}
