// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class BeamedFlatBarlineTest {
    private static final int WIDTH = 275, HEIGHT = 300, FIRST_RULE = 130, COLUMN = 124;
    private static final float GAP = 16;

    @Test
    public void flatSpineCannotDivideTheBeamBetweenTwoDoubleStops() {
        var plate = new Plate();
        assertTrue(plate.alias());
    }

    @Test
    public void actualDividerAndDisconnectedBeamBothVetoTheJoin() {
        var divided = new Plate();
        divided.ink(COLUMN, COLUMN + 2, FIRST_RULE, FIRST_RULE + (int) (4 * GAP), (byte) 0);
        assertFalse(divided.alias());

        var detached = new Plate();
        for (int y = 121; y <= 145; y++)
            Arrays.fill(detached.gray, y * WIDTH + COLUMN - 2, y * WIDTH + COLUMN + 4, (byte) 255);
        assertFalse(detached.alias());
    }

    @Test
    public void testsTheActualDividerColumnInsteadOfBorrowingAConnectedShaft() {
        var plate = new Plate();
        int shaft = COLUMN + 3, bottom = FIRST_RULE + 4 * (int) GAP;
        plate.ink(shaft, shaft + 1, FIRST_RULE, bottom + 18, (byte) 0);
        var staff = new RawStaffLineDetector.StaffLines(new int[] {130, 146, 162, 178, 194}, GAP);
        assertFalse(BeamedFlatBarline.seamRule(plate.gray, WIDTH, HEIGHT, COLUMN, staff, GAP));
        assertFalse(BeamedFlatBarline.seamRule(plate.gray, WIDTH, HEIGHT, shaft, staff, GAP));

        var divided = new Plate();
        divided.ink(COLUMN, COLUMN + 1, FIRST_RULE, bottom, (byte) 0);
        assertTrue(BeamedFlatBarline.seamRule(divided.gray, WIDTH, HEIGHT, COLUMN, staff, GAP));
    }

    @Test
    public void tallOctavesNearAProposedDividerRequireBothRailCores() {
        var plate = new Plate();
        Arrays.fill(plate.gray, (byte) 255);
        var a = new BeamedFlatBarline.Dyad(84, 110, 166);
        var b = new BeamedFlatBarline.Dyad(120, 111, 167);
        for (int x = 74; x <= 110; x++) {
            int tip = 229 + Math.round((x - 74) * .08f);
            plate.ink(x, x, tip - 7, tip - 3, (byte) 5);
            plate.ink(x, x, tip - 19, tip - 15, (byte) 5);
        }
        plate.ink(74, 76, 110, 227, (byte) 1);
        plate.ink(110, 112, 111, 230, (byte) 1);
        plate.head(84, 110);
        plate.head(84, 166);
        plate.head(120, 111);
        plate.head(120, 167);
        assertTrue(tallSeam(plate, 83, a, b));
        assertFalse(tallSeam(plate, 76, a, b));
        for (int y = 209; y <= 224; y++)
            Arrays.fill(plate.gray, y * WIDTH + 82, y * WIDTH + 91, (byte) 255);
        assertFalse(tallSeam(plate, 83, a, b));
    }

    private static boolean tallSeam(
            Plate plate, int seam, BeamedFlatBarline.Dyad a, BeamedFlatBarline.Dyad b) {
        return BeamedFlatBarline.joinedByTwoShaftsAndRails(
                plate.gray, WIDTH, HEIGHT, seam, a, b, GAP);
    }

    @Test
    public void fittingFlatMustOwnIndependentInkBeforeTheNextShaft() {
        int x = 102;
        float noteY = 175;
        var staff = new RawStaffLineDetector.StaffLines(new int[] {130, 146, 162, 178, 194}, GAP);
        var shaft = new PairedTremoloInk.Stem(125, 175, 229);
        var plate = new Plate();
        plate.ink(125, 127, 145, 229, (byte) 0);
        assertFalse(
                BeamedFlatBarline.flatBeforeShaft(
                        plate.gray, WIDTH, HEIGHT, x, shaft, noteY, staff));
        plate.ink(x, x + 1, 147, 188, (byte) 0);
        plate.ink(x + 3, x + 13, 166, 168, (byte) 0);
        plate.ink(x + 7, x + 13, 169, 177, (byte) 0);
        for (int y = 179; y <= 187; y++) {
            int end = x + Math.max(1, 10 - (y - 179));
            plate.ink(end - 1, end, y, y, (byte) 0);
        }
        assertTrue(
                BeamedFlatBarline.flatBeforeShaft(
                        plate.gray, WIDTH, HEIGHT, x, shaft, noteY, staff));
        var overlapping = new PairedTremoloInk.Stem(116, 175, 229);
        assertFalse(
                BeamedFlatBarline.flatBeforeShaft(
                        plate.gray, WIDTH, HEIGHT, x, overlapping, noteY, staff));
    }


    @Test
    public void offsetTerminalSharpsKeepSeparatePitchOwnership() {
        var plate = new Plate();
        Arrays.fill(plate.gray, (byte) 255);
        int targetX = 193, upperY = 138, lowerY = 177;
        drawSharp(plate, 161, upperY);
        drawSharp(plate, 155, lowerY);
        assertTrue(terminalSharp(plate, targetX, upperY, false));
        assertTrue(terminalSharp(plate, targetX, lowerY, true));

        // One stem through the projected overlap is not a second accidental.
        for (int y = lowerY - 23; y <= lowerY + 21; y++) {
            Arrays.fill(plate.gray, y * WIDTH + 151, y * WIDTH + 179, (byte) 255);
        }
        plate.ink(166, 168, lowerY - 23, lowerY + 21, (byte) 0);
        assertTrue(terminalSharp(plate, targetX, upperY, false));
        assertFalse(terminalSharp(plate, targetX, lowerY, true));
    }

    private static void drawSharp(Plate plate, int left, int pitch) {
        plate.ink(left + 4, left + 6, pitch - 19, pitch + 22, (byte) 0);
        plate.ink(left + 12, left + 14, pitch - 22, pitch + 18, (byte) 0);
        for (int dx = 0; dx <= 17; dx++) {
            int center = pitch + 2 - dx / 4;
            plate.ink(left + dx, left + dx, center - 10, center - 5, (byte) 0);
            plate.ink(left + dx, left + dx, center + 6, center + 11, (byte) 0);
        }
    }

    private static boolean terminalSharp(Plate plate, int x, int y, boolean lower) {
        return OmrScoreInterpreter.printedTerminalDyadSharp(
                plate.gray, WIDTH, HEIGHT, x, y, GAP, lower);
    }


    @Test
    public void pairedSharpSourceRequiresTwoUniqueRetainedOwners() {
        var region = new MeasureRegion(.12f, .88f, .2f, .8f);
        var regions = java.util.List.of(region);
        float x = WIDTH * .5f;
        var source = new BeamedFlatBarline.SharpTailJoin(regions,
                java.util.List.of(new BeamedFlatBarline.Dyad(x, 105, 145)));
        var upper = new ScoreNoteEvent(0, .5f, 10, 0, 1, 105f / HEIGHT,
                false, 0, 3, ScoreNoteEvent.ACCIDENTAL_SHARP, 0).withStemDirection(-1);
        var lower = new ScoreNoteEvent(0, .5f, 5, 0, 1, 145f / HEIGHT,
                false, 0, 3, ScoreNoteEvent.ACCIDENTAL_SHARP, 0).withStemDirection(-1);
        var held = java.util.List.of(upper, lower);
        assertTrue(source.printedSharpOwners(held, regions, WIDTH, HEIGHT).equals(held));
        assertTrue(source.printedSharpOwners(java.util.List.of(upper), regions, WIDTH, HEIGHT).isEmpty());
        assertTrue(source.printedSharpOwners(java.util.List.of(upper, upper, lower),
                regions, WIDTH, HEIGHT).isEmpty());
        assertTrue(source.printedSharpOwners(java.util.List.of(upper, upper),
                regions, WIDTH, HEIGHT).isEmpty());
    }

    private static final class Plate {
        final byte[] labels = new byte[WIDTH * HEIGHT];
        final byte[] gray = new byte[WIDTH * HEIGHT];

        Plate() {
            Arrays.fill(gray, (byte) 255);
            for (int i = 0; i < 5; i++)
                ink(15, 260, FIRST_RULE + i * (int) GAP, FIRST_RULE + i * (int) GAP + 1, (byte) 4);
            for (int x = 98; x <= 163; x++) {
                int y = 122 + Math.round((x - 98) * .063f);
                ink(x, x, y, y + 8, (byte) 5);
                ink(x, x, y + 12, y + 20, (byte) 5);
            }
            for (int x : new int[] {90, 152}) {
                int stemX = x + 8;
                int tip = 122 + Math.round((stemX - 98) * .063f);
                ink(stemX, stemX + 2, tip, 195, (byte) 1);
                head(x, 177);
                head(x, 194);
            }
            ink(COLUMN, COLUMN + 2, 145, 189, (byte) 3);
            ink(COLUMN + 4, COLUMN + 9, 167, 170, (byte) 3);
            ink(COLUMN + 9, COLUMN + 11, 171, 178, (byte) 3);
            for (int y = 177; y <= 189; y++)
                ink(
                        COLUMN + Math.round((189 - y) * .75f),
                        COLUMN + Math.round((189 - y) * .75f) + 2,
                        y,
                        y,
                        (byte) 3);
        }

        boolean alias() {
            return BeamedFlatBarline.owns(
                    labels,
                    gray,
                    WIDTH,
                    HEIGHT,
                    COLUMN,
                    FIRST_RULE,
                    FIRST_RULE + (int) (4 * GAP),
                    GAP);
        }

        void head(int x, int y) {
            for (int dy = -8; dy <= 8; dy++) {
                int radius = Math.round(11f * (float) Math.sqrt(1 - dy * dy / 81f));
                ink(x - radius, x + radius, y + dy, y + dy, (byte) 2);
            }
        }

        void ink(int left, int right, int top, int bottom, byte label) {
            for (int y = top; y <= bottom; y++)
                for (int x = left; x <= right; x++) {
                    gray[y * WIDTH + x] = 0;
                    if (label != 0) labels[y * WIDTH + x] = label;
                }
        }
    }
}
