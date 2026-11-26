// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public final class MultiMeasureRestDetectorTest {
    @Test
    public void ledgerHeadsAboveStaffPreventBeamBecomingRest() {
        assertLedgerMeasureIsWritten(43);
    }

    @Test
    public void ledgerHeadsBelowStaffPreventBeamBecomingRest() {
        assertLedgerMeasureIsWritten(109);
    }

    private static void assertLedgerMeasureIsWritten(int headY) {
        int width = 240, height = 160;
        byte[] labels = new byte[width * height];
        byte[] gray = new byte[width * height];
        java.util.Arrays.fill(gray, (byte) 255);
        MeasureRegion written = new MeasureRegion(.10f, .90f, .375f, .60f);
        addHorizontalBar(labels, gray, width, 92, 150, 74, 79);
        addNotehead(labels, width, 105, headY);
        addNotehead(labels, width, 135, headY);
        assertEquals(
                List.of(),
                MultiMeasureRestDetector.candidateMeasureIndexes(
                        labels, gray, width, height, List.of(written)));
    }

    @Test
    public void adjacentStaffNotesDoNotSuppressSilentStaffRest() {
        int width = 240, height = 200;
        byte[] labels = new byte[width * height];
        byte[] gray = new byte[width * height];
        java.util.Arrays.fill(gray, (byte) 255);
        MeasureRegion upper = new MeasureRegion(.10f, .90f, .15f, .35f);
        MeasureRegion silent = new MeasureRegion(.10f, .90f, .45f, .65f);
        addNotehead(labels, width, 105, 65);
        addHorizontalBar(labels, gray, width, 92, 150, 106, 111);
        assertEquals(
                List.of(1),
                MultiMeasureRestDetector.candidateMeasureIndexes(
                        labels, gray, width, height, List.of(upper, silent)));
    }

    @Test
    public void distinguishesFourMeasureRestFromTripletAndSystemNumber() {
        int width = 200, height = 100;
        byte[] labels = new byte[width * height];
        byte[] gray = new byte[width * height];
        java.util.Arrays.fill(gray, (byte) 255);
        List<MeasureRegion> measures =
                List.of(
                        region(.10f, .38f), region(.39f, .55f),
                        region(.56f, .72f), region(.73f, .90f));
        addNotehead(labels, width, 40, 50);
        addNotehead(labels, width, 126, 50);
        addNotehead(labels, width, 162, 50);
        addHorizontalBar(labels, gray, width, 82, 106, 48, 52);
        MeasureNumberReconciler.NumberToken systemNumber = token(16, .06f, .42f);
        MeasureNumberReconciler.NumberToken triplet = token(3, .20f, .42f);
        MeasureNumberReconciler.NumberToken restCount = token(4, .47f, .42f);

        List<MeasureNumberReconciler.NumberToken> result =
                MultiMeasureRestDetector.detect(
                        labels,
                        gray,
                        width,
                        height,
                        measures,
                        List.of(systemNumber, triplet, restCount));

        assertEquals(List.of(restCount), result);
    }

    @Test
    public void precedingWrittenRowOwnsItsTupletNumber() {
        int width = 240, height = 300;
        byte[] labels = new byte[width * height];
        byte[] gray = new byte[width * height];
        java.util.Arrays.fill(gray, (byte) 255);
        MeasureRegion written = new MeasureRegion(.10f, .90f, .20f, .40f);
        MeasureRegion silent = new MeasureRegion(.10f, .90f, .50f, .85f);
        addNotehead(labels, width, 105, 100);
        addHorizontalBar(labels, gray, width, 80, 160, 180, 185);
        var tuplet = token(7, .50f, .38f);
        var restCount = token(4, .50f, .47f);
        assertEquals(
                List.of(restCount),
                MultiMeasureRestDetector.detect(
                        labels,
                        gray,
                        width,
                        height,
                        List.of(written, silent),
                        List.of(tuplet, restCount)));
    }

    @Test
    public void precedingRowTupletAloneCannotExpandRest() {
        int width = 240, height = 300;
        byte[] labels = new byte[width * height];
        byte[] gray = new byte[width * height];
        java.util.Arrays.fill(gray, (byte) 255);
        MeasureRegion written = new MeasureRegion(.10f, .90f, .20f, .40f);
        MeasureRegion silent = new MeasureRegion(.10f, .90f, .50f, .85f);
        addNotehead(labels, width, 105, 100);
        addHorizontalBar(labels, gray, width, 80, 160, 180, 185);
        assertEquals(
                List.of(),
                MultiMeasureRestDetector.detect(
                        labels,
                        gray,
                        width,
                        height,
                        List.of(written, silent),
                        List.of(token(7, .50f, .38f))));
    }

    @Test
    public void rejectsBareNumberWithoutThickRestBar() {
        int width = 200, height = 100;
        byte[] labels = new byte[width * height];
        byte[] gray = new byte[width * height];
        java.util.Arrays.fill(gray, (byte) 255);
        MeasureNumberReconciler.NumberToken token = token(4, .47f, .42f);

        assertEquals(
                List.of(),
                MultiMeasureRestDetector.detect(
                        labels, gray, width, height, List.of(region(.39f, .55f)), List.of(token)));
    }

    @Test
    public void acceptsRestCountPrintedJustAboveTheStaffRegion() {
        int width = 200, height = 100;
        byte[] labels = new byte[width * height];
        byte[] gray = new byte[width * height];
        java.util.Arrays.fill(gray, (byte) 255);
        MeasureRegion region = region(.39f, .70f);
        addHorizontalBar(labels, gray, width, 88, 132, 48, 52);
        MeasureNumberReconciler.NumberToken countAbove = token(4, .55f, .36f);

        assertEquals(
                List.of(countAbove),
                MultiMeasureRestDetector.detect(
                        labels, gray, width, height, List.of(region), List.of(countAbove)));
    }

    @Test
    public void writtenMeasureWithNotesCannotBecomeAMultiMeasureRest() {
        int width = 240, height = 120;
        byte[] labels = new byte[width * height];
        byte[] gray = new byte[width * height];
        java.util.Arrays.fill(gray, (byte) 255);
        MeasureRegion written = new MeasureRegion(.10f, .90f, .38f, .72f);
        // A long beam/line occupies a locally empty part of an otherwise written measure.
        addHorizontalBar(labels, gray, width, 92, 150, 62, 67);
        addNotehead(labels, width, 42, 68);
        MeasureNumberReconciler.NumberToken timeSignature = token(4, .50f, .34f);

        assertEquals(
                List.of(),
                MultiMeasureRestDetector.detect(
                        labels, gray, width, height, List.of(written), List.of(timeSignature)));
    }

    @Test
    public void exposesRestBarForFocusedOcrWhenWholePageOcrMissesItsNumber() {
        int width = 200, height = 100;
        byte[] labels = new byte[width * height];
        byte[] gray = new byte[width * height];
        java.util.Arrays.fill(gray, (byte) 255);
        List<MeasureRegion> measures = List.of(region(.10f, .38f), region(.39f, .70f));
        addNotehead(labels, width, 40, 50);
        addHorizontalBar(labels, gray, width, 88, 132, 48, 52);

        assertEquals(
                List.of(1),
                MultiMeasureRestDetector.candidateMeasureIndexes(
                        labels, gray, width, height, measures));
    }

    @Test
    public void recognizesStandaloneFourOnlyAboveProvenRestBar() {
        int width = 200, height = 120;
        byte[] labels = new byte[width * height];
        byte[] gray = new byte[width * height];
        java.util.Arrays.fill(gray, (byte) 255);
        MeasureRegion region = new MeasureRegion(.35f, .75f, .50f, .90f);
        addHorizontalBar(labels, gray, width, 88, 132, 72, 77);
        // Open-top 4: diagonal left arm, middle crossbar, and full right spine.
        for (int y = 35; y <= 54; y++) gray[y * width + 116] = 0;
        for (int offset = 0; offset <= 10; offset++) gray[(35 + offset) * width + 106 + offset] = 0;
        for (int x = 106; x <= 116; x++) gray[46 * width + x] = 0;
        MultiMeasureRestDetector.RestBarCandidate candidate =
                new MultiMeasureRestDetector.RestBarCandidate(0, region);

        MeasureNumberReconciler.NumberToken token =
                MultiMeasureRestDetector.standaloneFour(gray, width, height, candidate);

        assertEquals(4, token.value());
    }

    @Test
    public void recognizesStandaloneTwoOnlyAboveProvenRestBar() {
        int width = 200, height = 120;
        byte[] gray = new byte[width * height];
        java.util.Arrays.fill(gray, (byte) 255);
        MeasureRegion region = new MeasureRegion(.35f, .75f, .50f, .90f);
        addHorizontalBar(new byte[width * height], gray, width, 88, 132, 72, 77);
        // Curved top/right, descending diagonal, and a broad baseline form a printed 2.
        for (int x = 103; x <= 117; x++) gray[36 * width + x] = 0;
        for (int y = 37; y <= 43; y++) gray[y * width + 117] = 0;
        for (int offset = 0; offset <= 13; offset++) gray[(43 + offset) * width + 117 - offset] = 0;
        for (int x = 103; x <= 117; x++) gray[56 * width + x] = 0;
        MultiMeasureRestDetector.RestBarCandidate candidate =
                new MultiMeasureRestDetector.RestBarCandidate(0, region);

        MeasureNumberReconciler.NumberToken token =
                MultiMeasureRestDetector.standaloneCount(gray, width, height, candidate);

        assertEquals(2, token.value());
    }

    @Test
    public void standaloneThreeCannotMasqueradeAsTwo() {
        int width = 200, height = 120;
        byte[] gray = new byte[width * height];
        java.util.Arrays.fill(gray, (byte) 255);
        MeasureRegion region = new MeasureRegion(.35f, .75f, .50f, .90f);
        for (int y = 36; y <= 56; y++) gray[y * width + 117] = 0;
        for (int y : new int[] {36, 46, 56})
            for (int x = 103; x <= 117; x++) gray[y * width + x] = 0;
        MultiMeasureRestDetector.RestBarCandidate candidate =
                new MultiMeasureRestDetector.RestBarCandidate(0, region);

        assertEquals(
                3,
                MultiMeasureRestDetector.standaloneCount(gray, width, height, candidate).value());
    }

    @Test
    public void centeredRestTwoWinsOverOpeningTimeSignatureFour() {
        int width = 240, height = 120;
        byte[] gray = new byte[width * height];
        java.util.Arrays.fill(gray, (byte) 255);
        MeasureRegion region = new MeasureRegion(.25f, .75f, .50f, .90f);
        // A four near the left edge represents the opening 4/4 signature.
        for (int y = 35; y <= 54; y++) gray[y * width + 82] = 0;
        for (int offset = 0; offset <= 10; offset++) gray[(35 + offset) * width + 72 + offset] = 0;
        for (int x = 72; x <= 82; x++) gray[46 * width + x] = 0;
        // The real rest count is centered over the heavy bar.
        for (int x = 113; x <= 127; x++) gray[36 * width + x] = 0;
        for (int y = 37; y <= 43; y++) gray[y * width + 127] = 0;
        for (int offset = 0; offset <= 13; offset++) gray[(43 + offset) * width + 127 - offset] = 0;
        for (int x = 113; x <= 127; x++) gray[56 * width + x] = 0;
        MultiMeasureRestDetector.RestBarCandidate candidate =
                new MultiMeasureRestDetector.RestBarCandidate(0, region);

        assertEquals(
                2,
                MultiMeasureRestDetector.standaloneCount(gray, width, height, candidate).value());
    }

    @Test
    public void recognizesHeavySerifSevenAboveRestBar() {
        int width = 220, height = 130;
        byte[] gray = new byte[width * height];
        java.util.Arrays.fill(gray, (byte) 255);
        MeasureRegion region = new MeasureRegion(.32f, .68f, .51f, .88f);
        addHorizontalBar(new byte[width * height], gray, width, 83, 137, 86, 90);
        for (int y = 34; y <= 38; y++)
            for (int x = 102; x <= 116; x++) gray[y * width + x] = 0;
        for (int y = 39; y <= 56; y++) {
            int start = 112 - (y - 39) / 3;
            for (int x = start; x <= start + 2; x++) gray[y * width + x] = 0;
        }
        gray[39 * width + 102] = 0;
        gray[40 * width + 102] = 0;
        var candidate = new MultiMeasureRestDetector.RestBarCandidate(0, region);

        assertEquals(7, MultiMeasureRestDetector.standaloneCount(gray, width, height, candidate).value());
    }

    @Test
    public void repeatedCountHeadsOnTwoLinkedStavesProveOneRest() {
        int width = 320, height = 230;
        byte[] labels = new byte[width * height];
        byte[] gray = new byte[width * height];
        java.util.Arrays.fill(gray, (byte) 255);
        var measure = new MeasureRegion(.20f, .70f, .25f, .83f);
        var counts = new java.util.ArrayList<MeasureNumberReconciler.NumberToken>();
        for (int staff : new int[] {70, 145}) {
            for (int line = 0; line < 5; line++) {
                int y = staff + line * 9;
                java.util.Arrays.fill(gray, y * width + 24, y * width + 300, (byte) 0);
                java.util.Arrays.fill(labels, y * width + 24, y * width + 300, OmrMeasurePostProcessor.STAFF);
            }
            addHorizontalBar(labels, gray, width, 88, 194, staff + 15, staff + 21);
            addNotehead(labels, width, 144, staff - 8);
            counts.add(new MeasureNumberReconciler.NumberToken(3, 137f / width,
                    (staff - 12f) / height, 151f / width, (staff - 4f) / height));
        }

        var admitted = MultiMeasureRestDetector.detect(labels, gray, width, height, List.of(measure), counts);

        assertEquals(List.of(counts.get(0)), admitted);
        addNotehead(labels, width, 111, 157);
        assertEquals(List.of(), MultiMeasureRestDetector.detect(labels, gray, width, height,
                List.of(measure), counts));
    }

    @Test
    public void recognizesBreveRestWithPrintedTwoAndRejectsOrdinaryRest() {
        int width = 320, height = 220;
        byte[] labels = new byte[width * height];
        byte[] gray = new byte[width * height];
        java.util.Arrays.fill(gray, (byte) 255);
        for (int y = 100; y <= 148; y += 12) {
            java.util.Arrays.fill(gray, y * width + 18, y * width + 300, (byte) 0);
            java.util.Arrays.fill(labels, y * width + 18, y * width + 300, OmrMeasurePostProcessor.STAFF);
        }
        var measure = new MeasureRegion(.25f, .70f, .42f, .73f);
        var count = new MeasureNumberReconciler.NumberToken(2, 147f / width, 84f / height,
                159f / width, 95f / height);
        addHorizontalBar(labels, gray, width, 150, 156, 113, 123);

        assertEquals(List.of(count), MultiMeasureRestDetector.detect(labels, gray, width, height,
                List.of(measure), List.of(count)));
        assertEquals(List.of(), MultiMeasureRestDetector.detect(labels, gray, width, height,
                List.of(measure), List.of()));
        for (int y = 118; y <= 123; y++) {
            java.util.Arrays.fill(gray, y * width + 150, y * width + 157, (byte) 255);
            java.util.Arrays.fill(labels, y * width + 150, y * width + 157, (byte) 0);
        }
        assertEquals(List.of(), MultiMeasureRestDetector.detect(labels, gray, width, height,
                List.of(measure), List.of(count)));
    }

    @Test
    public void followsLongSkewedRestBetweenItsTwoCaps() {
        int width = 500, height = 260;
        byte[] labels = new byte[width * height];
        byte[] gray = new byte[width * height];
        java.util.Arrays.fill(gray, (byte) 255);
        for (int x = 35; x < 470; x++) {
            int drift = (x - 230) / 42;
            for (int line = 0; line < 5; line++) {
                int i = (100 + line * 14 + drift) * width + x;
                gray[i] = 0;
                labels[i] = OmrMeasurePostProcessor.STAFF;
            }
        }
        for (int x = 90; x <= 370; x++) {
            int middle = 128 + (x - 230) / 42;
            int half = x <= 93 || x >= 367 ? 14 : 4;
            for (int y = middle - half; y <= middle + half; y++) gray[y * width + x] = 0;
        }
        var measure = new MeasureRegion(.15f, .80f, 72f / height, 187f / height);
        var count = new MeasureNumberReconciler.NumberToken(3, .445f, 78f / height, .475f, 97f / height);

        assertEquals(List.of(count), MultiMeasureRestDetector.detect(labels, gray, width, height,
                List.of(measure), List.of(count)));
        addNotehead(labels, width, 84, 116);
        assertEquals(List.of(), MultiMeasureRestDetector.detect(labels, gray, width, height,
                List.of(measure), List.of(count)));
    }

    private static MeasureRegion region(float left, float right) {
        return new MeasureRegion(left, right, .38f, .62f);
    }

    private static MeasureNumberReconciler.NumberToken token(int value, float x, float y) {
        return new MeasureNumberReconciler.NumberToken(
                value, x - .01f, y - .01f, x + .01f, y + .01f);
    }

    private static void addNotehead(byte[] labels, int width, int centerX, int centerY) {
        for (int y = centerY - 2; y <= centerY + 2; y++)
            for (int x = centerX - 3; x <= centerX + 3; x++)
                labels[y * width + x] = OmrMeasurePostProcessor.NOTEHEAD;
    }

    private static void addHorizontalBar(
            byte[] labels, byte[] gray, int width, int left, int right, int top, int bottom) {
        for (int y = top; y <= bottom; y++)
            for (int x = left; x <= right; x++) {
                labels[y * width + x] = OmrMeasurePostProcessor.STEM_OR_REST;
                gray[y * width + x] = 0;
            }
    }
}
