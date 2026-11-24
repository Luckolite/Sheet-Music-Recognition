// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class CadenceRailOwnershipTest {
    private static final int WIDTH = 510;
    private static final int HEIGHT = 280;
    private static final int LEFT = 38;
    private static final int RIGHT = 470;
    private static final int FLOOR = 158;
    private static final ScoreMeterChange METER = new ScoreMeterChange(0, 4, 4);

    @Test
    public void pairedRailsAndAHollowClosingHeadDisproveTheBorrowedEighth() {
        var fixture = cadence();
        var decoded = recover(fixture, fixture.ink());
        assertEquals(9, decoded.notes().size());
        assertEquals(List.of(2,2,2,2,2,2,2,2,0), decoded.notes().stream().map(ScoreNoteEvent::beamCount).toList());
        assertEquals(2, decoded.notes().get(decoded.notes().size() - 1).unbeamedDurationBeats(), 0);
        assertEquals(-1, decoded.notes().get(3).writtenAccidental());
    }

    @Test
    public void replacingTheHollowHeadWithASolidHeadPreservesTheOriginalDecode() {
        var fixture = cadence();
        var raw = fixture.ink().clone();
        rect(raw, 401, 411, 125, 132, 0);
        assertSame(fixture.score(), recover(fixture, raw));
    }

    private static ScorePageInterpretation recover(Fixture f, byte[] raw) {
        return ClosedMonophonicSixteenths.removeBorrowedRailEighth(f.score(), METER, f.ink(), raw, WIDTH, HEIGHT);
    }

    private static Fixture cadence() {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        for (int y = FLOOR - 64; y <= FLOOR; y += 16) rect(ink, LEFT, RIGHT + 20, y, y + 1, 0);
        rect(ink, LEFT, LEFT + 2, FLOOR - 64, FLOOR, 0);
        rect(ink, RIGHT - 1, RIGHT + 1, FLOOR - 64, FLOOR, 0);

        var notes = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < 8; i++) {
            int x = 70 + 42*i;
            int y = 135 - 3*(i%4);
            int shaft = x - 10;
            rect(ink, x - 9, x + 7, y - 4, y + 4, 0);
            rect(ink, shaft - 1, shaft + 1, y - 3, 221, 0);
            if (i%4 == 0) {
                rect(ink, shaft, shaft + 126, 202, 208, 0);
                rect(ink, shaft, shaft + 126, 215, 221, 0);
            }
            notes.add(note(x, y, -1, i == 3 ? 1 : 2, 0, i == 3 ? -1 : ScoreNoteEvent.ACCIDENTAL_FROM_KEY));
        }
        notes.add(3, note(179, 210, 1, 1, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY));
        for (int dy = -5; dy <= 5; dy++) {
            int extent = Math.max(2, (int)Math.floor(8 * Math.sqrt(1 - dy*dy / 36.0)));
            for (int dx = -extent; dx <= extent; dx++) {
                if (Math.abs(dy) >= 3 || Math.abs(dx)>=extent-1) ink[(128+dy)*WIDTH+406+dx]=0;
            }
        }
        rect(ink, 396, 398, 129, 185, 0);
        notes.add(note(406, 128, -1, 0, 2, ScoreNoteEvent.ACCIDENTAL_FROM_KEY));
        var bar = new MeasureRegion(LEFT/(float)WIDTH,RIGHT/(float)WIDTH,60f/HEIGHT,192f/HEIGHT);
        return new Fixture(new ScorePageInterpretation(List.of(bar),notes,0,List.of(),List.of(),List.of(),List.of()),ink);
    }

    private static ScoreNoteEvent note(int x, int y, int direction, int beams, int length, int accidental) {
        return new ScoreNoteEvent(0,(x-LEFT)/(float)(RIGHT-LEFT),Math.round((FLOOR-y)/8f),
                0,1,y/(float)HEIGHT,false,0,beams,accidental,length,1,0,0,ScoreNoteEvent.CLEF_TREBLE,
                false,0,false,1,0,1,direction,ScoreNoteEvent.Kind.PITCHED,ScoreNoteEvent.VisualSize.FULL,0,null);
    }

    private static void rect(byte[] px, int x0, int x1, int y0, int y1, int gray) {
        for (int y = y0; y <= y1; y++) Arrays.fill(px,y*WIDTH+x0,y*WIDTH+x1+1,(byte)gray);
    }

    private record Fixture(ScorePageInterpretation score, byte[] ink) {}
}
