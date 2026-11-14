// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Recovers a complete half-note dyad between existing regions and a printed inline meter. */
final class InlineHalfDyadCadence {
    private InlineHalfDyadCadence() {}

    record Proposal(MeasureRegion region, PlayingTechniqueDetector.Staff staff) {}

    static List<MeasureRegion> recover(
            List<MeasureRegion> regions, byte[] labels, byte[] gray, int width, int height) {
        if (gray == null || labels == null || gray.length != (long) width * height
                || labels.length != gray.length) return regions;
        var updated = new ArrayList<MeasureRegion>();
        for (int i = 0; i < regions.size(); i++) {
            var before = regions.get(i);
            updated.add(before);
            if (i + 1 == regions.size()) continue;
            var after = regions.get(i + 1);
            var proposal = propose(before, after, gray, width, height);
            if (proposal != null && qualifies(before, after, proposal, labels, gray, width, height))
                updated.add(proposal.region());
        }
        return updated.size() == regions.size() ? regions : List.copyOf(updated);
    }

    static Proposal propose(
            MeasureRegion before, MeasureRegion after, byte[] gray, int width, int height) {
        if (before.cadenceQuarterBeats() != 0 || after.cadenceQuarterBeats() != 0) return null;
        float staffSpan = (after.bottom() - after.top()) * height;
        float gap = staffSpan / 8.2f;
        int left = Math.round(before.right() * width), right = Math.round(after.left() * width);
        float space = right - left;
        if (gap < 8 || gap > 32 || space < gap * 3.8f || space > gap * 4.9f
                || Math.abs(before.top() - after.top()) * height > gap
                || Math.abs(before.bottom() - after.bottom()) * height > gap
                || (before.right() - before.left()) * width < gap * 16
                || (after.right() - after.left()) * width < gap * 16) return null;

        float hint = (before.bottom() + after.bottom()) * height * .5f - gap * 2.1f;
        int nearOpening = left + Math.round(gap * .55f);
        var rails = DottedDyadMeasureSeparator.localRails(gray, width, height, nearOpening, hint, gap);
        if (rails == null) return null;
        gap = rails[1];
        int top = Math.round(rails[0] - 4 * gap), bottom = Math.round(rails[0]);
        var staff = new PlayingTechniqueDetector.Staff(top, bottom, gap, 0, 1);
        int opening = singleOpening(gray, width, height, top, bottom, gap, nearOpening);
        if (opening < 0) return null;
        int doubleProbe = right - Math.round(gap * .86f);
        int closing = PrintedCadenceDurations.boundaryColumn(
                gray, width, staff, doubleProbe + gap * .55f, false, true);
        if (closing < right - gap * .85f || closing > right - gap * .35f
                || closing - opening < gap * 2.9f || closing - opening > gap * 3.6f) return null;
        int first = opening + Math.round(gap * .55f);
        int last = closing - Math.round(gap * .33f);
        if (last - first < gap * 1.9f) return null;
        var region = new MeasureRegion(first / (float) width, last / (float) width,
                (before.top() + after.top()) * .5f, (before.bottom() + after.bottom()) * .5f);
        return new Proposal(region, staff);
    }

    private static int singleOpening(byte[] gray, int width, int height, int top, int bottom,
            float gap, int expected) {
        int axis = -1;
        int first = expected - Math.round(gap * .25f), last = expected + Math.round(gap * .25f);
        for (int x = first; x <= last; x++) {
            if (!DottedDyadMeasureSeparator.fullInk(gray, width, x, top, gap)) continue;
            int start = x;
            while (x < last && DottedDyadMeasureSeparator.fullInk(gray, width, x + 1, top, gap)) x++;
            if (x - start + 1 < 3 || x - start + 1 > gap * .4f) continue;
            int center = (start + x) / 2;
            if (!OmrMeasurePostProcessor.verticalRuleAt(gray, width, center, top, bottom, gap)
                    || ClosedHeadBarlineGuard.attached(gray, width, height, center, top, bottom, gap)
                    || DottedDyadMeasureSeparator.continuesBeyondStaff(
                            gray, width, height, center, top, bottom, gap)) continue;
            if (axis >= 0) return -1;
            axis = center;
        }
        return axis;
    }

    static boolean qualifies(
            MeasureRegion before, MeasureRegion after, Proposal proposal,
            byte[] labels, byte[] gray, int width, int height) {
        var measures = List.of(before, proposal.region(), after);
        var analysis = OmrScoreInterpreter.analyze(labels, gray, width, height, measures);
        var heads = analysis.notes().stream().filter(n -> n.measureIndex() == 1).toList();
        if (heads.size() != 2 || heads.get(0).staffStep() == heads.get(1).staffStep()
                || heads.get(0).stemDirection() == 0
                || heads.get(0).stemDirection() != heads.get(1).stemDirection()) return false;
        var meterChanges = MeterFontMatcher.standaloneReadings(
                labels, gray, width, height, measures, analysis.notes());
        if (meterChanges.stream().noneMatch(m -> m.measureIndex() == 2)) return false;
        var score = new ScorePageInterpretation(measures, analysis.notes(), 1, List.of(),
                List.of(), meterChanges, analysis.rests());
        return PrintedCadenceDurations.capture(score, gray, width, height, List.of(proposal.staff()))
                .measures().get(1).cadenceQuarterBeats() == 2;
    }
}
