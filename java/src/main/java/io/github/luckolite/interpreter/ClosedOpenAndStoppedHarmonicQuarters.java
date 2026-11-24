// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** A closed two-quarter bar with an open-string pair followed by two stopped harmonics. */
final class ClosedOpenAndStoppedHarmonicQuarters {
    private ClosedOpenAndStoppedHarmonicQuarters() {}

    private static final int[][] RADII = {
        {-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}
    };

    record Head(int step, float y, int written, int open, int sounding, int finger) {}
    private record Pair(float openingX, Head lower, Head upper, int lowStopped, int highStopped,
                        int lowTouch, int highTouch, String lowFingers, String highFingers) {}

    private record Recovery(Pair pair, int openAlias) {}

    static ScorePageInterpretation recover(ScorePageInterpretation score, List<Map<String, Object>> events,
            List<PlayingTechniqueDetector.Word> words, byte[] labels, byte[] raw, byte[] normalized,
            int width, int height, float[] beats, int[] keys, double[] starts) {
        if (raw == null || normalized == null || labels == null || raw.length != (long) width * height
                || normalized.length != raw.length || labels.length != raw.length || words.isEmpty()) return score;
        var bars = score.measures();
        var notes = score.notes();
        var byBar = new ArrayList<List<Integer>>();
        for (int i = 0; i < bars.size(); i++) byBar.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) byBar.get(notes.get(i).measureIndex()).add(i);
        var recoveries = new ArrayList<Recovery>();
        ScoreMeterChange active = null;
        int meter = 0;
        for (int bar = 1; bar + 1 < bars.size(); bar++) {
            while (meter < score.meterChanges().size() && score.meterChanges().get(meter).measureIndex() <= bar)
                active = score.meterChanges().get(meter++);
            var local = byBar.get(bar);
            if (active == null || active.numerator() != 2 || active.denominator() != 4 || beats[bar] != 2
                    || local.size() < 2 || local.size() > 3 || bars.get(bar).cadenceQuarterBeats() != 0) continue;
            int measure = bar;
            if (score.rests().stream().anyMatch(r -> r.measureIndex() == measure)) continue;
            if (local.size() == 3) {
                var group = local.stream().sorted(java.util.Comparator.comparingDouble(
                        owner -> notes.get(owner).positionInMeasure())).toList();
                int open = group.get(0);
                int lo = group.get(1), hi = group.get(2);
                if (notes.get(lo).staffStep() > notes.get(hi).staffStep()) {
                    int swap = lo; lo = hi; hi = swap;
                }
                var alias = notes.get(open);
                var low = notes.get(lo);
                var high = notes.get(hi);
                if (!ordinaryDownstem(alias) || alias.unbeamedDurationBeats() != 2 || alias.octaveShift() != 2
                        || !quarter(low) || !quarter(high) || low.octaveShift() != 0 || high.octaveShift() != 0
                        || alias.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                        || low.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                        || high.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                        || high.staffStep() - low.staffStep() != 4 || alias.staffStep() - low.staffStep() != 2
                        || Math.abs(low.positionInMeasure() - high.positionInMeasure()) > .018f
                        || !openAliasOwners(events, open, lo, hi, starts[bar])) continue;
                var source = proveOpenAlias(raw, labels, width, height, bars.get(bar - 1), bars.get(bar),
                        bars.get(bar + 1), alias, low, high, lo, hi, words, keys[bar]);
                if (source != null && source.equals(proveOpenAlias(normalized, labels, width, height,
                        bars.get(bar - 1), bars.get(bar), bars.get(bar + 1),
                        alias, low, high, lo, hi, words, keys[bar])))
                    recoveries.add(new Recovery(source, open));
                continue;
            }
            int lo = local.get(0), hi = local.get(1);
            if (notes.get(lo).staffStep() > notes.get(hi).staffStep()) {
                int initial = lo;
                lo = hi;
                hi = initial;
            }
            var a = notes.get(lo);
            var b = notes.get(hi);
            if (!quarter(a) || !quarter(b) || a.writtenAccidental() != 0 || a.octaveShift() != 2
                    || b.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY || b.octaveShift() != 0
                    || b.staffStep() - a.staffStep() != 4
                    || Math.abs(a.positionInMeasure() - b.positionInMeasure()) > .018f) continue;
            var source = prove(raw, labels, width, height, bars.get(bar - 1), bars.get(bar), bars.get(bar + 1),
                    a, b, lo, hi, words, keys[bar]);
            if (source == null) continue;
            var decoded = prove(normalized, labels, width, height,
                    bars.get(bar - 1), bars.get(bar), bars.get(bar + 1), a, b, lo, hi, words, keys[bar]);
            if (source.equals(decoded)) recoveries.add(new Recovery(source, -1));
        }
        if (recoveries.isEmpty()) return score;
        var revised = new ArrayList<>(notes);
        var owners = new ArrayList<>(IntStream.range(0, notes.size()).boxed().toList());
        for (var recovery : recoveries) {
            var pair = recovery.pair();
            var a = notes.get(pair.lowStopped());
            int bar = a.measureIndex();
            stopped(revised, events, pair.lowStopped(), pair.lowTouch(), 28,
                    pair.lowFingers(), "below", starts[bar] + 1);
            stopped(revised, events, pair.highStopped(), pair.highTouch(), 24,
                    pair.highFingers(), "above", starts[bar] + 1);
            if (recovery.openAlias() < 0)
                append(revised, events, owners, bars.get(bar), bar, pair.openingX(), pair.lower(),
                        width, height, starts[bar], "below");
            else replaceOpenAlias(revised, events, recovery.openAlias(), pair.lower(), starts[bar]);
            append(revised, events, owners, bars.get(bar), bar, pair.openingX(), pair.upper(),
                    width, height, starts[bar], "above");
        }
        return score.withNotes(revised, owners).withClockContextSource(
                score.clockContextSource().withNoteCount(revised.size()));
    }

    private static boolean openAliasOwners(List<Map<String, Object>> events, int open, int lo,
            int hi, double onset) {
        for (int i : new int[]{open, lo, hi}) {
            var event = events.get(i);
            if (!Integer.valueOf(i).equals(event.get("sourceNoteIndex"))
                    || !Boolean.FALSE.equals(event.get("durationFallback")) || event.containsKey("writtenPlacement")
                    || !Double.valueOf(i == open ? onset : onset + 1.25).equals(event.get("startBeat"))
                    || !Double.valueOf(i == open ? 2 : 1).equals(event.get("durationBeats"))) return false;
        }
        return true;
    }

    private static void replaceOpenAlias(List<ScoreNoteEvent> notes, List<Map<String, Object>> events,
            int owner, Head head, double onset) {
        var old = notes.get(owner);
        notes.set(owner, new ScoreNoteEvent(old.measureIndex(), old.positionInMeasure(), old.staffStep(),
                old.staffIndex(), old.staffCount(), old.pageY(), false, 0, 0, old.writtenAccidental(),
                ScoreNoteEvent.DURATION_QUARTER, 1, 0, 0, ScoreNoteEvent.CLEF_TREBLE).withStemDirection(-1));
        var event = events.get(owner);
        event.remove("octaveShift");
        event.put("writtenMidi", head.written());
        event.put("harmonicTouchMidi", head.written());
        event.put("openStringMidi", head.open());
        event.put("midi", head.sounding());
        event.put("startBeat", onset);
        event.put("durationBeats", 1.0);
        hand(event, 0, head.finger(), "below", false);
    }

    private static boolean quarter(ScoreNoteEvent n) {
        return ordinaryDownstem(n) && n.unbeamedDurationBeats() == 1;
    }

    private static boolean ordinaryDownstem(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.clefBottomDiatonic() == ScoreNoteEvent.CLEF_TREBLE
                && n.staffIndex() == 0 && n.staffCount() == 1 && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.stemDirection() == -1 && n.beamCount() == 0
                && n.augmentationDots() == 0 && n.tupletDivisor() == 1 && n.tupletNormalNotes() == 1
                && n.harmonicSemitones() == 0 && n.openStringTouch() == null && n.articulations() == 0
                && !n.tiedFromPrevious() && !n.crossStaffBeam() && n.boundaryTies() == 0
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0;
    }

    private static Pair prove(byte[] ink, byte[] labels, int w, int h, MeasureRegion before,
            MeasureRegion bar, MeasureRegion after, ScoreNoteEvent lower, ScoreNoteEvent upper,
            int lo, int hi, List<PlayingTechniqueDetector.Word> words, int key) {
        float nominal = (bar.bottom() - bar.top()) * h / 8.2f, span = (bar.right() - bar.left()) * w;
        if (nominal < 12 || nominal > 23 || span < nominal * 6.5f || span > nominal * 7.7f
                || upper.positionInMeasure() < .65f || upper.positionInMeasure() > .8f
                || Math.abs(bar.bottom() - before.bottom()) * h > nominal * .25f
                || Math.abs(bar.bottom() - after.bottom()) * h > nominal * .25f) return null;
        float x2 = bar.left() * w + upper.positionInMeasure() * span;
        var frame = PrintedDirectionStaff.printedRules(ink, w, h, x2, bar.bottom() * h - 2.1f * nominal, nominal);
        if (frame == null || Math.abs(frame[1] - nominal) > nominal * .1f) return null;
        float floor = frame[0], gap = frame[1];
        var staff = new PlayingTechniqueDetector.Staff(floor - 4 * gap, floor, gap, 0, 1);
        int opening = PrintedCadenceDurations.boundaryColumn(ink, w, staff,
                (before.right() + bar.left()) * w * .5f, true, false);
        int closing = PrintedCadenceDurations.boundaryColumn(ink, w, staff,
                (bar.right() + after.left()) * w * .5f, true, false);
        if (opening < 0 || closing - opening < gap * 7.1f || closing - opening > gap * 8.3f) return null;
        float top = floor - (upper.staffStep() + 3) * gap / 2;
        float middle = floor - (lower.staffStep() + 2) * gap / 2;
        float lowY = lower.pageY() * h, highY = upper.pageY() * h;
        if (Math.abs(lowY - (floor - lower.staffStep() * gap / 2)) > gap * .2f
                || Math.abs(highY - (floor - upper.staffStep() * gap / 2)) > gap * .2f) return null;
        var firstAbove = word(words, opening + gap * 1.92f, top - gap * 1.3f, gap, w, h, "[1-4]0");
        if (firstAbove == null) return null;
        float x1 = (firstAbove.left() + firstAbove.right()) * w * .5f;
        var a = shaft(ink, w, h, x1, top, gap);
        var b = shaft(ink, w, h, x2, top, gap);
        if (a == null || b == null || b.left() - a.left() < gap * 3.3f
                || b.left() - a.left() > gap * 4.1f || Math.abs(a.endY() - b.endY()) > gap * .36f)
            return null;
        float firstTop = top - .04f * gap;
        var firstBelow = word(words, x1, a.endY() + gap * 2.05f, gap, w, h, "[1-4]0");
        var lastAbove = word(words, x2, top - gap * 1.3f, gap, w, h, "[1-4][1-4]");
        var lastTouch = word(words, x2, b.endY() + gap * 1.5f, gap, w, h, "[1-4]");
        var lastBase = word(words, x2, b.endY() + gap * 3.3f, gap, w, h, "[1-4]");
        if (firstBelow == null || lastAbove == null || lastTouch == null || lastBase == null
                || lastTouch == lastBase || lastTouch.text().charAt(0) <= lastBase.text().charAt(0)
                || lastAbove.text().charAt(0) <= lastAbove.text().charAt(1)) return null;
        float ringX = (firstBelow.left() + firstBelow.right()) * w * .5f;
        if (!openTopZero(ink, w, h, x1, firstTop, gap)
                || !closedLowerZero(ink, w, h, ringX, a.endY(), gap)
                || !touch(ink, labels, w, h, x1, top, gap, a, true)
                || !touch(ink, labels, w, h, x1, middle, gap, a, false)
                || !touch(ink, labels, w, h, x2, top, gap, b, true)
                || !touch(ink, labels, w, h, x2, middle, gap, b, false)
                || !filled(ink, w, x2, lowY, gap) || !filled(ink, w, x2, highY, gap)
                || !ArtificialHarmonics.unflaggedQuarterTip(ink,w,h,a.left(),top,middle,a.endY(),-1,staff)
                || !ArtificialHarmonics.unflaggedQuarterTip(ink,w,h,b.left(),top,lowY,b.endY(),-1,staff)
                || !PrintedCadenceDurations.emptyOutsideChord(ink,w,staff,opening,closing,x1,x2,true)
                || !OmrScoreInterpreter.printedTerminalDyadSharp(ink,w,h,x2,top,gap,false)
                || !OmrScoreInterpreter.provesNaturalMeterInk(ink,w,h,Math.round(x2-gap*2.27f),
                        Math.round(x2-gap*.98f),lowY,gap,Math.round(staff.top()))) return null;
        int fromKey = ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
        int lowStep = lower.staffStep() + 2, highStep = upper.staffStep() + 3;
        int lowTouch = midi(lowStep, fromKey, key), highTouch = midi(highStep, fromKey, key);
        int lowBase = midi(lower.staffStep(), 0, key), highBase = midi(upper.staffStep(), fromKey, key);
        var firstLow = natural(lowStep, middle, lowTouch, firstBelow.text().charAt(0) - '0');
        var firstHigh = natural(highStep, top, highTouch, firstAbove.text().charAt(0) - '0');
        if (firstLow == null || firstHigh == null || firstLow.open() == firstHigh.open()
                || lowTouch - lowBase != 4 || highTouch - highBase != 5
                || highTouch != midi(highStep, ScoreNoteEvent.ACCIDENTAL_SHARP, key)) return null;
        return new Pair(x1, firstLow, firstHigh, lo, hi, lowTouch, highTouch,
                lastTouch.text() + lastBase.text(), lastAbove.text());
    }


    /** A detected half head can be one of two open diamonds sharing an unflagged quarter shaft. */
    private static Pair proveOpenAlias(byte[] ink, byte[] labels, int w, int h, MeasureRegion before,
            MeasureRegion bar, MeasureRegion after, ScoreNoteEvent alias, ScoreNoteEvent lower,
            ScoreNoteEvent upper, int lo, int hi, List<PlayingTechniqueDetector.Word> words, int key) {
        float nominal = (bar.bottom() - bar.top()) * h / 8.2f, span = (bar.right() - bar.left()) * w;
        if (nominal < 10 || nominal > 25 || bar.cadenceQuarterBeats() != 0
                || Math.abs(bar.bottom() - before.bottom()) * h > nominal * .36f
                || Math.abs(bar.bottom() - after.bottom()) * h > nominal * .36f) return null;
        float x1 = w * bar.left() + alias.positionInMeasure() * span;
        float x2 = w * bar.left() + upper.positionInMeasure() * span;
        var rails = PrintedDirectionStaff.printedRules(ink, w, h,
                (bar.left() + bar.right()) * w * .5f, bar.bottom() * h - 2.1f * nominal, nominal);
        if (rails == null || Math.abs(rails[1] - nominal) > nominal * .12f) return null;
        float floor = rails[0], gap = rails[1];
        var staff = new PlayingTechniqueDetector.Staff(floor - 4 * gap, floor, gap, 0, 1);
        int opening = PrintedCadenceDurations.boundaryColumn(ink, w, staff,
                (before.right() + bar.left()) * w * .5f, true, false);
        int closing = PrintedCadenceDurations.boundaryColumn(ink, w, staff,
                (bar.right() + after.left()) * w * .5f, true, false);
        if (opening < 0 || closing < 0 || x1 - opening < gap * 1.5f || x1 - opening > gap * 3.0f
                || x2 - x1 < gap * 3.6f || x2 - x1 > gap * 6.1f
                || closing - x2 < gap * 1.8f || closing - x2 > gap * 6.0f) return null;
        float openLowY = floor - alias.staffStep() * gap * .5f;
        int openHighStep = alias.staffStep() + 3;
        float openHighY = floor - openHighStep * gap * .5f;
        float stopLowY = lower.pageY() * h, stopHighY = upper.pageY() * h;
        int lowStep = lower.staffStep() + 2, highStep = upper.staffStep() + 3;
        float lowTouchY = floor - lowStep * gap * .5f, highTouchY = floor - highStep * gap * .5f;
        if (Math.abs(alias.pageY() * h - openLowY) > gap * .23f
                || Math.abs(stopLowY - (floor - lower.staffStep() * gap * .5f)) > gap * .23f
                || Math.abs(stopHighY - (floor - upper.staffStep() * gap * .5f)) > gap * .23f) return null;
        var a = shaft(ink, w, h, x1, openHighY, gap);
        var b = shaft(ink, w, h, x2, highTouchY, gap);
        if (a == null || b == null || !touch(ink, labels, w, h, x1, openHighY, gap, a, true)
                || !touch(ink, labels, w, h, x1, alias.pageY() * h, gap, a, false)
                || !touch(ink, labels, w, h, x2, highTouchY, gap, b, true)
                || !touch(ink, labels, w, h, x2, lowTouchY, gap, b, false)
                || !filled(ink, w, x2, stopLowY, gap) || !filled(ink, w, x2, stopHighY, gap)
                || !ArtificialHarmonics.unflaggedQuarterTip(ink, w, h, a.left(), openHighY,
                        openLowY, a.endY(), -1, staff)
                || !ArtificialHarmonics.unflaggedQuarterTip(ink, w, h, b.left(), lowTouchY,
                        stopLowY, b.endY(), -1, staff)
                || !PrintedCadenceDurations.emptyOutsideChord(ink, w, staff, opening, closing, x1, x2, true))
            return null;
        var openAbove = word(words, x1, openHighY - 2 * gap, gap, w, h, "[1-4]0");
        var openBelow = word(words, x1, a.endY() + 1.5f * gap, gap, w, h, "[1-4]0");
        var stopAbove = word(words, x2, highTouchY - 2 * gap, gap, w, h, "[1-4][1-4]");
        var stopBelow = word(words, x2, b.endY() + gap, gap, w, h, "[1-4][1-4]");
        if (openAbove == null || openBelow == null || stopAbove == null || stopBelow == null
                || !boxedZero(ink, w, h, x1, gap, openAbove) || !boxedZero(ink, w, h, x1, gap, openBelow)
                || stopAbove.text().charAt(0) <= stopAbove.text().charAt(1)
                || stopBelow.text().charAt(0) <= stopBelow.text().charAt(1)) return null;
        int fromKey = ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
        int lowTouch = midi(lowStep, fromKey, key), highTouch = midi(highStep, fromKey, key);
        if (lowTouch - midi(lower.staffStep(), lower.writtenAccidental(), key) != 4
                || highTouch - midi(upper.staffStep(), upper.writtenAccidental(), key) != 5) return null;
        var lowOpen = natural(alias.staffStep(), alias.pageY() * h, midi(alias.staffStep(), fromKey, key),
                openBelow.text().charAt(0) - '0');
        var highOpen = natural(openHighStep, openHighY, midi(openHighStep, fromKey, key),
                openAbove.text().charAt(0) - '0');
        if (lowOpen == null || highOpen == null || lowOpen.open() == highOpen.open()) return null;
        return new Pair(x1, lowOpen, highOpen, lo, hi, lowTouch, highTouch, stopBelow.text(), stopAbove.text());
    }

    private static boolean boxedZero(byte[] gray, int w, int h, float x, float gap,
            PlayingTechniqueDetector.Word word) {
        int left = Math.round(word.left() * w), right = Math.round(word.right() * w);
        int top = Math.round(word.top() * h), bottom = Math.round(word.bottom() * h);
        if (left < 0 || right >= w || top < 0 || bottom >= h || left >= right || top >= bottom
                || bottom - top > gap * 4.1f) return false;
        var box = new NaturalHarmonicRingProof.Box(left, top, right, bottom);
        var rings = NaturalHarmonicRingProof.rings(gray, w, h, box, 195, Math.round(gap * .6f),
                Math.round(gap * 1.6f), new NaturalHarmonicRingProof.Budget(100_000, 100_000));
        return rings.size() == 1 && rings.get(0).centerY() > (top + bottom) * .5f
                && Math.abs(rings.get(0).centerX() - x) < gap * .34f
                && rings.get(0).counterPixels() >= gap * gap * .095f;
    }

    private static PlayingTechniqueDetector.Word word(List<PlayingTechniqueDetector.Word> words,
            float x, float y, float gap, int w, int h, String pattern) {
        var matching = words.stream().filter(word -> word.text() != null && word.text().matches(pattern)
                && (word.right() - word.left()) * w <= gap * 2.6f
                && Math.abs((word.left() + word.right()) * w / 2 - x) < gap * .45f
                && Math.abs((word.top() + word.bottom()) * h / 2 - y) < gap * .89f).toList();
        return matching.size() == 1 ? matching.get(0) : null;
    }

    private static PrintedBeamShafts.Shaft shaft(byte[] ink, int w, int h, float x, float y, float gap) {
        var shafts = PrintedBeamShafts.at(ink, w, h, x, y, gap, new PrintedBeamShafts.Budget(120_000));
        return shafts.size() == 1 && shafts.get(0).direction() == 1 ? shafts.get(0) : null;
    }

    private static boolean touch(byte[] ink, byte[] labels, int w, int h, float x, float y, float gap,
            PrintedBeamShafts.Shaft shaft, boolean onRule) {
        if (onRule) {
            for (int dy = -1; dy <= 1; dy++)
                if (LedgerCrossedHarmonicDiamond.proved(labels, ink, w, h, x, y + dy, gap, shaft.left(), -1))
                    return true;
            // At a heavy staff crossing, the split outline can still close around real paper.
            int reach = Math.round(gap * .24f);
            for (int row = Math.round(y) - reach; row <= Math.round(y) + reach; row++)
                for (int col = Math.round(x) - reach; col <= Math.round(x) + reach; col++)
                    if (crossedAperture(labels, ink, w, col, row, gap)
                            && Math.abs(shaft.startY() - y) < gap * .32f) return true;
            return false;
        }
        return ArtificialHarmonics.enclosedCounter(ink,w,h,x,y,gap*.75f,gap*.24f)
                && darkColumn(ink, w, shaft.left(), y, gap);
    }

    private static boolean crossedAperture(byte[] labels, byte[] ink, int width, int x, int y, float gap) {
        int center = tone(ink, width, x, y);
        if (center < 85) return false;
        int edge = 0, neighbors = 0;
        int near = Math.max(2, Math.round(gap * .11f)), far = Math.round(gap * .25f);
        for (var radii : RADII) {
            int dx = radii[0], dy = radii[1], dark = 255;
            if (tone(ink, width, x + dx, y + dy) >= 50) neighbors++;
            for (int d = near; d <= far; d++)
                dark = Math.min(dark, tone(ink, width, x + d * dx, y + d * dy));
            if (dark >= 50) return false;
            edge = Math.max(edge, dark);
        }
        if (neighbors < 3 || center - edge < 59) return false;
        int recognized = 0, radius = Math.round(gap * .18f);
        for (int yy = y - radius; yy <= y + radius; yy++) {
            for (int xx = x - radius; xx <= x + radius; xx++) {
                int label = labels[yy * width + xx] & 255;
                if (label == OmrMeasurePostProcessor.NOTEHEAD || label == OmrMeasurePostProcessor.SYMBOL)
                    recognized++;
            }
        }
        int side = 2 * radius + 1;
        return recognized >= side * side * .5f;
    }

    private static int tone(byte[] image, int width, int x, int y) {
        return image[y * width + x] & 255;
    }

    private static boolean darkColumn(byte[] ink, int w, int x, float y, float gap) {
        for (int row = Math.round(y - gap * .66f); row <= Math.round(y + gap * .66f); row++)
            if ((ink[row * w + x] & 255) >= 165) return false;
        return true;
    }

    private static boolean filled(byte[] ink, int w, float x, float y, float gap) {
        int reach = Math.round(gap * .22f), cx = Math.round(x), cy = Math.round(y);
        for (int row = cy - 2; row <= cy + 2; row++)
            for (int col = cx - reach; col <= cx + reach; col++)
                if ((ink[row * w + col] & 255) >= 155) return false;
        return true;
    }

    private static boolean closedLowerZero(byte[] ink, int w, int h, float x, int tip, float gap) {
        var box = new NaturalHarmonicRingProof.Box(Math.round(x - gap * .85f), Math.round(tip + gap * 2.11f),
                Math.round(x + gap * .85f), Math.round(tip + gap * 3.7f));
        var rings = NaturalHarmonicRingProof.rings(ink, w, h, box, 195, Math.round(gap * .62f),
                Math.round(gap * 1.4f), new NaturalHarmonicRingProof.Budget(220_000, 220_000));
        return rings.size() == 1 && Math.abs(rings.get(0).centerX() - x) < gap * .25f
                && rings.get(0).counterPixels() >= gap * gap * .20f;
    }

    private static int midi(int step, int accidental, int key) {
        return ScoreTiePitchGuard.writtenMidi(ScoreNoteEvent.CLEF_TREBLE + step, accidental, key);
    }

    static Head natural(int step, float y, int written, int finger) {
        int sound = OpenStringHarmonics.uniqueSoundingMidi(written);
        if (sound < 0) return null;
        for (int open : new int[]{55, 62, 69, 76}) {
            int partial = switch (written - open) {
                case 4, 9 -> 28;
                case 5 -> 24;
                case 7 -> 19;
                default -> 0;
            };
            if (partial != 0 && open + partial == sound) return new Head(step, y, written, open, sound, finger);
        }
        return null;
    }

    static void stopped(List<ScoreNoteEvent> notes, List<Map<String, Object>> events, int index, int touch,
            int transposition, String fingers, String side, double onset) {
        var note = notes.get(index);
        notes.set(index, note.withOctaveShift(0).withHarmonicSemitones(transposition));
        var event = events.get(index);
        int written = touch - (transposition == 28 ? 4 : 5);
        event.remove("octaveShift");
        event.remove("writtenPlacement");
        event.put("writtenMidi", written);
        event.put("harmonicTouchMidi", touch);
        event.put("midi", written + transposition);
        event.put("startBeat", onset);
        event.put("durationBeats", 1.0);
        event.put(transposition == 28 ? "artificialHarmonicThird" : "artificialHarmonicFourth", true);
        hand(event, fingers.charAt(1) - '0', fingers.charAt(0) - '0', side, true);
    }

    static void append(List<ScoreNoteEvent> notes, List<Map<String, Object>> events, List<Integer> owners,
            MeasureRegion bar, int measure, float x, Head head, int width, int height, double onset, String side) {
        append(notes, events, owners, bar, measure, x, head, width, height, onset, side, -1);
    }

    static void append(List<ScoreNoteEvent> notes, List<Map<String, Object>> events, List<Integer> owners,
            MeasureRegion bar, int measure, float x, Head head, int width, int height, double onset, String side, int stem) {
        var note = new ScoreNoteEvent(measure, (x / width - bar.left()) / (bar.right() - bar.left()), head.step(),
                0, 1, head.y() / height, false, 0, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                ScoreNoteEvent.DURATION_QUARTER, 1, 0, 0, ScoreNoteEvent.CLEF_TREBLE).withStemDirection(stem);
        var event = new LinkedHashMap<String, Object>();
        event.put("measureIndex", measure);
        event.put("staffIndex", 0);
        event.put("staffCount", 1);
        event.put("midi", head.sounding());
        event.put("clefInferred", false);
        event.put("writtenMidi", head.written());
        event.put("harmonicTouchMidi", head.written());
        event.put("openStringMidi", head.open());
        event.put("startBeat", onset);
        event.put("durationBeats", 1.0);
        event.put("durationFallback", false);
        event.put("tiedFromPrevious", false);
        event.put("x", x);
        event.put("y", head.y());
        event.put("sourceNoteIndex", notes.size());
        hand(event, 0, head.finger(), side, false);
        notes.add(note);
        events.add(event);
        owners.add(-1);
    }

    private static void hand(Map<String, Object> event, int base, int touch, String placement, boolean stopped) {
        event.put("mixedHarmonicQuarter", true);
        if (stopped) event.put("sharedStoppedHarmonicPair", true);
        event.put("mixedHarmonicFingering", Map.of("base", base, "touch", touch, "placement", placement));
    }

    private record Row(int left, int leftEnd, int rightStart, int right) {
        boolean hasCounter() { return rightStart > leftEnd + 1; }
    }

    private static boolean openTopZero(byte[] ink, int w, int h, float centerX, float headY, float gap) {
        int left = Math.round(centerX - gap * .86f), right = Math.round(centerX + gap * .86f);
        int top = Math.round(headY - gap * 1.86f), bottom = Math.round(headY - gap * .67f);
        if (left < 0 || right >= w || top < 0 || bottom >= h) return false;
        boolean finished = false;
        int splitRows = 0, joinedFoot = 0, minLeft = right, maxRight = left;
        Row previous = null;
        for (int y = top; y <= bottom; y++) {
            int a = -1, b = -1, c = -1, d = -1;
            boolean ended = false;
            for (int x = left; x <= right; x++) {
                if ((ink[y * w + x] & 255) >= 195) {
                    ended |= a >= 0;
                    continue;
                }
                if (a < 0) a = x;
                if (!ended) b = x;
                else if (c < 0) c = x;
                else if (x - d != 1) return false;
                d = x;
            }
            if (a < 0) {
                finished |= previous != null;
                continue;
            }
            if (finished || a == left || d == right || d - a > gap * 1.12f) return false;
            Row row = new Row(a, b, c, d);
            if (row.hasCounter()) {
                if (joinedFoot > 0 || row.rightStart() - row.leftEnd() > gap * .53f) return false;
                if (previous != null && (Math.abs(previous.left() - a) > 2 || Math.abs(previous.right() - d) > 2))
                    return false;
                minLeft = Math.min(minLeft, a);
                maxRight = Math.max(maxRight, d);
                splitRows++;
                previous = row;
                continue;
            }
            if (splitRows == 0) continue;
            if (splitRows < gap * .56f) return false;
            if (joinedFoot == 0 && previous.leftEnd() - a > gap * .26f) return false;
            joinedFoot++;
            if (joinedFoot > 1 && d - a > previous.right() - previous.left() + 1) return false;
            previous = row;
        }
        return splitRows >= gap * .56f && splitRows <= gap * .97f && joinedFoot >= 2
                && joinedFoot <= gap * .38f && maxRight - minLeft >= gap * .68f
                && previous != null && previous.right() - previous.left() <= gap * .45f;
    }

}
