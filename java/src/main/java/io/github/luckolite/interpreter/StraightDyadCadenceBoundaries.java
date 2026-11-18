// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Recover skipped physical separators around a complete written half-note cadence. */
final class StraightDyadCadenceBoundaries {
    private StraightDyadCadenceBoundaries() {}

    static List<MeasureRegion> recover(
            List<MeasureRegion> regions, byte[] labels, byte[] gray, int width, int height) {
        var result = new ArrayList<MeasureRegion>();
        for (var region : regions) {
            var proposal = propose(region, gray, width, height);
            if (!proposal.isEmpty()) {
                var local = OmrScoreInterpreter.analyze(labels, gray, width, height, proposal);
                if (closedSixteenthPair(proposal, local.notes(), local.rests(), width, height)
                        || completeCadenceGroup(
                                proposal, local.notes(), local.rests(), width, height)
                        || closesWithQuarterDyad(
                                proposal, local.notes(), local.rests(), width, height)) {
                    result.addAll(proposal);
                    continue;
                }
            }
            result.add(region);
        }
        return result.size() == regions.size() ? regions : List.copyOf(result);
    }

    /** Two complete sixteenth runs can flank a printed rule even with one unresolved head. */
    static boolean closedSixteenthPair(
            List<MeasureRegion> parts,
            List<ScoreNoteEvent> notes,
            List<ScoreRestEvent> rests,
            int width,
            int height) {
        if (parts.size() != 2 || !rests.isEmpty()) return false;
        for (int bar = 0; bar < parts.size(); bar++) {
            final int measure = bar;
            var owners = notes.stream().filter(n -> n.measureIndex() == measure).toList();
            var members =
                    owners.stream()
                            .filter(n -> n.stemDirection() != 0 && dyadMember(n, false))
                            .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                            .toList();
            if (members.size() < 31 || members.size() > 32 || owners.size() - members.size() > 1)
                return false;

            var region = parts.get(bar);
            float span = (region.right() - region.left()) * width;
            float gap = (region.bottom() - region.top()) * height / 8.2f;
            float normalizedGap = gap / span;
            if (members.get(0).positionInMeasure() > 2 * normalizedGap
                    || members.get(members.size() - 1).positionInMeasure() < 1 - 2 * normalizedGap)
                return false;

            for (var owner : owners) {
                if (members.contains(owner)) continue;
                var adjacent =
                        members.stream()
                                .filter(
                                        n ->
                                                Math.abs(
                                                                n.positionInMeasure()
                                                                        - owner.positionInMeasure())
                                                        <= .35f * normalizedGap)
                                .toList();
                if (owner.stemDirection() != 0
                        || owner.beamCount() < 3
                        || adjacent.size() != 2
                        || adjacent.stream()
                                .anyMatch(n -> Math.abs(n.staffStep() - owner.staffStep()) < 8))
                    return false;
            }

            int attacks = 0;
            float previous = 0;
            for (int start = 0; start < members.size(); ) {
                var first = members.get(start);
                float position = first.positionInMeasure();
                int end = start + 1;
                while (end < members.size()
                        && members.get(end).positionInMeasure() - position <= .35f * normalizedGap)
                    end++;
                if (end - start > 2) return false;
                if (end - start == 2) {
                    var second = members.get(start + 1);
                    if (Math.abs(first.staffStep() - second.staffStep()) < 2
                            || first.stemDirection() != second.stemDirection()) return false;
                }
                float separation = position - previous;
                if (attacks > 0
                        && (separation < 1.25f * normalizedGap
                                || separation > 3.25f * normalizedGap)) return false;
                previous = position;
                attacks++;
                start = end;
            }
            if (attacks != 16) return false;
        }
        return true;
    }

    static List<MeasureRegion> propose(
            MeasureRegion whole, byte[] gray, int width, int height) {
        if (whole.cadenceQuarterBeats() != 0) return List.of();
        int left = Math.round(whole.left() * width), right = Math.round(whole.right() * width);
        float estimate = (whole.bottom() - whole.top()) * height / 8.2f;
        float hint = whole.bottom() * height - 2.1f * estimate;
        if (estimate < 8 || estimate > 29 || right-left < estimate * 59)
            return List.of();
        var candidates = new ArrayList<Integer>();
        // An interior printed rule is never inferred from evenly spaced notes.
        for (int x = left + Math.round(estimate * 14); x < right - Math.round(estimate * 14); x++) {
            if (!DottedDyadMeasureSeparator.fullInk(
                    gray, width, x, Math.round(hint - estimate * 4), estimate)) continue;
            var staff = DottedDyadMeasureSeparator.localRails(gray,width,height,x,hint,estimate);
            if (staff == null) continue;
            float gap = staff[1]; int bottom = Math.round(staff[0]), top = Math.round(staff[0] - gap * 4);
            int first = x, last = x;
            int maximum = Math.round(gap);
            while (first > x - maximum && DottedDyadMeasureSeparator.fullInk(gray,width,first-1,top,gap))
                first--;
            while (last < x + maximum && DottedDyadMeasureSeparator.fullInk(gray,width,last+1,top,gap))
                last++;
            if (last-first+1 > gap * .9f || last-first+1 < gap *.14f
                    || !OmrMeasurePostProcessor.verticalRuleAt(gray,width,(first+last)/2,top,bottom,gap)
                    || DottedDyadMeasureSeparator.continuesBeyondStaff(
                            gray,width,height,(first+last)/2,top,bottom,gap)
                    || ClosedHeadBarlineGuard.attached(gray,width,height,(first+last)/2,top,bottom,gap))
                continue;
            int cut = (first+last)/2;
            if (candidates.stream().allMatch(prior -> Math.abs(prior - cut) > gap * .8f))
                candidates.add(cut);
            x = last;
        }
        if (candidates.size() > 2 || candidates.isEmpty()) return List.of();
        int pad = Math.max(3, Math.round(estimate * .55f));
        if (candidates.size()==1) {
            int cut = candidates.get(0);
            if (cut-left < estimate*17 || right-cut < estimate*24f) return List.of();
            return List.of(new MeasureRegion(whole.left(),(cut-pad)/(float)width,whole.top(),whole.bottom()),
                    new MeasureRegion((cut+pad)/(float)width,whole.right(),whole.top(),whole.bottom()));
        }
        int a = candidates.get(0), b=candidates.get(1);
        if (a-left < estimate*24f || b-a < estimate*17 || right-b < estimate*24f) return List.of();
        return List.of(new MeasureRegion(whole.left(),(a-pad)/(float)width,whole.top(),whole.bottom()),
                new MeasureRegion((a+pad)/(float)width,(b-pad)/(float)width,whole.top(),whole.bottom()),
                new MeasureRegion((b+pad)/(float)width,whole.right(),whole.top(),whole.bottom()));
    }

    static boolean completeCadenceGroup(
            List<MeasureRegion> parts, List<ScoreNoteEvent> notes, List<ScoreRestEvent> rests,
            int width, int height) {
        if ((parts.size()!=3 && parts.size()!=2) || !rests.isEmpty()) return false;
        int cadence=parts.size()-2;
        for (int i=0;i<parts.size();i++) {
            int bar=i;
            var local=notes.stream().filter(n->n.measureIndex()==bar)
                    .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
            int columns=i==cadence?9:16;
            if(local.size()!=columns*2)return false;
            var m=parts.get(i);float span=(m.right()-m.left())*width;
            float gap=(m.bottom()-m.top())*height/8.2f;
            for(int j=0;j<local.size();j+=2) {
                var upper=local.get(j);var lower=local.get(j+1);
                boolean half=i==cadence && j+2==local.size();
                if(Math.abs(upper.positionInMeasure()-lower.positionInMeasure())*span>gap*.35f
                        || upper.staffStep()==lower.staffStep()
                        || upper.stemDirection()==0 || upper.stemDirection()!=lower.stemDirection()
                        || !dyadMember(upper,half) || !dyadMember(lower,half))return false;
            }
        }
        return true;
    }

    /** A printed separator can distinguish a complete 4/4 run from a second
     * bar whose last, shared-stem quarter is written without a beam. */
    static boolean closesWithQuarterDyad(List<MeasureRegion> parts,
            List<ScoreNoteEvent> notes, List<ScoreRestEvent> rests, int width, int height) {
        if (parts.size() != 2 || !rests.isEmpty()) return false;
        for (int bar = 0; bar < 2; bar++) {
            final int index = bar;
            var owners = notes.stream().filter(n -> n.measureIndex() == index)
                    .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
            var region = parts.get(bar);
            float span = (region.right() - region.left()) * width;
            float gap = (region.bottom() - region.top()) * height / 8.2f;
            if (owners.isEmpty() || owners.get(0).positionInMeasure() > .12f
                    || owners.get(owners.size() - 1).positionInMeasure() < .79f) return false;
            int columns = 0, singleton = 0, dottedColumns = 0;
            for (int start = 0; start < owners.size();) {
                var head = owners.get(start);
                int end = start + 1;
                while (end < owners.size() && (owners.get(end).positionInMeasure()
                        - head.positionInMeasure()) * span <= gap * .35f) end++;
                boolean quarter = bar == 1 && end == owners.size();
                int direction = head.stemDirection();
                if (end - start > 2 || direction == 0) return false;
                if (end - start == 1) {
                    if (quarter || bar == 0 || ++singleton > 2) return false;
                } else {
                    var mate = owners.get(start + 1);
                    if (mate.staffStep() == head.staffStep()
                            || Math.abs(mate.pageY() - head.pageY()) * height < gap) return false;
                }
                int dots = head.augmentationDots();
                if (dots != 0 && (bar != 0 || dots != 1 || ++dottedColumns > 1)) return false;
                for (int i = start; i < end; i++) {
                    var n = owners.get(i);
                    if (n.stemDirection() != direction || n.augmentationDots() != dots
                            || n.staffIndex() != 0 || n.staffCount() != 1
                            || n.kind() != ScoreNoteEvent.Kind.PITCHED
                            || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                            || n.crossStaffBeam() || n.tiedFromPrevious() || n.boundaryTies() != 0
                            || n.tupletDivisor() != 1 || n.leadingRestBeats() != 0
                            || n.followingRestBeats() != 0 || (n.articulations() & NoteOrnament.GRACE) != 0
                            || n.beamCount() != (quarter ? 0 : 2)
                            || n.unbeamedDurationBeats() != (quarter ? 1 : 0)) return false;
                }
                columns++;
                start = end;
            }
            if (columns != (bar == 0 ? 16 : 13)) return false;
        }
        return true;
    }

    private static boolean dyadMember(ScoreNoteEvent n, boolean half) {
        return n.kind()==ScoreNoteEvent.Kind.PITCHED && n.staffCount()==1 && n.staffIndex()==0
                && n.visualSize()==ScoreNoteEvent.VisualSize.FULL && n.augmentationDots()==0
                && n.tupletDivisor()==1 && !n.crossStaffBeam()
                && !n.tiedFromPrevious() && (n.articulations() & NoteOrnament.GRACE)==0
                && (half?n.beamCount()==0 && n.unbeamedDurationBeats()==2
                         :n.beamCount()==2 && n.unbeamedDurationBeats()==0);
    }
}
