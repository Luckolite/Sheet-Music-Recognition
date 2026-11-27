// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import java.util.Arrays;
import org.junit.Test;

public final class OpeningConnectorMeasureTest {
    private static final int WIDTH = 540, HEIGHT = 360, GAP = 16;
    private static final int BEFORE = 80, CONNECTOR = 144, CLOSE = 475;
    private static final int[] STAFF_TOPS = {65, 217};

    @Test
    public void emptyAreaToTheLeftOfAJoinedStaffHasNoMeasure() {
        assertEquals(1, measures(false));
    }

    @Test
    public void staffRulesAndAPlacedRestKeepTheShortOpeningMeasure() {
        assertEquals(2, measures(true));
    }

    private static int measures(boolean printedOpening) {
        byte[] gray = new byte[WIDTH * HEIGHT], labels = new byte[gray.length];
        Arrays.fill(gray, (byte) 255);
        for (int top : STAFF_TOPS) {
            int start = printedOpening || top == STAFF_TOPS[0] ? BEFORE : CONNECTOR;
            for (int line = 0; line < 5; line++) {
                int y = top + line * GAP;
                for (int x = start; x <= CLOSE; x++) {
                    labels[y * WIDTH + x] = OmrMeasurePostProcessor.STAFF;
                    if (printedOpening || x >= CONNECTOR) gray[y * WIDTH + x] = 0;
                }
            }
            for (int x : new int[] {start, CONNECTOR, CLOSE})
                for (int y = top; y <= top + 4 * GAP; y++) {
                    labels[y * WIDTH + x] = OmrMeasurePostProcessor.STEM_OR_REST;
                    if (printedOpening || x >= CONNECTOR) gray[y * WIDTH + x] = 0;
                }
            if (printedOpening)
                for (int y = top + 2 * GAP; y < top + 2 * GAP + 5; y++)
                    for (int x = BEFORE + 24; x < BEFORE + 37; x++) {
                        gray[y * WIDTH + x] = 0;
                        labels[y * WIDTH + x] = OmrMeasurePostProcessor.STEM_OR_REST;
                    }
        }
        for (int y = STAFF_TOPS[0]; y <= STAFF_TOPS[1] + 4 * GAP; y++) {
            gray[y * WIDTH + CONNECTOR] = 0;
            gray[y * WIDTH + CLOSE] = 0;
            if (printedOpening) gray[y * WIDTH + BEFORE] = 0;
        }
        return OmrMeasurePostProcessor.process(labels, gray, WIDTH, HEIGHT).size();
    }

    @Test
    public void openingConnectorOutsideInsetHeadersNeedsMatchingBarsAndPrintedContact() throws Exception {
        assertEquals(true, joinedAcrossInset(true, true, true));
        assertEquals(false, joinedAcrossInset(true, false, true));
        assertEquals(false, joinedAcrossInset(true, true, false));
        assertEquals(false, joinedAcrossInset(false, true, true));
    }

    @Test
    public void aLongActualConnectorCanBridgeAlignedEnsembleStaves() throws Exception {
        assertEquals(true, joinedAcrossInset(true, true, true, true));
        assertEquals(false, joinedAcrossInset(false, true, true, true));
        assertEquals(false, joinedAcrossInset(true, false, true, true));
    }

    private static boolean joinedAcrossInset(boolean connector, boolean sharesBars, boolean contact)
            throws Exception {
        return joinedAcrossInset(connector, sharesBars, contact, false);
    }

    private static boolean joinedAcrossInset(
            boolean connector, boolean sharesBars, boolean contact, boolean wide)
            throws Exception {
        int width = 600, height = wide ? 490 : 390, gap = 16;
        int top = 64, lowerTop = wide ? 400 : 224, reportedLeft = 85, opening = 48, end = 537;
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 255);
        for (int y0 : new int[] {top, lowerTop}) {
            for (int line = 0; line < 5; line++) {
                int row = (y0 + line * gap) * width;
                Arrays.fill(gray, row + (contact ? opening : reportedLeft), row + end + 1, (byte) 0);
            }
            for (int x : new int[] {reportedLeft, 201, 311, 423, end})
                for (int y = y0; y <= y0 + 4 * gap; y++) gray[y * width + x] = 0;
        }
        if (connector)
            for (int y = top; y <= lowerTop + 4 * gap; y++) gray[y * width + opening] = 0;
        Class<?> processor = OmrMeasurePostProcessor.class;
        Class<?> staff = Class.forName(processor.getName() + "$StaffRun");
        Class<?> system = Class.forName(processor.getName() + "$SystemRun");
        var staffCtor = staff.getDeclaredConstructor(
                int.class, int.class, float.class, int.class, int.class, java.util.List.class, float.class);
        staffCtor.setAccessible(true);
        var upper = staffCtor.newInstance(top, top + 4 * gap, (float) gap, reportedLeft, end,
                java.util.List.of(reportedLeft, 201, 311, 423, end), 0f);
        var lower = staffCtor.newInstance(lowerTop, lowerTop + 4 * gap, (float) gap, reportedLeft, end,
                java.util.List.of(reportedLeft, sharesBars ? 201 : 240, 311, 423, end), 0f);
        if (wide) {
            var merge = processor.getDeclaredMethod("mergeAlignedStaffs",
                    java.util.List.class, byte[].class, byte[].class, int.class, int.class);
            merge.setAccessible(true);
            return ((java.util.List<?>) merge.invoke(
                    null, java.util.List.of(upper, lower), null, gray, width, height)).size() == 1;
        }
        var systemCtor = system.getDeclaredConstructor(staff);
        systemCtor.setAccessible(true);
        var check = processor.getDeclaredMethod("connectedByVerticalRule",
                byte[].class, int.class, int.class, system, staff, float.class, boolean.class);
        check.setAccessible(true);
        return (boolean) check.invoke(null, gray, width, height,
                systemCtor.newInstance(upper), lower, (float) gap, false);
    }
}
