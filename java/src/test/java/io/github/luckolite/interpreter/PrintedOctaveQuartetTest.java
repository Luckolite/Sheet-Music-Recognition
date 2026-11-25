// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class PrintedOctaveQuartetTest {
    private static final int WIDTH = 760, HEIGHT = 216, GAP = 16;

    @Test
    public void retainsFourIndependentlySeparatedTwoRailGroups() {
        var fixture = fixture();
        var score = recognize(fixture, fixture.gray.clone());
        assertEquals(fixture.score.notes(), score.notes());
        assertEquals(4, score.beamSource().groups().size());
        for (var group : score.beamSource().groups()) {
            assertEquals(List.of(3, 3, 3), group.bridges());
            assertEquals(List.of(2, 2, 2, 2), group.columns().stream().map(c -> c.members().size()).toList());
        }
        var existing = score.beamSource();
        assertSame(score, ClosedOctaveRailRun.completePrintedBeamQuartets(
                score, fixture.gray, fixture.gray.clone(), WIDTH, HEIGHT, fixture.staffs, 0));
        assertEquals(4, existing.groups().size());
    }

    @Test
    public void requiresBothRailsAndAClosedBoundaryInTheOriginalPlane() {
        var fixture = fixture();
        byte[] cut = fixture.gray.clone();
        fill(cut, 40, 163, 53, 173, 255);
        assertTrue(recognize(fixture, cut).beamSource().groups().isEmpty());
        byte[] joined = fixture.gray.clone();
        fill(joined, 176, 177, 220, 183, 0);
        assertTrue(recognize(fixture, joined).beamSource().groups().isEmpty());
        byte[] third = fixture.gray.clone();
        fill(third, 40, 147, 84, 153, 0);
        assertTrue(recognize(fixture, third).beamSource().groups().isEmpty());
    }

    @Test
    public void removesOnlyAStemlessAttackInsideAnIndependentlyProvedBeam() {
        var fixture = fixture();
        var notes = new ArrayList<>(fixture.score.notes());
        var fragment = new ScoreNoteEvent(0, 68f / WIDTH, 0, 0, 1, 171f / HEIGHT, false, 0, 1)
                .withClef(ScoreNoteEvent.CLEF_TREBLE);
        notes.add(2, fragment);
        var withFragment = new Fixture(new ScorePageInterpretation(fixture.score.measures(), notes),
                fixture.gray, fixture.staffs);
        assertEquals(fixture.score.notes(), recognize(withFragment, fixture.gray.clone()).notes());

        byte[] shaft = fixture.gray.clone();

        fill(shaft, 58, 135, 60, 183, 0);
        assertEquals(notes, recognize(withFragment, shaft).notes());
        byte[] body = fixture.gray.clone();
        oval(body, 68, 172);
        assertEquals(notes, recognize(withFragment, body).notes());
    }
    @Test
    public void completesACompatiblePartialGroupAndKeepsIndependentEvidence() {
        var fixture = fixture();
        var partial = new ScoreBeamSource.Group("partial",
                List.of(column(0, 1), column(2), column(4, 5)), List.of(1, 3), List.of());
        var overlapping = new ScoreBeamSource.Group("proved",
                List.of(column(8, 9), column(10, 11)), List.of(3),
                List.of(new ScoreBeamSource.Hook(1, 3, ScoreBeamSource.Direction.BACKWARD)));
        var source = ScoreBeamSource.capture(0, fixture.score.notes().size(),
                List.of(partial, overlapping), List.of());
        var withSource = new Fixture(fixture.score.withBeamSource(source), fixture.gray, fixture.staffs);
        var groups = recognize(withSource, fixture.gray.clone()).beamSource().groups();
        assertEquals(4, groups.size());
        assertTrue(groups.contains(overlapping));
        assertTrue(!groups.contains(partial));
    }

    private static ScoreBeamSource.Column column(int... owners) {
        return new ScoreBeamSource.Column(Arrays.stream(owners)
                .mapToObj(i -> new ScoreBeamSource.Anchor(0, i)).toList());
    }

    @Test
    public void ignoresAnApparentBridgeOnlyWithFiveIndependentlyWitnessedRules() {
        byte[] staff = new byte[WIDTH * HEIGHT];
        Arrays.fill(staff, (byte) 255);
        for (int y = 80; y <= 144; y += GAP) fill(staff, 8, y - 2, 110, y + 1, 0);
        var left = new PrintedBeamShafts.Shaft(39, 41, 110, 147, 1, List.of());
        var right = new PrintedBeamShafts.Shaft(81, 83, 110, 147, 1, List.of());
        assertTrue(WrappedEighthTriplets.matchesBeamRails(staff, WIDTH, HEIGHT, left, right, GAP, 2));
        assertTrue(WrappedEighthTriplets.separatedOctaveQuartets(
                staff, WIDTH, HEIGHT, left, right, GAP, 144));

        byte[] joined = staff.clone();
        fill(joined, 40, 141, 82, 148, 0);
        assertTrue(!WrappedEighthTriplets.separatedOctaveQuartets(
                joined, WIDTH, HEIGHT, left, right, GAP, 144));

        byte[] doubled = joined.clone();
        fill(doubled, 40, 125, 82, 133, 0);
        assertTrue(WrappedEighthTriplets.matchesBeamRails(doubled, WIDTH, HEIGHT, left, right, GAP, 2));
        assertTrue(!WrappedEighthTriplets.separatedOctaveQuartets(
                doubled, WIDTH, HEIGHT, left, right, GAP, 144));

        byte[] partial = staff.clone();
        fill(partial, 47, 95, 75, 97, 255);
        assertTrue(!WrappedEighthTriplets.separatedOctaveQuartets(
                partial, WIDTH, HEIGHT, left, right, GAP, 144));
    }

    @Test
    public void preservesOctaveShiftValuesOnIndependentlyProvedGroups() {
        var fixture = fixture();
        var notes = new ArrayList<>(fixture.score.notes());
        for (int i = 0; i < 8; i++) notes.set(i, notes.get(i).withOctaveShift(1));
        var shifted = new Fixture(new ScorePageInterpretation(fixture.score.measures(), notes),
                fixture.gray, fixture.staffs);
        var result = recognize(shifted, fixture.gray.clone());
        assertEquals(notes, result.notes());
        assertEquals(4, result.beamSource().groups().size());
    }

    @Test
    public void recoversThreeSeparatedQuartetsInAThreeBeatBar() {
        var fixture = fixture(3);
        var result = recognize(fixture, fixture.gray.clone());
        assertEquals(3, result.beamSource().groups().size());
        assertEquals(fixture.score.notes(), result.notes());
        assertEquals(List.of(new ScoreMeterChange(0, 3, 4)), result.meterChanges());

        byte[] joined = fixture.gray.clone();
        fill(joined, 176, 177, 220, 183, 0);
        assertTrue(recognize(fixture, joined).beamSource().groups().isEmpty());
    }

    @Test
    public void preservesARealShortThirdHookWhenOneHeadReportsThreeBeams() {
        var fixture = fixture();
        var notes = new ArrayList<>(fixture.score.notes());
        var first = notes.get(1);
        notes.set(1, new ScoreNoteEvent(0, first.positionInMeasure(), first.staffStep(), 0, 1,
                first.pageY(), false, 0, 3).withStemDirection(-1).withClef(ScoreNoteEvent.CLEF_TREBLE));
        var overcount = new Fixture(new ScorePageInterpretation(fixture.score.measures(), notes),
                fixture.gray, fixture.staffs);
        assertEquals(4, recognize(overcount, fixture.gray.clone()).beamSource().groups().size());
        byte[] hooked = fixture.gray.clone();
        fill(hooked, 40, 147, 55, 153, 0);
        assertTrue(recognize(overcount, hooked).beamSource().groups().isEmpty());
    }

    @Test
    public void completesAnOctaveShaftAtTheLowerHeadAndRejectsASeparateStem() {
        var fixture = fixture();
        byte[] clipped = fixture.gray.clone();
        fill(clipped, 40, 116, 42, 123, 255);
        assertEquals(4, recognize(fixture, clipped).beamSource().groups().size());
        byte[] separate = clipped.clone();
        fill(separate, 40, 91, 42, 114, 255);
        assertTrue(recognize(fixture, separate).beamSource().groups().isEmpty());
    }

    @Test
    public void closesQuartetsBeforeAnIndependentHalfOrQuarterDyad() {
        for (int quartets : new int[] {2, 3}) {
            var source = fixture(quartets);
            int x = 50 + quartets * 4 * 42;
            var notes = new ArrayList<>(source.score.notes());
            byte[] ink = source.gray.clone();
            for (int y : new int[] {64, 120}) {
                oval(ink, x, y);
                if (quartets == 2) fill(ink, x - 4, y - 3, x + 4, y + 3, 255);
                notes.add(new ScoreNoteEvent(0, x / (float) WIDTH, (144 - y) / 8, 0, 1,
                        y / (float) HEIGHT, false, 0, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                        4 - quartets).withStemDirection(-1).withClef(ScoreNoteEvent.CLEF_TREBLE));
            }
            fill(ink, x - 10, 67, x - 8, 183, 0);
            var fixture = new Fixture(new ScorePageInterpretation(source.score.measures(), notes),
                    ink, source.staffs);
            var result = recognize(fixture, ink.clone());
            assertEquals(quartets, result.beamSource().groups().size());
            assertEquals(notes, result.notes());
            assertTrue(result.beamSource().noteOrigins().get(notes.size() - 2).isEmpty());
            assertTrue(result.beamSource().noteOrigins().get(notes.size() - 1).isEmpty());

            byte[] joined = ink.clone();
            fill(joined, x - 42 - 8, 177, x - 10, 183, 0);
            assertTrue(recognize(fixture, joined).beamSource().groups().isEmpty());
        }
    }

    private static ScorePageInterpretation recognize(Fixture fixture, byte[] raw) {
        return ClosedOctaveRailRun.completePrintedBeamQuartets(
                fixture.score, fixture.gray, raw, WIDTH, HEIGHT, fixture.staffs, 0);
    }

    private record Fixture(ScorePageInterpretation score, byte[] gray, List<PlayingTechniqueDetector.Staff> staffs) {}

    private static Fixture fixture() {
        return fixture(4);
    }

    private static Fixture fixture(int quartets) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = 80; y <= 144; y += GAP) fill(gray, 8, y, WIDTH - 9, y + 1, 0);
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int slot = 0; slot < quartets * 4; slot++) {
            int x = 50 + slot * 42;
            for (int y : new int[] {64, 120}) {
                oval(gray, x, y);
                notes.add(new ScoreNoteEvent(0, x / (float) WIDTH, (144 - y) / 8,
                        0, 1, y / (float) HEIGHT, false, 0, 2).withStemDirection(-1).withClef(ScoreNoteEvent.CLEF_TREBLE));
            }
            fill(gray, x - 10, 67, x - 8, 183, 0);
        }
        for (int slot = 0; slot < quartets * 4; slot += 4) {
            int first = 40 + slot * 42, last = first + 3 * 42 + 2;
            fill(gray, first, 162, last, 168, 0);
            fill(gray, first, 177, last, 183, 0);
        }
        return new Fixture(new ScorePageInterpretation(List.of(new MeasureRegion(0, 1, 0, 1)), notes,
                        1, List.of(), List.of(), List.of(new ScoreMeterChange(0, quartets, 4)),
                        List.of(), List.of(), List.of()),
                gray, List.of(new PlayingTechniqueDetector.Staff(80, 144, 16, 0, 1)));
    }

    private static void oval(byte[] ink, int x, int y) {
        for (int dy = -8; dy <= 8; dy++)
            for (int dx = -11; dx <= 11; dx++)
                if (dx * dx / 121f + dy * dy / 64f <= 1) ink[(y + dy) * WIDTH + x + dx] = 0;
    }

    private static void fill(byte[] ink, int x0, int y0, int x1, int y1, int shade) {
        for (int y = y0; y <= y1; y++) Arrays.fill(ink, y * WIDTH + x0, y * WIDTH + x1 + 1, (byte) shade);
    }
}
