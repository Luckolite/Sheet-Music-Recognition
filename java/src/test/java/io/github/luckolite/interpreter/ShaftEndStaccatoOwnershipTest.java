// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class ShaftEndStaccatoOwnershipTest {
    private static final int WIDTH = 192, HEIGHT = 192;
    private static final Class<?> COMPONENT = nested("Component");

    @Test
    public void detachedDotAtTheClosedRailEndBelongsToTheLastHead() throws Exception {
        var page = fixture();
        assertEquals(Map.of(page.dot, page.owner), claims(page, page.gray));
    }

    @Test
    public void erasedDotOrBrokenRailCannotSupplyAnArticulation() throws Exception {
        var page = fixture();
        var erased = page.gray.clone();
        rectangle(erased, 109, 118, 121, 131, 255);
        assertTrue(claims(page, erased).isEmpty());
        var severed = page.gray.clone();
        rectangle(severed, 98, 105, 103, 120, 255);
        assertTrue(claims(page, severed).isEmpty());
    }

    @Test
    public void filledSmallHeadWithItsOwnStemSurvives() throws Exception {
        var page = fixture();
        var stemmed = page.gray.clone();
        rectangle(stemmed, 112, 123, 114, 162, 0);
        assertTrue(claims(page, stemmed).isEmpty());
    }

    private static Map<?, ?> claims(Fixture p, byte[] gray) throws Exception {
        var gate = OmrScoreInterpreter.class.getDeclaredMethod("shaftEndStaccatoHeads",
                byte[].class, byte[].class, byte[].class, int.class, int.class,
                List.class, List.class, List.class);
        gate.setAccessible(true);
        return (Map<?, ?>) gate.invoke(null, p.labels, gray, gray, WIDTH, HEIGHT,
                List.of(p.previous, p.owner, p.dot), List.of(p.staff), List.of(p.region));
    }

    private static Fixture fixture() throws Exception {
        byte[] gray = new byte[WIDTH * HEIGHT], labels = new byte[gray.length];
        Arrays.fill(gray, (byte) 255);
        var previous = oval(gray, labels, 94, 61, 10, 9);
        var owner = oval(gray, labels, 125, 45, 10, 9);
        var dot = oval(gray, labels, 115, 124, 4, 4);
        rectangle(gray, 84, 61, 86, 119, 0);
        rectangle(gray, 115, 45, 117, 112, 0);
        for (int x = 84; x <= 117; x++) {
            int bottom = Math.round(119 - 7 * Math.min(x - 84, 31) / 31f);
            rectangle(gray, x, bottom - 6, x, bottom, 0);
        }
        var constructor = nested("Staff").getDeclaredConstructor(float.class, float.class, float.class);
        constructor.setAccessible(true);
        Object staff = constructor.newInstance(68f,132f,16f);
        var region = new MeasureRegion(40f / WIDTH, 170f / WIDTH, 24f / HEIGHT, 168f / HEIGHT);
        return new Fixture(gray, labels, previous, owner, dot, staff, region);
    }

    private static Object oval(byte[] gray, byte[] labels, int x, int y, int rx, int ry)
            throws Exception {
        int area = 0, left = WIDTH, right = -1, top = HEIGHT, bottom = -1;
        for (int dy = -ry; dy <= ry; dy++)
            for (int dx = -rx; dx <= rx; dx++) {
                if (dx*dx / (float)(rx*rx) + dy*dy / (float)(ry*ry) > .90f) continue;
                int xx = x + dx, yy = y + dy;
                gray[yy * WIDTH + xx] = 0;
                labels[yy * WIDTH + xx] = OmrMeasurePostProcessor.NOTEHEAD;
                left = Math.min(left, xx);
                right = Math.max(right, xx);
                top = Math.min(top, yy);
                bottom = Math.max(bottom, yy);
                area++;
            }
        var constructor = COMPONENT.getDeclaredConstructor(int.class, int.class, int.class, int.class,
                int.class, float.class, float.class);
        constructor.setAccessible(true);
        return constructor.newInstance(area, left, right, top, bottom, (float)x, (float)y);
    }

    private static void rectangle(byte[] plane, int l, int t, int r, int b, int ink) {
        for (int y = t; y <= b; y++) Arrays.fill(plane, y*WIDTH+l, y*WIDTH+r+1, (byte)ink);
    }

    private static Class<?> nested(String name) {
        try {
            return Class.forName(OmrScoreInterpreter.class.getName() + "$" + name);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record Fixture(byte[] gray, byte[] labels, Object previous, Object owner,
            Object dot, Object staff, MeasureRegion region) {}
}
