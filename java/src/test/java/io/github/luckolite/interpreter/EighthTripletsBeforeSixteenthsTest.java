// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class EighthTripletsBeforeSixteenthsTest {
    private static final int WIDTH = 680;
    private static final int HEIGHT = 370;
    private static final int GAP = 16;
    private static final int BEFORE_Y = 73;
    private static final int TARGET_Y = 243;
    private static final int FIRST_X = 61;
    private static final int SPACING = 40;

    @Test
    public void assignsOnlyTheTwoTripletGroupsBeforeWrittenSixteenths() {
        var sheet = fixture(4);
        var timing =
                EighthTripletsBeforeSixteenths.capture(sheet.score(), sheet.ink(), WIDTH, HEIGHT)
                        .score();
        assertEquals(26, timing.notes().size());
        for (int i = 0; i < timing.notes().size(); i++) {
            var original = sheet.score().notes().get(i);
            var expected = i >= 12 && i < 18 ? original.withTupletRatio(3, 2) : original;
            assertEquals("owner " + i, expected, timing.notes().get(i));
        }
        var placements = ScorePlacementMap.resolveWritten(timing.notes(), 4, timing.meterChanges());
        for (int i = 0; i < 14; i++) {
            assertEquals(
                    i < 6 ? i / 3.0 : 2 + (i - 6) / 4.0,
                    placements.get(12 + i).onsetBeats(),
                    .0001);
            assertEquals(i < 6 ? 1.0 / 3 : .25, placements.get(12 + i).durationBeats(), .0001);
        }
    }

    @Test
    public void preservesVerifiedTripletsInThePreviousBarAndStillAppliesTheTransition() {
        var original = fixture(4);
        var testedNotes = new ArrayList<>(original.score().notes());
        for (int i = 0; i < 12; i++) testedNotes.set(i, testedNotes.get(i).withTupletRatio(3, 2));
        var owners = java.util.stream.IntStream.range(0, testedNotes.size()).boxed().toList();
        var prior = original.score().withNotes(testedNotes, owners);
        var result = EighthTripletsBeforeSixteenths.capture(prior, original.ink(), WIDTH, HEIGHT).score();
        assertEquals(testedNotes.subList(0, 12), result.notes().subList(0, 12));
        assertEquals(6, result.notes().subList(12, 26).stream()
                .filter(n -> n.tupletDivisor() == 3 && n.tupletNormalNotes() == 2).count());

        testedNotes.set(0, testedNotes.get(0).withTupletRatio(5, 4));
        var unsupported = original.score().withNotes(testedNotes, owners);
        assertSame(unsupported, EighthTripletsBeforeSixteenths.capture(
                unsupported, original.ink(), WIDTH, HEIGHT).score());
    }

    @Test
    public void requiresSeparateTripletGroupsAndOwnedSecondaryBeams() {
        var joined = fixture(4);
        var erased = fixture(4);
        int cut = FIRST_X + 2 * SPACING;
        draw(joined.ink(), cut + 1, TARGET_Y + 55, cut + SPACING - 12, TARGET_Y + 62, 0);
        int firstSixteenth = FIRST_X + 6 * SPACING;
        draw(
                erased.ink(),
                firstSixteenth + 3,
                TARGET_Y + 40,
                firstSixteenth + 21,
                TARGET_Y + 54,
                255);
        assertSame(
                joined.score(),
                EighthTripletsBeforeSixteenths.capture(joined.score(), joined.ink(), WIDTH, HEIGHT)
                        .score());
        assertSame(
                erased.score(),
                EighthTripletsBeforeSixteenths.capture(erased.score(), erased.ink(), WIDTH, HEIGHT)
                        .score());
    }

    @Test
    public void leavesAnOtherwiseIdenticalBarInAnotherMeterUntouched() {
        var sheet = fixture(3);
        assertSame(
                sheet.score(),
                EighthTripletsBeforeSixteenths.capture(sheet.score(), sheet.ink(), WIDTH, HEIGHT)
                        .score());
    }

    @Test
    public void projectsLiteralRunsAfterOrdinaryCapture() {
        var sheet = fixture(4);
        var proof =
                EighthTripletsBeforeSixteenths.capture(sheet.score(), sheet.ink(), WIDTH, HEIGHT);
        var earlier = group(0, 1);
        var regular =
                proof.score()
                        .withBeamSource(
                                ScoreBeamSource.capture(
                                        3,
                                        proof.score().notes().size(),
                                        List.of(earlier),
                                        List.of()));
        var projected = proof.projectBeams(regular, 3);
        assertEquals(regular.notes(), projected.notes());
        var found = projected.beamSource().groups();
        assertEquals(3, found.size());
        assertEquals(earlier, found.get(0));
        assertEquals(
                List.of(4, 4), found.subList(1, 3).stream().map(g -> g.columns().size()).toList());
        assertEquals(
                List.of(List.of(3, 3, 3), List.of(3, 3, 3)),
                found.subList(1, 3).stream().map(ScoreBeamSource.Group::bridges).toList());
        assertEquals(
                List.of(18, 22),
                found.subList(1, 3).stream()
                        .map(g -> g.columns().get(0).members().get(0).noteIndex())
                        .toList());
        assertSame(projected, proof.projectBeams(projected, 3));
        assertSame(regular, proof.projectBeams(regular, 4));
        assertSame(proof.score(), proof.projectBeams(proof.score(), 3));
    }

    @Test
    public void doesNotClaimAbsentAmbiguousOrPreviouslyOwnedStems() {
        var sheet = fixture(4);
        var proof =
                EighthTripletsBeforeSixteenths.capture(sheet.score(), sheet.ink(), WIDTH, HEIGHT);
        var score = proof.score();
        var occupied =
                score.withBeamSource(
                        ScoreBeamSource.capture(
                                3, score.notes().size(), List.of(), List.of(column(25))));
        assertSame(occupied, proof.projectBeams(occupied, 3));
        var valid = ScoreBeamSource.capture(3, score.notes().size(), List.of(), List.of());
        var origins = new ArrayList<>(valid.noteOrigins());
        origins.set(11, origins.get(18));
        var ambiguous = score.withBeamSource(new ScoreBeamSource(List.of(), List.of(), origins));
        assertSame(ambiguous, proof.projectBeams(ambiguous, 3));
        var changedNotes = new ArrayList<>(score.notes());
        changedNotes.set(18, changedNotes.get(18).withStemDirection(1));
        var changed =
                score.withNotes(
                                changedNotes,
                                java.util.stream.IntStream.range(0, changedNotes.size())
                                        .boxed()
                                        .toList())
                        .withBeamSource(valid);
        assertSame(changed, proof.projectBeams(changed, 3));
    }

    private static ScoreBeamSource.Group group(int a, int b) {
        return new ScoreBeamSource.Group(
                "earlier", List.of(column(a), column(b)), List.of(1), List.of());
    }

    private static ScoreBeamSource.Column column(int index) {
        return new ScoreBeamSource.Column(List.of(new ScoreBeamSource.Anchor(3, index)));
    }

    private record Fixture(ScorePageInterpretation score, byte[] ink) {}

    private static Fixture fixture(int numerator) {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < 12; i++) addNote(ink, notes, 0, i, BEFORE_Y, 1);
        for (int i = 0; i < 14; i++) addNote(ink, notes, 1, i, TARGET_Y, i < 6 ? 1 : 2);
        for (int i = 0; i < 12; i += 3) beam(ink, i, 3, BEFORE_Y, 1);
        for (int i = 0; i < 6; i += 3) beam(ink, i, 3, TARGET_Y, 1);
        for (int i = 6; i < 14; i += 4) beam(ink, i, 4, TARGET_Y, 2);
        var before = new MeasureRegion(34f / WIDTH, 514f / WIDTH, 39f / HEIGHT, 170f / HEIGHT);
        var target = new MeasureRegion(34f / WIDTH, 614f / WIDTH, 209f / HEIGHT, 340f / HEIGHT);
        return new Fixture(
                new ScorePageInterpretation(
                        List.of(before, target),
                        notes,
                        0,
                        List.of(),
                        List.of(),
                        List.of(new ScoreMeterChange(0, numerator, 4))),
                ink);
    }

    private static void addNote(
            byte[] ink, List<ScoreNoteEvent> notes, int measure, int index, int topY, int beams) {
        int x = FIRST_X + index * SPACING;
        int y = topY + (index % 3) * (GAP / 2);
        for (int dy = -6; dy <= 6; dy++)
            for (int dx = -10; dx <= 10; dx++)
                if (dx * dx + 2 * dy * dy <= 100) ink[(y + dy) * WIDTH + x + dx] = 0;
        draw(ink, x - 10, y, x - 8, topY + 62, 0);
        float pos = (x - 34f) / (measure == 0 ? 480f : 580f);
        notes.add(
                new ScoreNoteEvent(
                                measure,
                                pos,
                                10 - index % 3,
                                0,
                                1,
                                y / (float) HEIGHT,
                                false,
                                0,
                                beams,
                                ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                                0)
                        .withClef(30)
                        .withStemDirection(-1));
    }

    private static void beam(byte[] ink, int first, int groupSize, int y, int rails) {
        int left = FIRST_X + first * SPACING - 10;
        int right = left + (groupSize - 1) * SPACING + 2;
        draw(ink, left, y + 54, right, y + 62, 0);
        if (rails == 2) draw(ink, left, y + 41, right, y + 49, 0);
    }

    private static void draw(byte[] ink, int left, int top, int right, int bottom, int tone) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(ink, y * WIDTH + left, y * WIDTH + right + 1, (byte) tone);
    }
}
