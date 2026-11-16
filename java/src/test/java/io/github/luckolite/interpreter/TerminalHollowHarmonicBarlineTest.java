// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class TerminalHollowHarmonicBarlineTest {
    private static final int WIDTH = 740, HEIGHT = 430;
    private static final int FLOOR = 220, GAP = 16, TOP = FLOOR - 4 * GAP;
    private static final int OPENING = 158, SECOND = 225, ENDING = 581;
    private static final List<MeasureRegion> BOXES = List.of(
            new MeasureRegion(.04f,.85f,.05f,.24f),
            region(109,149), region(167,269), region(287,340), region(358,421),
            region(439,490), region(508,572), region(590,649));

    @Test
    public void twoHollowHeadsAndTheirShaftRemoveOnlyTheFalseDividers() {
        var page = page();
        var found = TerminalHollowHarmonicBarline.join(BOXES, page.labels, page.paper, page.paper, WIDTH, HEIGHT);
        assertEquals(List.of(BOXES.get(0),region(109,269),BOXES.get(3),BOXES.get(4),
                BOXES.get(5),new MeasureRegion(508f/WIDTH,649f/WIDTH,122f/HEIGHT,254f/HEIGHT,1)),found);
    }

    @Test
    public void eitherPlaneMustProveTheSecondAttackAndFinalDoubleRule() {
        var page = page();
        var noSecond = page.paper.clone();
        paint(noSecond, SECOND - 19, FLOOR - 43, SECOND - 2, FLOOR - 19, (byte)255);
        var noDouble = page.paper.clone();
        paint(noDouble, 656, TOP, 670, FLOOR, (byte)255);
        for (var edit : List.of(noSecond, noDouble)) {
            assertEquals(BOXES.size()-1, TerminalHollowHarmonicBarline.join(
                    BOXES, page.labels, edit, page.paper, WIDTH, HEIGHT).size());
            assertEquals(BOXES.size()-1, TerminalHollowHarmonicBarline.join(
                    BOXES, page.labels, page.paper, edit, WIDTH, HEIGHT).size());
        }
    }

    @Test
    public void aRealRuleBetweenTwoBarsRemains() {
        var scene = page();
        paint(scene.paper, OPENING - 25, TOP - 10, OPENING + 3, FLOOR + 11, (byte)255);
        for (int y=TOP; y<=FLOOR; y+=GAP)
            paint(scene.paper, OPENING - 25, y, OPENING + 3, y, (byte)0);
        paint(scene.paper, OPENING, TOP, OPENING + 2, FLOOR, (byte)0);
        var found = TerminalHollowHarmonicBarline.join(
                BOXES, scene.labels, scene.paper, scene.paper, WIDTH, HEIGHT);
        assertEquals(BOXES.subList(0,3), found.subList(0,3));
        assertEquals(BOXES.size()-1,found.size());
    }

    @Test
    public void recognizedOpenFingeringsStaySilentWithoutAnExplicitViolin() {
        var page = page();
        var bars = TerminalHollowHarmonicBarline.join(BOXES, page.labels, page.paper, page.paper, WIDTH, HEIGHT);
        var control = new ScoreNoteEvent(3, .5f, 0, 0, 1, FLOOR / (float) HEIGHT,
                false, 0, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 1, 1, 0, 0, ScoreNoteEvent.CLEF_TREBLE);
        var score = new ScorePageInterpretation(bars, List.of(control));
        float[] beats = {4, 2, 2, 2, 2, 1};
        var words = new java.util.ArrayList<PlayingTechniqueDetector.Word>();
        for (int shaft : new int[]{OPENING, SECOND, ENDING}) {
            int x = shaft - 10;
            for (int y : new int[]{137, 268}) {
                openZero(page.paper, x, y);
                words.add(new PlayingTechniqueDetector.Word("0", (x - 12) / (float) WIDTH,
                        (y - 12) / (float) HEIGHT, (x + 12) / (float) WIDTH, (y + 12) / (float) HEIGHT));
            }
        }
        var neutral = TerminalOpenHarmonicHeads.capture(
                score, page.labels, page.paper, page.paper, WIDTH, HEIGHT, beats, words, 0, false);
        assertEquals(List.of(1, 1, 5), neutral.stream().map(TerminalOpenHarmonicHeads.Chord::measureIndex).toList());
        assertEquals(List.of(64, 67, 64, 71, 64, 67), neutral.stream().flatMap(c -> c.heads().stream())
                .map(TerminalOpenHarmonicHeads.Head::writtenMidi).toList());
        assertEquals(0, neutral.stream().flatMap(c -> c.heads().stream())
                .filter(head -> head.soundingMidi() != null).count());
        var violin = TerminalOpenHarmonicHeads.capture(
                score, page.labels, page.paper, page.paper, WIDTH, HEIGHT, beats, words, 0, true);
        assertEquals(List.of(83, 86, 83, 90, 83, 86), violin.stream().flatMap(c -> c.heads().stream())
                .map(TerminalOpenHarmonicHeads.Head::soundingMidi).toList());
        var strokeRemoved = page.paper.clone();
        paint(strokeRemoved, ENDING - 22, 257, ENDING + 2, 279, (byte) 255);
        for (var image : List.of(strokeRemoved, page.paper)) {
            var other = image == strokeRemoved ? page.paper : strokeRemoved;
            var held = TerminalOpenHarmonicHeads.capture(
                    score, page.labels, image, other, WIDTH, HEIGHT, beats, words, 0, true);
            assertEquals(violin.subList(0, 2), held);
        }
        assertEquals(List.of(), TerminalOpenHarmonicHeads.capture(
                score, page.labels, page.paper, page.paper, WIDTH, HEIGHT, beats, List.of(), 0, false));
    }

    private static void openZero(byte[] image, int x, int y) {
        for (int dy = -8; dy <= 8; dy++) {
            for (int dx = -6; dx <= 6; dx++) {
                float distance = dx * dx / 36f + dy * dy / 64f;
                if (distance >= .42f && distance <= 1f) image[(y + dy) * WIDTH + x + dx] = 0;
            }
        }
    }

    private record Scene(byte[] paper, byte[] labels) {}

    private static Scene page() {
        var ink = new byte[WIDTH * HEIGHT];
        var labels = new byte[ink.length];
        Arrays.fill(ink, (byte)255);
        for (int y=TOP; y<=FLOOR; y+=GAP)
            paint(ink, 55, y, 670, y, (byte)0);
        for (int x : new int[]{278,349,430,499,659,667})
            paint(ink, x-1, TOP, x+1, FLOOR, (byte)0);
        for (int x : new int[]{OPENING,SECOND,ENDING}) {
            paint(ink, x, TOP - 7, x+1, FLOOR+1, (byte)0);
            diamond(ink, labels, x-10, FLOOR);
            diamond(ink, labels, x-10, FLOOR-(x==SECOND ? 2*GAP : GAP));
        }
        return new Scene(ink,labels);
    }

    private static void diamond(byte[] ink, byte[] labels, int x, int y) {
        for (int dy=-10;dy<=10;dy++)
            for (int dx=-10;dx<=10;dx++) {
                int n=Math.abs(dx)+Math.abs(dy);
                if(n>10)continue;
                labels[(y+dy)*WIDTH+x+dx]=OmrMeasurePostProcessor.SYMBOL;
                if(n>=8)ink[(y+dy)*WIDTH+x+dx]=0;
            }
    }

    private static void paint(byte[] ink, int left, int top, int right, int bottom, byte value) {
        for(int y=top;y<=bottom;y++)
            Arrays.fill(ink,y*WIDTH+left,y*WIDTH+right+1,value);
    }

    private static MeasureRegion region(int left, int right) {
        return new MeasureRegion(left/(float)WIDTH,right/(float)WIDTH,122f/HEIGHT,254f/HEIGHT);
    }
}
