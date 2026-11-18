// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.junit.Test;

public class StackedSharpOwnershipTest {
    private static final int W = 280, H = 320, UPPER = 130, LOWER = 172;
    private static final float GAP = 16f;
    private final byte[] labels = new byte[W * H], ink = new byte[W * H];

    public StackedSharpOwnershipTest() {
        Arrays.fill(ink, (byte) 255);
        oval(UPPER);
        oval(LOWER);
        for (int y = UPPER - 1; y <= 255; y++) for (int x = 159; x <= 161; x++) ink[y * W + x] = 0;
        for (int y = 107; y <= 195; y++)
            for (int x = 141; x <= 155; x++)
                if (x >= 144 && x <= 146 || x >= 151 && x <= 153) glyphPixel(x, y);
        for (int center : new int[] {121, 138, 163, 180})
            for (int x = 141; x <= 155; x++) {
                int row = Math.round(center - .21f * (x - 148));
                for (int d = -2; d <= 2; d++) glyphPixel(x, row + d);
            }
    }

    @Test
    public void bothCompleteSharpsOwnThePrintedDyad() throws Exception {
        assertTrue(proved(ink, true));
        assertTrue(proved(ink, false));
    }

    @Test
    public void oneRetainedSharpDoesNotProveTheOther() throws Exception {
        var one = ink.clone();
        erase(one, 141, 155, 157, 195);
        assertEquals(ScoreNoteEvent.ACCIDENTAL_FROM_KEY, kind(one, true));
        assertEquals(ScoreNoteEvent.ACCIDENTAL_FROM_KEY, kind(one, false));
    }

    @Test
    public void bothIndependentCrossbarsMustSurviveInTheOriginal() throws Exception {
        var erased = ink.clone();
        erase(erased, 146, 151, 156, 169);
        assertEquals(ScoreNoteEvent.ACCIDENTAL_FROM_KEY, kind(erased, true));
        assertEquals(ScoreNoteEvent.ACCIDENTAL_FROM_KEY, kind(erased, false));
    }

    @Test
    public void asymmetricShaftTailsKeepTheLowerNatural() throws Exception {
        var mixed = ink.clone();
        erase(mixed, 150, 154, 153, 159);
        erase(mixed, 143, 147, 184, 195);
        assertEquals(ScoreNoteEvent.ACCIDENTAL_SHARP, kind(mixed, true));
        assertEquals(ScoreNoteEvent.ACCIDENTAL_NATURAL, kind(mixed, false));
    }

    @Test
    public void bothSharpsCanOwnAnUpstemDyadOnOneSharedShaft() throws Exception {
        var upward = ink.clone();
        erase(upward, 159, 161, UPPER + 10, LOWER - 10);
        erase(upward, 159, 161, LOWER + 10, 255);
        for (int y = 52; y <= LOWER; y++) {
            for (int x = 179; x <= 181; x++) upward[y * W + x] = 0;
        }
        var missingCrossbar = upward.clone();
        erase(missingCrossbar, 146, 151, 156, 169);
        for (boolean upper : new boolean[] {true, false}) {
            assertEquals(ScoreNoteEvent.ACCIDENTAL_SHARP, beamedKind(labels, upward, upward, upper));
            assertEquals(ScoreNoteEvent.ACCIDENTAL_FROM_KEY, beamedKind(labels, upward, missingCrossbar, upper));
            assertEquals(ScoreNoteEvent.ACCIDENTAL_FROM_KEY, beamedKind(labels, missingCrossbar, upward, upper));
            var missingMembers = labels.clone();
            for (int y = 159; y <= 167; y++)
                Arrays.fill(missingMembers, y * W + 146, y * W + 151, (byte) 0);
            assertEquals(ScoreNoteEvent.ACCIDENTAL_FROM_KEY, beamedKind(missingMembers, upward, upward, upper));
        }
        var mixed = upward.clone();
        erase(mixed, 150, 154, 153, 159);
        erase(mixed, 143, 147, 184, 195);
        assertEquals(ScoreNoteEvent.ACCIDENTAL_SHARP, beamedKind(labels, mixed, mixed, true));
        assertEquals(ScoreNoteEvent.ACCIDENTAL_NATURAL, beamedKind(labels, mixed, mixed, false));
    }

    @Test
    public void narrowLowerNaturalCanOwnItsCrossbarsInsideTheSharedGlyph() throws Exception {
        var source = ink.clone();
        var ownership = labels.clone();
        erase(source, 159, 161, UPPER + 10, LOWER - 10);
        erase(source, 159, 161, LOWER + 10, 255);
        for (int y = 52; y <= LOWER; y++) {
            for (int x = 179; x <= 181; x++) source[y * W + x] = 0;
        }
        erase(source, 150, 154, 153, 159);
        erase(source, 143, 147, 184, 195);
        for (int y = 167; y <= 177; y++) {
            source[y * W + 141] = 0;
            source[y * W + 142] = 0;
            ownership[y * W + 141] = OmrMeasurePostProcessor.CLEF_OR_KEY;
            ownership[y * W + 142] = OmrMeasurePostProcessor.CLEF_OR_KEY;
        }
        source[167 * W + 143] = 0;
        ownership[167 * W + 143] = OmrMeasurePostProcessor.CLEF_OR_KEY;
        assertEquals(ScoreNoteEvent.ACCIDENTAL_SHARP, beamedKind(ownership, source, source, true));
        assertEquals(ScoreNoteEvent.ACCIDENTAL_NATURAL, beamedKind(ownership, source, source, false));
        var missingCrossbar = source.clone();
        erase(missingCrossbar, 146, 150, 176, 184);
        assertEquals(ScoreNoteEvent.ACCIDENTAL_FROM_KEY, beamedKind(ownership, source, missingCrossbar, false));
    }

    private int beamedKind(byte[] mask, byte[] normalized, byte[] source, boolean upper) throws Exception {
        var interpreter = OmrScoreInterpreter.class;
        var component = Class.forName(interpreter.getName() + "$Component");
        Method components =
                interpreter.getDeclaredMethod("findComponents", byte[].class, int.class, int.class, byte.class);
        components.setAccessible(true);
        var heads = (List<?>) components.invoke(null, mask, W, H, OmrMeasurePostProcessor.NOTEHEAD);
        var members = new ExactComponentMembers.Collector(normalized, mask, W, H,
                new ExactComponentMembers.Budget(8L * mask.length, 262_144),
                new ExactComponentMembers.Budget(8L * mask.length, 262_144));
        Method glyphComponents = interpreter.getDeclaredMethod("findComponents", byte[].class, int.class,
                int.class, byte.class, byte[].class, java.util.IdentityHashMap.class,
                ExactComponentMembers.Collector.class);
        glyphComponents.setAccessible(true);
        var sharps = (List<?>) glyphComponents.invoke(null, mask, W, H, OmrMeasurePostProcessor.CLEF_OR_KEY,
                normalized, null, members);
        assertTrue(members.seal(new ExactComponentMembers.Budget(8L * mask.length, 262_144)));
        Method center = component.getDeclaredMethod("centerY");
        center.setAccessible(true);
        var sorted = heads.stream().sorted(Comparator.comparingDouble(n -> y(n, center))).toList();
        Method ownership = interpreter.getDeclaredMethod(
                "ownedStackedAccidentalsOnBeamedSixth", byte[].class, byte[].class, int.class, int.class,
                List.class, component, List.class, float.class, ExactComponentMembers.Collector.class, List.class);
        ownership.setAccessible(true);
        return (int) ownership.invoke(null, normalized, source, W, H, sharps,
                sorted.get(upper ? 0 : 1), heads, GAP, members, new java.util.ArrayList<>());
    }

    private boolean proved(byte[] source, boolean upper) throws Exception {
        return kind(source, upper) == ScoreNoteEvent.ACCIDENTAL_SHARP;
    }

    private int kind(byte[] source, boolean upper) throws Exception {
        return kind(source, upper, 190f);
    }

    @Test
    public void staffIntersectionDoesNotSupplyAccidentalCrossbars() throws Exception {
        assertEquals(ScoreNoteEvent.ACCIDENTAL_FROM_KEY, kind(ink, true, 166f));
        assertEquals(ScoreNoteEvent.ACCIDENTAL_FROM_KEY, kind(ink, false, 166f));
    }

    @Test
    public void separatelySlopedSharpsOwnTheTopStaffCrossing() throws Exception {
        var withStaff = staffInk();
        assertEquals(ScoreNoteEvent.ACCIDENTAL_SHARP, crossing(withStaff, true));
        assertEquals(ScoreNoteEvent.ACCIDENTAL_SHARP, crossing(withStaff, false));
    }

    @Test
    public void unchangedStaffCannotStandInForAnErasedSharpCrossbar() throws Exception {
        var erased = staffInk();
        erase(erased, 147, 150, 157, 168);
        assertEquals(ScoreNoteEvent.ACCIDENTAL_FROM_KEY, crossing(erased, true));
        assertEquals(ScoreNoteEvent.ACCIDENTAL_FROM_KEY, crossing(erased, false));
    }

    @Test
    public void aNearbyLedgerStrokeDoesNotAuthorizeTheStaffCrossingRoute() throws Exception {
        var withOneLine = staffInk();
        erase(withOneLine, 80, 110, 155, 157);
        assertEquals(ScoreNoteEvent.ACCIDENTAL_FROM_KEY, crossing(withOneLine, true));
        assertEquals(ScoreNoteEvent.ACCIDENTAL_FROM_KEY, crossing(withOneLine, false));
    }

    private byte[] staffInk() {
        var withStaff = ink.clone();
        for (int y : new int[] {156, 172}) for (int x = 75; x <= 217; x++) withStaff[y * W + x] = 0;
        return withStaff;
    }

    private int crossing(byte[] source, boolean upper) throws Exception {
        return kind(source, upper, 156f, true);
    }

    private int kind(byte[] source, boolean upper, float staffTop) throws Exception {
        return kind(source, upper, staffTop, false);
    }

    private int kind(byte[] source, boolean upper, float staffTop, boolean crossesStaff)
            throws Exception {
        var interpreter = OmrScoreInterpreter.class;
        var component = Class.forName(interpreter.getName() + "$Component");
        Method components =
                interpreter.getDeclaredMethod(
                        "findComponents", byte[].class, int.class, int.class, byte.class);
        components.setAccessible(true);
        var heads =
                (List<?>) components.invoke(null, labels, W, H, OmrMeasurePostProcessor.NOTEHEAD);
        var sharps =
                (List<?>)
                        components.invoke(null, labels, W, H, OmrMeasurePostProcessor.CLEF_OR_KEY);
        Method center = component.getDeclaredMethod("centerY");
        center.setAccessible(true);
        var sorted = heads.stream().sorted(Comparator.comparingDouble(n -> y(n, center))).toList();
        Method ownership =
                interpreter.getDeclaredMethod(
                        "stackedAccidentalForWideDyad",
                        byte[].class,
                        byte[].class,
                        int.class,
                        int.class,
                        List.class,
                        component,
                        List.class,
                        float.class,
                        float.class,
                        boolean.class);
        ownership.setAccessible(true);
        return (int)
                ownership.invoke(
                        null,
                        ink,
                        source,
                        W,
                        H,
                        sharps,
                        sorted.get(upper ? 0 : 1),
                        heads,
                        GAP,
                        staffTop,
                        crossesStaff);
    }

    private static float y(Object body, Method method) {
        try {
            return (float) method.invoke(body);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void glyphPixel(int x, int y) {
        labels[y * W + x] = OmrMeasurePostProcessor.CLEF_OR_KEY;
        ink[y * W + x] = 0;
    }

    private void oval(int center) {
        for (int dy = -9; dy <= 9; dy++)
            for (int dx = -11; dx <= 11; dx++)
                if (dx * dx / 121f + dy * dy / 81f <= 1) {
                    int index = (center + dy) * W + 170 + dx;
                    labels[index] = OmrMeasurePostProcessor.NOTEHEAD;
                    ink[index] = 0;
                }
    }

    private void erase(byte[] plane, int left, int right, int top, int bottom) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(plane, y * W + left, y * W + right + 1, (byte) 255);
    }
}
