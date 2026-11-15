// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SplitSharpSourceOwnershipTest {
    private static final int WIDTH = 120, HEIGHT = 112;
    private static final byte HEAD = OmrMeasurePostProcessor.NOTEHEAD;
    private static final byte ACCIDENTAL = OmrMeasurePostProcessor.CLEF_OR_KEY;
    private static final SourceLocalAccidental.AccidentalStrips UPPER =
            new SourceLocalAccidental.AccidentalStrips(1, 40, 52, 3, 25, 27, 37, 39);
    private static final SourceLocalAccidental.AccidentalStrips LOWER =
            new SourceLocalAccidental.AccidentalStrips(1, 40, 52, 3, 68, 70, 80, 82);

    @Test
    public void separatelyOwnedStripsEmitOneSharpPerRetainedHead() throws Exception {
        var fixture = new Fixture(false, false);
        var source = fixture.capture(UPPER, LOWER);
        assertEquals(
                List.of(
                        new SourceLocalAccidental.PrintedMark(0, 1),
                        new SourceLocalAccidental.PrintedMark(1, 1)),
                source.printedMarks(fixture.notes, fixture.notes));
        assertEquals(
                List.of(new SourceLocalAccidental.PrintedMark(0, 1)),
                source.printedMarks(fixture.notes, fixture.notes.subList(0, 1)));
    }

    @Test
    public void witnessedStripsMustHaveIndependentExactParentMembership() throws Exception {
        var fixture = new Fixture(true, false);
        assertTrue(
                fixture.capture(UPPER, LOWER).printedMarks(fixture.notes, fixture.notes).isEmpty());
        var intact = new Fixture(false, false);
        assertTrue(intact.capture(UPPER, UPPER).printedMarks(intact.notes, intact.notes).isEmpty());
    }

    @Test
    public void aThirdHeadClaimOnTheMergedComponentRefusesBothExtraMarks() throws Exception {
        var fixture = new Fixture(false, true);
        assertTrue(
                fixture.capture(UPPER, LOWER).printedMarks(fixture.notes, fixture.notes).isEmpty());
    }

    @Test
    public void anUnresolvedPickerConflictRefusesSplitMarks() throws Exception {
        var fixture = new Fixture(false, false);
        fixture.pickerChoice(0, null, true);
        assertTrue(
                fixture.capture(UPPER, LOWER).printedMarks(fixture.notes, fixture.notes).isEmpty());
    }

    @Test
    public void conflictingRepeatedWitnessesRefuseBothExtraMarks() throws Exception {
        var fixture = new Fixture(false, false);
        var narrowed = new SourceLocalAccidental.AccidentalStrips(1, 40, 52, 3, 25, 27, 37, 38);
        var source =
                fixture.capture(
                        List.of(fixture.split(UPPER, LOWER), fixture.split(narrowed, LOWER)));
        assertTrue(source.printedMarks(fixture.notes, fixture.notes).isEmpty());
    }

    @Test
    public void retainsTheNaturalTypeWhenTheOtherHeadOwnsASharp() throws Exception {
        var fixture = new Fixture(false, false, ScoreNoteEvent.ACCIDENTAL_NATURAL);
        var ink =
                new SourceLocalAccidental.AccidentalStrips(
                        ScoreNoteEvent.ACCIDENTAL_NATURAL, 40, 52, 3, 68, 70, 80, 82);
        var source = fixture.capture(UPPER, ink);
        assertEquals(
                List.of(
                        new SourceLocalAccidental.PrintedMark(0, 1),
                        new SourceLocalAccidental.PrintedMark(1, 0)),
                source.printedMarks(fixture.notes, fixture.notes));
        assertTrue(
                fixture.capture(UPPER, LOWER).printedMarks(fixture.notes, fixture.notes).isEmpty());
    }

    private static final class Fixture {
        final byte[] labels = new byte[WIDTH * HEIGHT], gray = new byte[WIDTH * HEIGHT];
        final ExactComponentMembers.Collector heads, clefs, symbols;
        final List<?> headMembers, glyphs;
        final List<ScoreNoteEvent> notes;
        final Map<Object, OmrScoreInterpreter.WrittenAccidentalChoice> choices =
                new IdentityHashMap<>();

        Fixture(boolean stripRemoved, boolean borrowed) throws Exception {
            this(stripRemoved, borrowed, ScoreNoteEvent.ACCIDENTAL_SHARP);
        }

        Fixture(boolean stripRemoved, boolean borrowed, int lowerKind) throws Exception {
            Arrays.fill(gray, (byte) 255);
            square(62, 33, HEAD);
            square(62, 76, HEAD);
            square(93, 96, HEAD);
            for (int y = 22; y <= 87; y++) {
                mark(43, y, ACCIDENTAL);
                mark(49, y, ACCIDENTAL);
            }
            stripe(25);
            stripe(37);
            stripe(68);
            if (!stripRemoved) stripe(80);
            heads = OmrScoreInterpreter.tremoloMemberCollector(gray, labels, WIDTH, HEIGHT);
            clefs = OmrScoreInterpreter.tremoloMemberCollector(gray, labels, WIDTH, HEIGHT);
            symbols = OmrScoreInterpreter.tremoloMemberCollector(gray, labels, WIDTH, HEIGHT);
            headMembers = components(HEAD, heads);
            glyphs = components(ACCIDENTAL, clefs);
            components(OmrMeasurePostProcessor.SYMBOL, symbols);
            assertEquals(3, headMembers.size());
            assertEquals(1, glyphs.size());
            notes =
                    List.of(
                            note(33, 1),
                            note(76, lowerKind),
                            note(96, ScoreNoteEvent.ACCIDENTAL_FROM_KEY));
            for (int index = 0; index < headMembers.size(); index++)
                pickerChoice(index, borrowed && index == 2 ? glyphs.get(0) : null, false);
        }

        void pickerChoice(int index, Object glyph, boolean ambiguous) throws Exception {
            var head = headMembers.get(index);
            var constructor =
                    OmrScoreInterpreter.WrittenAccidentalChoice.class.getDeclaredConstructor(
                            int.class,
                            Object.class,
                            Object.class,
                            int.class,
                            int.class,
                            boolean.class);
            constructor.setAccessible(true);
            choices.put(
                    head,
                    constructor.newInstance(
                            ScoreNoteEvent.ACCIDENTAL_FROM_KEY, glyph, head, 0, 0, ambiguous));
        }

        SourceLocalAccidental.SplitAccidental split(
                SourceLocalAccidental.AccidentalStrips upper,
                SourceLocalAccidental.AccidentalStrips lower) {
            return new SourceLocalAccidental.SplitAccidental(
                    glyphs.get(0), headMembers.get(0), headMembers.get(1), upper, lower);
        }

        SourceLocalAccidental capture(
                SourceLocalAccidental.AccidentalStrips upper,
                SourceLocalAccidental.AccidentalStrips lower) {
            return capture(List.of(split(upper, lower)));
        }

        SourceLocalAccidental capture(List<SourceLocalAccidental.SplitAccidental> proofs) {
            return SourceLocalAccidental.capture(
                    symbols,
                    clefs,
                    glyphs,
                    headMembers,
                    heads,
                    choices,
                    headMembers,
                    notes,
                    Map.of(),
                    Set.of(),
                    proofs);
        }

        List<?> components(byte label, ExactComponentMembers.Collector collector) throws Exception {
            Method detector =
                    OmrScoreInterpreter.class.getDeclaredMethod(
                            "findComponents",
                            byte[].class,
                            int.class,
                            int.class,
                            byte.class,
                            byte[].class,
                            IdentityHashMap.class,
                            ExactComponentMembers.Collector.class);
            detector.setAccessible(true);
            var found =
                    (List<?>)
                            detector.invoke(
                                    null, labels, WIDTH, HEIGHT, label, gray, null, collector);
            assertTrue(
                    collector.seal(
                            new ExactComponentMembers.Budget(2L * labels.length + 4096, 4096)));
            return found;
        }

        void stripe(int y0) {
            for (int center = y0; center <= y0 + 2; center++)
                for (int x = 40; x <= 52; x++)
                    mark(x, Math.round(center - .3f * (x - 46)), ACCIDENTAL);
        }

        void square(int x, int y, byte type) {
            for (int row = y - 4; row <= y + 4; row++)
                for (int column = x - 5; column <= x + 5; column++) mark(column, row, type);
        }

        void mark(int x, int y, byte type) {
            labels[y * WIDTH + x] = type;
            gray[y * WIDTH + x] = 0;
        }

        ScoreNoteEvent note(int row, int accidental) {
            return new ScoreNoteEvent(
                    0,
                    .55f,
                    (85 - row) / 8,
                    0,
                    1,
                    row / (float) HEIGHT,
                    false,
                    1,
                    2,
                    accidental,
                    0f,
                    1,
                    0f,
                    0,
                    30,
                    false,
                    0f,
                    false,
                    1,
                    0,
                    1,
                    -1);
        }
    }
}
