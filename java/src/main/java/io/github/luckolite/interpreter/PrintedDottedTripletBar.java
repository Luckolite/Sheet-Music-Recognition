// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Repairs a complete six-beat dotted-triplet bar with two proved borrowed beam heads. */
final class PrintedDottedTripletBar {
    private PrintedDottedTripletBar() {}

    private record Head(int owner, ScoreNoteEvent note, float x, float y) {}
    private record Shaft(int x, int y, int travel, float density) {}
    private record Bridge(Shaft a, Shaft b, float inset, float gap) {
        float center(float x) { return a.y + (b.y-a.y)*(x-a.x)/(b.x-a.x) - a.travel*inset; }
    }
    private record Raster(byte[] ink, int width, int height) {
        boolean dark(int x, int y) {
            return x >= 0 && x < width && y >= 0 && y < height && (ink[y*width+x]&255) < 155;
        }
    }

    static ScorePageInterpretation apply(
            ScorePageInterpretation score, ScoreMeterChange inherited,
            byte[] gray, byte[] raw, int width, int height) {
        return apply(score, inherited, gray, raw, width, height, null);
    }

    static ScorePageInterpretation apply(
            ScorePageInterpretation score, ScoreMeterChange inherited,
            byte[] gray, byte[] raw, int width, int height, byte[] labels) {
        if (gray == null || gray.length != (long) width*height || width < 256 || height < 128)
            return score;
        var raster = new Raster(gray, width, height);
        var notes = score.notes();
        var beams = score.beamSource();
        var perBar = new ArrayList<List<Head>>(score.measures().size());
        for (int i = 0; i < score.measures().size(); i++) perBar.add(new ArrayList<>());
        for (int owner = 0; owner < notes.size(); owner++) {
            var n = notes.get(owner);
            var bar = score.measures().get(n.measureIndex());
            perBar.get(n.measureIndex()).add(new Head(owner,n,
                    (bar.left()+n.positionInMeasure()*(bar.right()-bar.left()))*width,n.pageY()*height));
        }
        var remove = new boolean[notes.size()];
        var rewrite = new ScoreNoteEvent[notes.size()];
        var excludedRests = new ArrayList<ScoreRestEvent>();
        boolean changed = false;
        var numberedOwners = new ArrayList<Head>();
        int nextMeter = 0;
        var meter = inherited;
        for (int barIndex=0; barIndex<perBar.size(); barIndex++) {
            while (nextMeter<score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex()<=barIndex)
                meter=score.meterChanges().get(nextMeter++);
            final int index=barIndex;
            var heads=perBar.get(barIndex);
            if (heads.size() == 24
                    && (meter == null || meter.numerator() == 4 && meter.denominator() == 4)
                    && raw != null && raw.length == gray.length
                    && score.rests().stream().noneMatch(r -> r.measureIndex() == index)) {
                var hookAlias = octaveSecondaryHookAlias(
                        raster, new Raster(raw, width, height), score.measures(), index, heads);
                if (!hookAlias.isEmpty()) {
                    for (var head : hookAlias) {
                        int dots = head.note.augmentationDots();
                        rewrite[head.owner] = withRhythm(head.note, dots, dots == 0 ? 2 : 1, 1, 1);
                    }
                    changed = true;
                    continue;
                }
            }
            if (meter == null || meter.denominator() != 4) continue;
            if (meter.numerator() == 4 && heads.size() == 7 && raw != null && raw.length == gray.length) {
                var bar = score.measures().get(index);
                var rests = score.rests().stream().filter(rest -> rest.measureIndex() == index).toList();
                var cadence = closedTripletsIntoHalf(raster, new Raster(raw,width,height), bar, heads, rests);
                if (!cadence.isEmpty()) {
                    for (int slot = 0; slot < cadence.size(); slot++) {
                        var head = cadence.get(slot);
                        rewrite[head.owner] = slot < 6 ? withTriplet(head.note, slot % 3 == 0 ? 1 : 0) : head.note;
                    }
                    excludedRests.addAll(rests);
                    changed = true;
                    continue;
                }
            }

            if (meter.numerator() == 4 && heads.size() == 24
                    && raw != null && raw.length == gray.length
                    && score.rests().stream().noneMatch(r -> r.measureIndex() == index)) {
                var overread = singleThirdRailAlias(
                        raster, new Raster(raw, width, height),
                        score.measures().get(barIndex), heads);
                for (var head : overread) {
                    rewrite[head.owner] = withRhythm(
                            head.note, 0, 2, head.note.tupletDivisor(), head.note.tupletNormalNotes());
                    changed = true;
                }
                continue;
            }
            if (meter.numerator() == 6 && heads.size() == 19 && raw != null && raw.length == gray.length
                    && score.rests().stream().noneMatch(r -> r.measureIndex() == index)) {
                var corrected = maskedDottedTripletBar(
                        raster, new Raster(raw, width, height), score.measures(), index, heads);
                if (corrected != null) {
                    remove[corrected.maskOwner] = true;
                    for (int slot = 0; slot < corrected.lane.size(); slot++) {
                        var note = corrected.lane.get(slot);
                        rewrite[note.owner] = withRhythm(
                                note.note, slot % 3 == 0 ? 1 : 0, slot % 3 == 1 ? 2 : 1, 3, 2);
                    }
                    changed = true;
                    continue;
                }
            }
            if ((meter.numerator() == 4 || meter.numerator() == 6)
                    && heads.size() == meter.numerator() * 3 && raw != null && raw.length == gray.length
                    && score.rests().stream().noneMatch(r -> r.measureIndex() == index)) {
                var lane = heads.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
                boolean openingSixFour = index == 0 && meter.numerator() == 6 && meter.measureIndex() == 0
                        && !score.meterChanges().isEmpty() && score.meterChanges().get(0).equals(meter);
                var original = new Raster(raw, width, height);
                boolean internalSixFour = meter.numerator() == 6
                        && provesInternalMeter(raw, gray, labels, width, height, score.measures(), index, notes, meter)
                        && qualifiedAliasedMonophonic(raster, original, score.measures(),
                                index, lane, false, true, true, -1);
                if (internalSixFour) {
                    var marks = dottedEngraving(raster, original, score.measures(), index, lane, true);
                    if (!marks.isEmpty()) {
                        var complete = completeDottedEngraving(beams, lane, heads, marks);
                        if (complete != beams) numberedOwners.add(lane.get(1));
                        beams = complete;
                    }
                }
                if (internalSixFour || qualifiedAliasedMonophonic(raster, original,
                        score.measures(), index, lane, openingSixFour, false)
                        || meter.numerator() == 4
                            && qualifiedStaffAttachedGroups(raster, new Raster(raw, width, height),
                                    score.measures().get(index), lane)) {
                    for (int slot = 0; slot < lane.size(); slot++) {
                        var n = lane.get(slot);
                        rewrite[n.owner] = withRhythm(n.note, slot % 3 == 0 ? 1 : 0, slot % 3 == 1 ? 2 : 1, 3, 2);
                    }
                    changed = true;
                    continue;
                }
            }
            if (meter.numerator() == 4
                    && heads.size() >= 13 && heads.size() <= 15
                    && raw != null
                    && raw.length == gray.length
                    && score.rests().stream().noneMatch(r -> r.measureIndex() == index)) {
                var triplets = heads.size() == 15
                        ? qualifiedFourGroupsWithCornerAndHooks(raster, new Raster(raw, width, height),
                                score.measures().get(barIndex), heads)
                        : qualifiedFourGroupsWithBeamAliases(
                                raster,
                                new Raster(raw, width, height),
                                score.measures().get(barIndex),
                                heads);
                if (triplets == null && heads.size() == 14)
                    triplets = qualifiedOpposedGroupsWithEndGuests(
                            raster, new Raster(raw, width, height), score.measures(), barIndex, heads);
                if (triplets == null && heads.size() == 13)
                    triplets = qualifiedLedgerGroupsWithClosingGuest(
                            raster, new Raster(raw, width, height), score.measures().get(barIndex), heads);
                if (triplets == null && heads.size() == 13 && labels != null && labels.length == gray.length) {
                    var kept = heads.stream().filter(h -> h.note.stemDirection() == -1)
                            .sorted(Comparator.comparingDouble(Head::x)).toList();
                    var masks = heads.stream().filter(h -> h.note.stemDirection() != -1).toList();
                    if (kept.size() == 12 && masks.size() == 1 && staffAttachedHookGuest(masks.get(0).note)
                            && qualifiedStaffAttachedGroups(raster, new Raster(raw, width, height),
                                    score.measures().get(barIndex), kept, masks.get(0), labels)) {
                        triplets = new ArrayList<>(kept.size());
                        for (int slot = 0; slot < kept.size(); slot++) {
                            var head = kept.get(slot);
                            triplets.add(new Head(head.owner, withRhythm(head.note,
                                    slot % 3 == 0 ? 1 : 0, slot % 3 == 1 ? 2 : 1, 3, 2), head.x, head.y));
                        }
                    }
                }
                if (triplets == null && heads.size() == 13
                        && provesInternalMeter(raw, gray, labels, width, height, score.measures(), index, notes, meter)) {
                    triplets = internalOpeningHookAlias(raster, new Raster(raw, width, height), score.measures(),
                            index, heads);
                    if (triplets != null) {
                        var marks = dottedEngraving(raster, new Raster(raw, width, height),
                                score.measures(), index, triplets, false);
                        if (!marks.isEmpty()) beams = completeDottedEngraving(beams, triplets, heads, marks);
                    }
                }
                if (triplets != null) {
                    for (var head : heads) remove[head.owner] = true;
                    for (int slot = 0; slot < triplets.size(); slot++) {
                        var head = triplets.get(slot);
                        remove[head.owner] = false;
                        rewrite[head.owner] = withTriplet(head.note, slot % 3 == 0 ? 1 : 0);
                    }
                    changed = true;
                    continue;
                }
            }
            if (meter.numerator() == 4 && heads.size() == 12
                    && raw != null && raw.length == gray.length
                    && score.rests().stream().noneMatch(r -> r.measureIndex() == index)) {
                heads.sort(Comparator.comparingDouble(Head::x));
                if (qualifiedFourGroups(raster, new Raster(raw, width, height),
                        score.measures().get(barIndex), heads)) {
                    for (int slot = 0; slot < heads.size(); slot++) {
                        var owner = heads.get(slot);
                        rewrite[owner.owner] = withTriplet(owner.note, slot % 3 == 0 ? 1 : 0);
                    }
                    changed = true;
                }
                continue;
            }
            if (meter.numerator() == 4 && heads.size() == 16 && raw != null && raw.length == gray.length
                    && score.rests().stream().noneMatch(r -> r.measureIndex() == index)) {
                var lane = qualifiedFullyMaskedGroups(raster, new Raster(raw, width, height),
                        score.measures().get(index), heads);
                if (lane != null) {
                    for (var head : heads) remove[head.owner] = true;
                    for (int slot = 0; slot < lane.size(); slot++) {
                        var head = lane.get(slot);
                        remove[head.owner] = false;
                        rewrite[head.owner] = withRhythm(head.note, slot % 3 == 0 ? 1 : 0,
                                slot % 3 == 1 ? 2 : 1, 3, 2, 0, 0);
                    }
                    changed = true;
                    continue;
                }
            }
            boolean mixed = meter.numerator() == 4 && (heads.size() == 15 || heads.size() == 16);
            if (mixed || meter.numerator() == 6 && heads.size() == 20) {
                if (score.rests().stream().anyMatch(r -> r.measureIndex() == index)) continue;
            } else continue;
            if (mixed) {
                var bar = score.measures().get(barIndex);
                var kept = heads.size() == 16 ? qualifiedMixedRun(raster, bar, heads) : null;
                boolean recoveredRibbon = kept == null;
                if (recoveredRibbon && raw != null && raw.length == gray.length) {
                    kept = mixedRibbonRun(raster, new Raster(raw, width, height), bar, heads);
                    if (kept != null) {
                        var projected = mixedRibbonBeams(beams, kept, heads);
                        if (projected == beams) continue;
                        beams = projected;
                    }
                }
                if (kept == null) continue;
                for (var head : heads) remove[head.owner] = true;
                for (int slot = 0; slot < kept.size(); slot++) {
                    var head = kept.get(slot);
                    remove[head.owner] = false;
                    if (slot < 6)
                        rewrite[head.owner] = withTriplet(head.note, slot % 3 == 0 ? 1 : 0);
                    else if (recoveredRibbon && (slot & 1) == 1)
                        rewrite[head.owner] = withRhythm(head.note, 0, 3, 1, 1);
                }
                changed = true;
                continue;
            }
            if (heads.stream().anyMatch(h -> !ordinary(h.note))) continue;
            heads.sort(Comparator.comparingDouble(Head::x));
            var retained=qualified(raster,score.measures().get(barIndex),heads);
            if (retained==null) continue;
            for (var head:heads) remove[head.owner]=true;
            for (int slot=0;slot<retained.size();slot++) {
                var head=retained.get(slot);
                remove[head.owner]=false;
                rewrite[head.owner]=withTriplet(head.note,slot%3==0 ? 1 : 0);
            }
            changed=true;
        }
        if (!changed) return score;
        var edited = new ArrayList<ScoreNoteEvent>();
        var owners = new ArrayList<Integer>();
        for (int i=0;i<notes.size();i++) {
            if (remove[i]) continue;
            owners.add(i);
            edited.add(rewrite[i]==null ? notes.get(i) : rewrite[i]);
        }
        var changedNotes = score.withBeamSource(beams).withNotes(edited, owners);
        if (!numberedOwners.isEmpty()) changedNotes = changedNotes.withExpressiveEvents(
                changedNotes.expressiveEvents().stream().filter(event ->
                        numberedOwners.stream().noneMatch(owner -> numeralFermataAlias(event, owner, width))).toList());
        if (excludedRests.isEmpty()) return changedNotes;
        return changedNotes.withRests(
                changedNotes.rests().stream().filter(rest -> !excludedRests.contains(rest)).toList());
    }

    /** Octave partners can disagree on a hook, or both borrow the same printed staff rule. */
    private static List<Head> octaveSecondaryHookAlias(
            Raster normal, Raster raw, List<MeasureRegion> regions, int index, List<Head> candidates) {
        var bar = regions.get(index);
        if (bar.cadenceQuarterBeats() != 0) return List.of();
        float gap = (bar.bottom() - bar.top()) * normal.height / 8.2f;
        if (gap < 10 || gap > 27) return List.of();
        var heads = candidates.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        if (heads.get(0).note.positionInMeasure() > .18f
                || heads.get(23).note.positionInMeasure() < .80f) return List.of();
        int clef = heads.get(0).note.clefBottomDiatonic();
        int alias = -1;
        boolean borrowedRule = false;
        for (int slot = 0; slot < 12; slot++) {
            var a = heads.get(slot * 2);
            var b = heads.get(slot * 2 + 1);
            int offset = slot % 3;
            int direction = heads.get((slot - offset) * 2).note.stemDirection();
            int dots = offset == 0 ? 1 : 0;
            int beams = offset == 1 ? 2 : 1;
            if (!ordinary(a.note) || !ordinary(b.note)
                    || a.note.clefBottomDiatonic() != clef || b.note.clefBottomDiatonic() != clef
                    || a.note.stemDirection() != direction || b.note.stemDirection() != direction
                    || a.note.augmentationDots() != dots || b.note.augmentationDots() != dots
                    || a.note.unbeamedDurationBeats() != 0 || b.note.unbeamedDurationBeats() != 0
                    || Math.abs(a.note.staffStep() - b.note.staffStep()) != 7
                    || b.x - a.x > .25f * gap || Math.abs(a.y - b.y) < 3.1f * gap
                    || Math.abs(a.y - b.y) > 3.9f * gap) return List.of();
            if (slot > 0) {
                float step = a.x - heads.get((slot - 1) * 2).x;
                if (step < 1.5f * gap || step > 4.15f * gap) return List.of();
            }
            int firstBeams = a.note.beamCount(), secondBeams = b.note.beamCount();
            if (firstBeams == beams && secondBeams == beams) continue;
            if (alias >= 0) return List.of();
            if (offset == 0 && direction == -1 && firstBeams == 2 && secondBeams == 2) {
                borrowedRule = true;
            } else if (offset != 1 || Math.min(firstBeams, secondBeams) != 1
                    || Math.max(firstBeams, secondBeams) != 2) {
                return List.of();
            }
            alias = slot * 2;
        }
        if (alias < 0) return List.of();

        int firstIndex = borrowedRule ? alias : alias - 2;
        var first = beamSide(heads.get(firstIndex), heads.get(firstIndex + 1));
        var middle = beamSide(heads.get(firstIndex + 2), heads.get(firstIndex + 3));
        var last = beamSide(heads.get(firstIndex + 4), heads.get(firstIndex + 5));
        var other = middle == heads.get(firstIndex + 2) ? heads.get(firstIndex + 3) : heads.get(firstIndex + 2);
        float left = heads.get(0).x, right = heads.get(23).x;
        for (var raster : List.of(normal, raw)) {
            var track = staffTrack(raster, bar, gap);
            if (track == null || !(WrappedMeasureBoundaries.confirmsClosedWithVerifiedStaff(
                    raster.ink, raster.width, raster.height, bar, left, right, track)
                    || borrowedRule && WrappedMeasureBoundaries.confirmsInitialStaffBar(
                            raster.ink, raster.width, raster.height, regions, index, left, right))) return List.of();
            float localGap = track.at(middle.x)[1];
            if (!dot(raster, first, middle, localGap)) return List.of();
            if (borrowedRule) {
                if (!staffRuleBeforeHook(raster, track, first, middle, last, localGap)) return List.of();
            } else if (!sharedOctaveHook(raster, track, first, middle, other, last, localGap)) {
                return List.of();
            }
        }
        if (borrowedRule) return heads.subList(alias, alias + 2);
        return List.of(heads.get(alias).note.beamCount() == 1 ? heads.get(alias) : heads.get(alias + 1));
    }

    private static boolean staffRuleBeforeHook(
            Raster raster, StaffPitchTrack track, Head first, Head middle, Head last, float gap) {
        var preceding = bridge(raster, track, first, middle, gap);
        var following = bridge(raster, track, middle, last, gap);
        if (preceding == null || following == null
                || Math.abs(preceding.b.x - following.a.x) > .13f * gap
                || Math.abs(preceding.b.y - following.a.y) > .13f * gap) return false;
        int stop = preceding.b.x - Math.round(gap * 1.08f);
        int start = preceding.a.x + Math.round(gap * 1.12f);
        if (stop - start < gap * .8f) return false;
        for (int x = start; x <= stop; x++) {
            if (!innerRailColumn(raster, track, preceding, x, gap, false)) return false;
        }
        for (int x = preceding.b.x - Math.round(gap * .66f);
                x <= preceding.b.x - Math.round(gap * .21f); x++) {
            if (!innerRailColumn(raster, track, preceding, x, gap, true)) return false;
        }
        return true;
    }

    private static boolean innerRailColumn(
            Raster raster, StaffPitchTrack track, Bridge beam, int x, float gap, boolean hook) {
        int center = Math.round(beam.center(x));
        float[] rule = track.at(x);
        int staffInk = 0, otherInk = 0;
        for (int depth = Math.round(gap * .42f); depth <= Math.round(gap * 1.25f); depth++) {
            int y = center - depth;
            if (!raster.dark(x, y)) continue;
            if (nearRule(rule, y, .195f)) staffInk++;
            else otherInk++;
        }
        return hook ? otherInk >= Math.round(gap * .25f) : staffInk > 0 && otherInk <= 1;
    }

    private static Head beamSide(Head a, Head b) {
        return (a.y - b.y) * a.note.stemDirection() < 0 ? a : b;
    }

    private static boolean sharedOctaveHook(
            Raster p, StaffPitchTrack track, Head a, Head b, Head partner, Head c, float gap) {
        var budget = new PrintedBeamShafts.Budget(250_000);
        var owner = PrintedBeamShafts.at(p.ink, p.width, p.height, b.x, b.y, gap, budget);
        var peer = PrintedBeamShafts.at(p.ink, p.width, p.height, partner.x, partner.y, gap, budget);
        if (owner.isEmpty() && peer.size() == 1 && !budget.refused()
                && b.note.stemDirection() == -1) {
            var shaft = peer.get(0);
            var firstBridge = bridge(p, track, a, b, gap);
            if (shaft.direction() != 1 || firstBridge == null
                    || firstBridge.b.x < shaft.left() || firstBridge.b.x > shaft.right()
                    || Math.abs(firstBridge.b.y - shaft.endY()) > .17f * gap)
                return false;
            return downstemSecondaryHook(p, track, a, b, c, gap);
        }
        if (owner.size() != 1 || peer.size() != 1 || budget.refused()) return false;
        var upper = owner.get(0);
        var lower = peer.get(0);
        if (upper.direction() != lower.direction()
                || upper.direction() != -b.note.stemDirection()
                || Math.max(upper.left(), lower.left()) > Math.min(upper.right(), lower.right())
                || Math.abs(upper.endY() - lower.endY()) > .17f * gap) return false;
        if (upper.direction() > 0) return downstemSecondaryHook(p, track, a, b, c, gap);
        var first = PrintedBeamShafts.at(p.ink, p.width, p.height, a.x, a.y, gap, budget);
        var after = PrintedBeamShafts.at(p.ink, p.width, p.height, c.x, c.y, gap, budget);
        return first.size() == 1 && after.size() == 1 && !budget.refused()
                && WrappedEighthTriplets.matchesRailBridge(p.ink, p.width, p.height, first.get(0), upper, gap, true)
                && WrappedEighthTriplets.matchesRailBridge(p.ink, p.width, p.height, upper, after.get(0), gap, true)
                && hasOnlySecondaryHook(p.ink, p.width, p.height, first.get(0), upper, gap);
    }

    /** Two dotted triplets and the final printed half fill a closed common-time measure. */
    private static List<Head> closedTripletsIntoHalf(
            Raster normal, Raster raw, MeasureRegion bar, List<Head> candidates, List<ScoreRestEvent> rests) {
        if (bar.cadenceQuarterBeats() != 0 || rests.size() > 1) return List.of();
        var heads = candidates.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        float gap = (bar.bottom() - bar.top()) * raw.height / 8.2f;
        float left = bar.left() * raw.width, right = bar.right() * raw.width;
        var terminal = heads.get(6);
        if (terminal.note.followingRestBeats() != (rests.isEmpty() ? 0f : .125f)) return List.of();
        var halfNote = withRhythm(terminal.note, 0, 0, 1, 1, 0);
        var half = new Head(terminal.owner,halfNote,terminal.x,terminal.y);
        if (gap < 10 || gap > 25 || (right - left) < gap * 14 || (right - left) > gap * 24
                || heads.get(0).x - left < gap * .78f || heads.get(0).x - left > gap * 2.1f
                || right - half.x < gap * 2.2f || right - half.x > gap * 4.4f
                || !ordinary(half.note) || terminal.note.beamCount() != 0 || terminal.note.augmentationDots() != 0
                || half.note.unbeamedDurationBeats() != 2 || half.note.stemDirection() != 1)
            return List.of();

        var triplets = new ArrayList<Head>(6);
        for (int i = 0; i < 6; i++) {
            var head = heads.get(i);
            var note = head.note;
            int slot = i % 3;
            if (!ordinaryRhythm(note) || (note.stemDirection() != 0 && note.stemDirection() != 1)
                    || note.clefBottomDiatonic() != half.note.clefBottomDiatonic()
                    || note.unbeamedDurationBeats() != 0
                    || note.beamCount() != (slot == 1 ? 2 : 1)
                    || note.augmentationDots() != (slot == 0 ? 1 : 0)) return List.of();
            triplets.add(new Head(head.owner,note.withStemDirection(1),head.x,head.y));
        }
        for (var raster : List.of(normal, raw)) {
            var track = staffTrack(raster, bar, gap);
            if (track == null || !barline(raster,left,track.at(left))) return List.of();
            float[] rules = track.at(right);
            var staff = new PlayingTechniqueDetector.Staff(rules[0] - 4 * rules[1], rules[0], rules[1],0,1);
            int close = PrintedCadenceDurations.boundaryColumn(raster.ink,raster.width,staff,right,true,false);
            if (close < 0 || (!rests.isEmpty() && !restOwnedByClosingRule(rests.get(0),raster,bar,staff,close)))
                return List.of();
            var halfShaft = PrintedBeamShafts.at(raster.ink,raster.width,raster.height,half.x,half.y,
                    track.at(half.x)[1],new PrintedBeamShafts.Budget(150_000));
            if (halfShaft.size()!=1 || halfShaft.get(0).direction()!=-1
                    || !HollowLedgerCenter.straddles(raster.ink,raster.width,raster.height,half.x,half.y,track.at(half.x)[1])
                    || WrappedTripletDotInk.dottedHalfCore(raster.ink,raster.width,raster.height,
                            half.x,half.y,close,track.at(half.x)[1]) != null) return List.of();
            var roofs = new ArrayList<PrintedBeamShafts.Shaft>(6);
            for (int i = 0; i < 6; i+=3) {
                var a=triplets.get(i); var b=triplets.get(i+1); var c=triplets.get(i+2);
                float local=track.at(b.x)[1];
                if (!dot(raster,a,b,local) || dot(raster,b,c,local)
                        || b.x-a.x < local*1.85f || b.x-a.x > local*3.1f
                        || c.x-b.x < local*1.55f || c.x-b.x > local*2.3f) return List.of();
                var lead=PrintedBeamShafts.at(raster.ink,raster.width,raster.height,a.x,a.y,local,
                        new PrintedBeamShafts.Budget(125_000));
                if (lead.size()!=1 || lead.get(0).direction()!=-1) return List.of();
                var mid=provedPaleUpstem(raster,lead.get(0),b,local);
                if (mid==null || !hasOnlySecondaryHook(raster.ink,raster.width,raster.height,lead.get(0),mid,local))
                    return List.of();
                var last=provedPaleUpstem(raster,mid,c,local);
                if (last==null) return List.of();
                roofs.add(lead.get(0)); roofs.add(mid); roofs.add(last);
            }
            if (!WrappedEighthTriplets.matchesRailBridge(raster.ink,raster.width,raster.height,roofs.get(2),roofs.get(3),gap,false)
                    || !WrappedEighthTriplets.matchesRailBridge(
                            raster.ink,raster.width,raster.height,roofs.get(5),halfShaft.get(0),gap,false)) return List.of();
        }
        triplets.add(half);
        return triplets;
    }

    private static boolean restOwnedByClosingRule(
            ScoreRestEvent rest, Raster raster, MeasureRegion bar, PlayingTechniqueDetector.Staff staff, int close) {
        float gap = staff.gap();
        float x = (bar.left() + rest.positionInMeasure() * (bar.right() - bar.left())) * raster.width;
        float y = rest.pageY() * raster.height;
        return !rest.isFullMeasure() && rest.durationBeats() == .125
                && rest.staffIndex() == staff.index() && rest.staffCount() == staff.count()
                && rest.pageHeight() * raster.height >= gap * 3.8f && rest.pageHeight() * raster.height <= gap * 4.2f
                && Math.abs(x-close)<gap*.28f && Math.abs(y-(staff.top()+gap*2))<gap*.33f
                && hasRightClosingStroke(raster,staff,close)
                && clearOfRestBody(raster,staff,close);
    }

    private static boolean hasRightClosingStroke(
            Raster raster, PlayingTechniqueDetector.Staff staff, int close) {
        float gap = staff.gap();
        int left = Math.round(close + gap * .43f);
        int right = Math.round(close + gap * .81f);
        int top = Math.round(staff.top() + gap * .23f);
        int bottom = Math.round(staff.bottom() - gap * .23f);
        int inkRows = 0;
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x < right; x++) {
                if (raster.dark(x,y) && raster.dark(x+1,y)) {
                    inkRows++;
                    break;
                }
            }
        }
        return inkRows >= (bottom-top+1)*.85f;
    }

    private static boolean clearOfRestBody(Raster raster, PlayingTechniqueDetector.Staff staff, int close) {
        float gap=staff.gap();
        int left=close-Math.round(gap*1.1f), right=close-Math.round(gap*.25f);
        int top=Math.round(staff.top()+gap*.50f), bottom=Math.round(staff.bottom()-gap*.50f);
        for (int y=top;y<=bottom;y++) {
            float band=(y-staff.top())/gap;
            if (Math.abs(band-Math.round(band))<.23f) continue;
            for(int x=left;x<=right;x++) if(raster.dark(x,y)) return false;
        }
        return true;
    }

    private static PrintedBeamShafts.Shaft provedPaleUpstem(
            Raster r, PrintedBeamShafts.Shaft prior, Head head, float gap) {
        var measured=PrintedBeamShafts.at(r.ink,r.width,r.height,head.x,head.y,gap,
                new PrintedBeamShafts.Budget(125_000));
        if (measured.size()==1 && measured.get(0).direction()==-1
                && WrappedEighthTriplets.matchesRailBridge(r.ink,r.width,r.height,prior,measured.get(0),gap,true))
            return measured.get(0);
        if (!measured.isEmpty()) return null;
        int shoulder=Math.round(head.y-gap*.20f);
        for (int x=Math.round(head.x+gap*.43f);x<=Math.round(head.x+gap*.66f);x++) {
            if (!r.dark(x,shoulder) || !r.dark(x+1,shoulder)) continue;
            int start = prior.endY() - Math.round(gap*.25f);
            int end = prior.endY() + Math.round(gap*.48f);
            for (int tip=start;tip<=end;tip++) {
                if (!r.dark(x,tip) || !r.dark(x+1,tip)
                        || r.dark(x,tip-1) && r.dark(x+1,tip-1) || shoulder-tip<gap*2) continue;
                int together=0, longestGap=0, blank=0;
                for (int y=tip;y<=shoulder;y++) {
                    if (r.dark(x,y) && r.dark(x+1,y)) { together++; blank=0; }
                    else longestGap=Math.max(longestGap,++blank);
                }
                if (together < (shoulder-tip+1)*.70f || longestGap>gap*.77f) continue;
                var guess = new PrintedBeamShafts.Shaft(x,x+1,shoulder,tip,-1,List.of());
                if (WrappedEighthTriplets.matchesRailBridge(r.ink,r.width,r.height,prior,guess,gap,true))
                    return guess;
            }
        }
        return null;
    }

    /** A whole dotted-triplet lane can have one false third hook on an upstem octave. */
    private static List<Head> singleThirdRailAlias(
            Raster normal, Raster raw, MeasureRegion bar, List<Head> candidates) {
        if (bar.cadenceQuarterBeats() != 0) return List.of();
        float gap = (bar.bottom() - bar.top()) * normal.height / 8.2f;
        if (gap < 10 || gap > 27) return List.of();
        var heads = candidates.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        if (heads.get(0).note.positionInMeasure() > .18f
                || heads.get(23).note.positionInMeasure() < .80f) return List.of();
        int suspect = -1;
        int clef = heads.get(0).note.clefBottomDiatonic();
        for (int i = 0; i < heads.size(); i += 2) {
            var a = heads.get(i);
            var b = heads.get(i + 1);
            int offset = (i / 2) % 3;
            int direction = heads.get(i - 2 * offset).note.stemDirection();
            int expectedDot = offset == 0 ? 1 : 0;
            if (!ordinary(a.note) || !ordinary(b.note)
                    || a.note.clefBottomDiatonic() != clef || b.note.clefBottomDiatonic() != clef
                    || a.note.stemDirection() != direction || b.note.stemDirection() != direction
                    || a.note.augmentationDots() != expectedDot || b.note.augmentationDots() != expectedDot
                    || a.note.beamCount() != b.note.beamCount()
                    || a.note.unbeamedDurationBeats() != 0 || b.note.unbeamedDurationBeats() != 0
                    || Math.abs(a.note.staffStep() - b.note.staffStep()) != 7
                    || b.x - a.x > .25f * gap || Math.abs(a.y - b.y) < 3.1f * gap
                    || Math.abs(a.y - b.y) > 3.9f * gap) return List.of();
            if (i > 0) {
                float advance = a.x - heads.get(i - 2).x;
                if (advance < 1.5f * gap || advance > 3.9f * gap) return List.of();
            }
            if (a.note.beamCount() == (offset == 1 ? 2 : 1)) continue;
            if (suspect >= 0 || offset != 1 || direction != 1 || a.note.beamCount() != 3)
                return List.of();
            suspect = i;
        }
        if (suspect < 0) return List.of();
        float first = heads.get(0).x, last = heads.get(23).x;
        for (var raster : List.of(normal, raw)) {
            if (!WrappedMeasureBoundaries.confirmsClosed(
                    raster.ink, raster.width, raster.height, bar, first, last)
                    || !provedTwoBeams(raster, heads, suspect, gap)) return List.of();
        }
        return heads.subList(suspect, suspect + 2);
    }

    private static boolean provedTwoBeams(Raster p, List<Head> heads, int center, float gap) {
        var budget = new PrintedBeamShafts.Budget(250_000);
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(3);
        for (int i = center - 2; i <= center + 2; i += 2) {
            var a = heads.get(i);
            var b = heads.get(i + 1);
            var top = PrintedBeamShafts.at(p.ink, p.width, p.height, a.x, a.y, gap, budget);
            var bottom = PrintedBeamShafts.at(p.ink, p.width, p.height, b.x, b.y, gap, budget);
            if (top.size() != 1 || bottom.size() != 1 || budget.refused()) return false;
            var u = top.get(0);
            var l = bottom.get(0);
            if (u.direction() != -1 || l.direction() != -1
                    || Math.max(u.left(), l.left()) > Math.min(u.right(), l.right())
                    || Math.abs(u.endY() - l.endY()) > .15f * gap) return false;
            shafts.add(l);
        }
        var left = shafts.get(0);
        var own = shafts.get(1);
        var right = shafts.get(2);
        return WrappedEighthTriplets.matchesRailBridge(p.ink, p.width, p.height, left, own, gap, true)
                && WrappedEighthTriplets.matchesRailBridge(p.ink, p.width, p.height, own, right, gap, true)
                && hasOnlySecondaryHook(p.ink, p.width, p.height, left, own, gap);
    }

    static boolean hasOnlySecondaryHook(
            byte[] gray, int width, int height, PrintedBeamShafts.Shaft preceding,
            PrintedBeamShafts.Shaft owner, float gap) {
        int left = (preceding.left() + preceding.right()) / 2;
        int shaft = (owner.left() + owner.right()) / 2;
        if (preceding.direction() != -1 || owner.direction() != -1
                || shaft - left < 1.6f * gap || shaft - left > 4 * gap) return false;
        float slope = (owner.endY() - preceding.endY()) / (float) (shaft - left);
        if (Math.abs(slope) > .42f || OwnedTertiaryBeamlets.ownsThirdRailAtUpStemEnd(
                gray, width, height, new int[] {shaft, owner.endY(), -1},
                new int[] {left, preceding.endY(), -1}, gap)) return false;
        var raster = new Raster(gray, width, height);
        int near = Math.round(.29f * gap);
        int far = Math.round(.66f * gap);
        for (int depth = Math.round(.90f * gap); depth <= Math.round(1.26f * gap); depth++) {
            if (!hookCore(raster, shaft, owner.endY(), slope, depth, near, far)) continue;
            int remote = Math.round(1.12f * gap);
            if (!hookCore(raster, shaft, owner.endY(), slope, depth, remote, remote + 2))
                return true;
        }
        return false;
    }

    private static boolean hookCore(
            Raster p, int shaft, int tip, float slope, int depth, int first, int last) {
        for (int dx = first; dx <= last; dx++) {
            int y = Math.round(tip - slope * dx) + depth;
            if (!p.dark(shaft - dx, y - 1) || !p.dark(shaft - dx, y)
                    || !p.dark(shaft - dx, y + 1)) return false;
        }
        return true;
    }

    private static boolean ordinary(ScoreNoteEvent n) {
        return ordinaryRhythm(n) && Math.abs(n.stemDirection()) == 1;
    }

    private static boolean ordinaryRhythm(ScoreNoteEvent n) {
        return ordinaryRhythm(n, false);
    }

    private static boolean ordinaryRhythm(ScoreNoteEvent n, boolean allowGraceAlias) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED
                && n.staffIndex() == 0
                && n.staffCount() == 1
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.harmonicSemitones() == 0
                && n.openStringTouch() == null
                && !n.crossStaffBeam()
                && n.tupletDivisor() == 1
                && n.leadingRestBeats() == 0
                && n.followingRestBeats() == 0
                && !n.tiedFromPrevious()
                && n.boundaryTies() == 0
                && (allowGraceAlias || (n.articulations() & NoteOrnament.GRACE) == 0)
                && n.clefBottomDiatonic() != ScoreNoteEvent.CLEF_UNKNOWN;
    }

    private static List<Head> qualified(Raster p, MeasureRegion bar, List<Head> candidates) {
        if (candidates.get(0).note.positionInMeasure()>.12f
                || candidates.get(19).note.positionInMeasure()<.88f) return null;
        float left=bar.left()*p.width, right=bar.right()*p.width;
        float coarseGap=(bar.bottom()-bar.top())*p.height/8.2f;
        if (coarseGap<10 || coarseGap>28 || right-left<26*coarseGap || right-left>50*coarseGap)
            return null;
        var track = staffTrack(p, bar, coarseGap);
        if (track == null) return null;
        if (!barline(p,left,track.at(left)) || !barline(p,right,track.at(right))) return null;
        List<Head> result=null;
        int clef=candidates.get(0).note.clefBottomDiatonic();
        for (int first=1;first<19;first++) {
            var firstExtra=candidates.get(first);
            if (!couldBeBorrowed(p,track,firstExtra)) continue;
            for (int second=first+1;second<19;second++) {
                var secondExtra=candidates.get(second);
                if (!couldBeBorrowed(p,track,secondExtra)) continue;
                var kept=new ArrayList<Head>();
                for (int i=0;i<candidates.size();i++)
                    if (i!=first && i!=second) kept.add(candidates.get(i));
                if (!pattern(kept,clef) || !printedGroups(p,track,kept,List.of(firstExtra,secondExtra)))
                    continue;
                if (result!=null) return null;
                result=kept;
            }
        }
        return result;
    }

    /** A staff-opening dotted triplet run can borrow both the final hook and its following rail. */
    private static List<Head> qualifiedOpposedGroupsWithEndGuests(Raster normal, Raster raw,
            List<MeasureRegion> measures, int index, List<Head> candidates) {
        if (index == 0 || candidates.size() != 14) return null;
        var bar = measures.get(index);
        float gap = (bar.bottom() - bar.top()) * raw.height / 8.2f;
        float left = bar.left() * raw.width, right = bar.right() * raw.width;
        if (gap < 10 || gap > 25 || bar.cadenceQuarterBeats() != 0
                || (bar.top() - measures.get(index - 1).bottom()) * raw.height < 3 * gap
                || right - left < 22 * gap || right - left > 35 * gap) return null;
        var guests = candidates.stream().filter(h -> beamGuest(h.note))
                .sorted(Comparator.comparingDouble(Head::x)).toList();
        if (guests.size() != 2 || guests.get(0).note.stemDirection() != 1
                || guests.get(0).note.beamCount() != 0 || guests.get(1).note.stemDirection() != 0) return null;
        var heads = candidates.stream().filter(h -> !guests.contains(h))
                .sorted(Comparator.comparingDouble(Head::x)).toList();
        if (heads.get(0).x - left < 1.75f * gap || right - heads.get(11).x < .80f * gap
                || right - heads.get(11).x > 1.65f * gap) return null;
        int clef = heads.get(0).note.clefBottomDiatonic();
        for (int i = 0; i < heads.size(); i++) {
            var n = heads.get(i).note;
            int beams = i % 3 == 1 && i != 10 ? 2 : 1;
            if (!ordinary(n) || n.clefBottomDiatonic() != clef || n.stemDirection() != (i < 6 ? 1 : -1)
                    || n.beamCount() != beams || n.unbeamedDurationBeats() != 0
                    || n.augmentationDots() != (i % 3 == 0 ? 1 : 0)) return null;
        }
        for (Raster plane : List.of(raw, normal)) {
            var track = WrappedMeasureBoundaries.verifiedTripletStaff(plane.ink, plane.width, plane.height, bar, true);
            if (track == null || !barline(plane, right, track.at(right))) return null;
            for (int i = 0; i < heads.size(); i += 3) {
                var a = heads.get(i);
                var b = heads.get(i + 1);
                var c = heads.get(i + 2);
                if (b.x - a.x < 2.15f * gap || b.x - a.x > 3.25f * gap
                        || c.x - b.x < 1.6f * gap || c.x - b.x > 3.05f * gap
                        || !dot(plane, a, b, gap) || dot(plane, b, c, gap)) return null;
                var first = bridge(plane, track, a, b, gap);
                var last = bridge(plane, track, b, c, gap);
                if (first == null || last == null || Math.abs(first.b.x - last.a.x) > 2
                        || Math.abs(first.b.y - last.a.y) > .22f * gap) return null;
                if (i < 9) {
                    var next = heads.get(i + 3);
                    if (next.x - c.x < 1.65f * gap || next.x - c.x > 3.3f * gap) return null;
                    if (c.note.stemDirection() == next.note.stemDirection()
                            && !SeparateBeamGroups.between(plane.ink, plane.width, plane.height,
                                    c.x, c.y, next.x, next.y, gap)) return null;
                } else if (!ownedShortHookMask(plane, track, b, guests.get(0), first, gap, .36f)
                        || !backwardHook(plane, track, first, .52f, 1.40f, .19f)
                        || !stemlessEndingTripletRail(plane, last, guests.get(1), gap)) return null;
            }
        }
        var result = new ArrayList<>(heads);
        var middle = heads.get(10);
        result.set(10, new Head(middle.owner, withRhythm(middle.note, 0, 2, 3, 2), middle.x, middle.y));
        return result;
    }

    private static boolean stemlessEndingTripletRail(Raster plane, Bridge rail, Head guest, float gap) {
        float slope = (rail.b.y - rail.a.y) / (float) (rail.b.x - rail.a.x);
        if (slope < -.48f || slope > -.15f || guest.x - rail.a.x < .55f * gap
                || rail.b.x - guest.x < .75f * gap || Math.abs(guest.y - rail.center(guest.x)) > .28f * gap)
            return false;
        var budget = new PrintedBeamShafts.Budget(40_000);
        if (!PrintedBeamShafts.at(plane.ink, plane.width, plane.height, guest.x, guest.y, gap, budget).isEmpty()
                || budget.refused()) return false;
        for (int x = Math.round(guest.x - .32f * gap); x <= Math.round(guest.x + .32f * gap); x++) {
            int center = Math.round(guest.y + slope * (x - guest.x));
            if (!plane.dark(x, center)) return false;
            int top = center, bottom = center;
            while (center - top < gap && plane.dark(x, top - 1)) top--;
            while (bottom - center < gap && plane.dark(x, bottom + 1)) bottom++;
            if (center - top < .28f * gap || center - top > .78f * gap
                    || bottom - center < .15f * gap || bottom - center > .48f * gap) return false;
        }
        return true;
    }


    /** High ledger strokes can overstate a closing beam while another short hook is lost. */
    private static List<Head> qualifiedLedgerGroupsWithClosingGuest(Raster normal, Raster raw, MeasureRegion bar,
            List<Head> candidates) {
        var guests = candidates.stream().filter(h -> beamGuest(h.note) && h.note.stemDirection() == 1
                && h.note.beamCount() == 1).toList();
        if (candidates.size() != 13 || guests.size() != 1 || bar.cadenceQuarterBeats() != 0) return null;
        var guest = guests.get(0);
        var heads = candidates.stream().filter(h -> h != guest).sorted(Comparator.comparingDouble(Head::x)).toList();
        float gap = (bar.bottom() - bar.top()) * raw.height / 8.2f;
        float left = bar.left() * raw.width, right = bar.right() * raw.width;
        if (gap < 10 || gap > 25 || right - left < 22 * gap || right - left > 32 * gap
                || heads.get(0).x - left < .75f * gap || right - heads.get(11).x < .75f * gap) return null;
        int clef = heads.get(0).note.clefBottomDiatonic();
        for (int i = 0; i < heads.size(); i++) {
            var n = heads.get(i).note;
            int beams = i == 2 || i % 3 == 1 && i != 4 ? 2 : 1;
            if (!ordinary(n) || n.clefBottomDiatonic() != clef || n.stemDirection() != -1
                    || n.beamCount() != beams || n.unbeamedDurationBeats() != 0
                    || n.augmentationDots() != (i % 3 == 0 ? 1 : 0)) return null;
        }
        for (Raster plane : List.of(raw, normal)) {
            var track = WrappedMeasureBoundaries.verifiedTripletStaff(plane.ink, plane.width, plane.height, bar, true);
            if (track == null || !barline(plane, left, track.at(left)) || !barline(plane, right, track.at(right)))
                return null;
            for (int i = 0; i < heads.size(); i += 3) {
                var a = heads.get(i); var b = heads.get(i + 1); var c = heads.get(i + 2);
                if (b.x - a.x < 2.05f * gap || b.x - a.x > 3.3f * gap
                        || c.x - b.x < 1.45f * gap || c.x - b.x > 2.7f * gap || !dot(plane, a, b, gap))
                    return null;
                if (dot(plane, b, c, gap)) {
                    var core = WrappedTripletDotInk.uniqueCore(plane.ink, plane.width, plane.height, b.x, b.y, c.x, gap);
                    if (core == null || !WrappedEighthTriplets.headConnectedLedger(
                            plane.ink, plane.width, plane.height, b.x, b.y, core, gap, .38f)) return null;
                }
                var first = bridge(plane, track, a, b, gap, 9f);
                var last = bridge(plane, track, b, c, gap, 9f);
                if (first == null || last == null || Math.abs(first.b.x - last.a.x) > .18f * gap
                        || Math.abs(first.b.y - last.a.y) > .22f * gap
                        || !backwardHook(plane, track, first, .52f, 1.40f, .19f)) return null;
                if (i == 0 && backwardHook(plane, track, last, .52f, 1.40f, .19f)) return null;
                if (i < 9) {
                    var next = heads.get(i + 3);
                    if (!SeparateBeamGroups.between(plane.ink, plane.width, plane.height, c.x, c.y, next.x, next.y, gap)
                            && !confirmsSeparateDownstems(plane, c, next, gap)) return null;
                } else if (!shaftSideTripletGuest(plane, track, first, b, guest, gap)) return null;
            }
        }
        var result = new ArrayList<>(heads);
        for (int slot : new int[]{2, 4}) {
            var head = heads.get(slot);
            result.set(slot, new Head(head.owner, withRhythm(head.note, 0, slot == 2 ? 1 : 2, 3, 2),
                    head.x, head.y));
        }
        return result;
    }

    private static boolean shaftSideTripletGuest(Raster plane, StaffPitchTrack track, Bridge beam,
            Head owner, Head guest, float gap) {
        int shaft = beam.b.x;
        if (Math.abs(shaft - guest.x) > .18f * gap || guest.y - owner.y < 2.5f * gap
                || guest.y - owner.y > 3.35f * gap || guest.y - beam.center(guest.x) < -.72f * gap
                || guest.y - beam.center(guest.x) > -.30f * gap) return false;
        var budget = new PrintedBeamShafts.Budget(50_000);
        if (!PrintedBeamShafts.at(plane.ink, plane.width, plane.height, guest.x, guest.y, gap, budget).isEmpty()
                || budget.refused()) return false;
        int x = Math.round(guest.x), y = Math.round(guest.y), radius = Math.round(.47f * gap);
        int owned = 0, foreign = 0;
        for (int col = x - radius; col <= x + radius; col++) {
            float line = beam.center(col);
            var rules = track.at(col);
            for (int row = y - radius; row <= y + radius; row++) {
                if (!plane.dark(col, row) || nearRule(rules, row, .18f)) continue;
                float depth = (row - line) / gap;
                if (Math.abs(col - shaft) <= .18f * gap
                        || col < shaft && depth > -1.4f && depth < -.34f
                        || Math.abs(depth) <= .43f) owned++;
                else foreign++;
            }
        }
        return owned >= gap && foreign <= 3;
    }


    /** A complete dotted bar can contribute one corner response and two borrowed hooks. */
    private static List<Head> qualifiedFourGroupsWithCornerAndHooks(Raster normal, Raster raw,
            MeasureRegion bar, List<Head> candidates) {
        var guests = candidates.stream().filter(h -> h.note.stemDirection() != -1)
                .sorted(Comparator.comparingDouble(Head::x)).toList();
        if (guests.size() != 3) return null;
        var corner = guests.get(0).note;
        if (corner.stemDirection() != 0 || corner.beamCount() != 2 || corner.unbeamedDurationBeats() != 0
                || corner.augmentationDots() != 0 || !ordinaryRhythm(corner)
                || corner.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                || (corner.articulations() & ~NoteArticulation.STACCATO) != 0
                || guests.subList(1, 3).stream().anyMatch(h -> !beamGuest(h.note) || h.note.stemDirection() != 1))
            return null;
        var heads = candidates.stream().filter(h -> h.note.stemDirection() == -1)
                .sorted(Comparator.comparingDouble(Head::x)).toList();
        if (heads.size() != 12) return null;
        var adjusted = new ArrayList<>(heads);
        int missed = -1;
        for (int i = 1; i < 12; i += 3) {
            var h = heads.get(i);
            if (h.note.beamCount() != 1) continue;
            if (missed >= 0) return null;
            missed = i;
            adjusted.set(i, new Head(h.owner, withRhythm(h.note, 0, 2,
                    h.note.tupletDivisor(), h.note.tupletNormalNotes()), h.x, h.y));
        }
        if (!qualifiedFourGroups(normal, raw, bar, adjusted)) return null;
        float gap = (bar.bottom() - bar.top()) * raw.height / 8.2f;
        for (Raster plane : List.of(raw, normal)) {
            var track = staffTrack(plane, bar, gap);
            if (track == null
                    || !ownsTripletBeamGuest(plane, track, heads.get(0), heads.get(1), heads.get(2),
                            guests.get(0), gap, .36f)) return null;
            int prior = 0;
            for (int j = 1; j < guests.size(); j++) {
                var guest = guests.get(j);
                int group = -1;
                for (int i = prior + 3; i < heads.size(); i += 3) {
                    if (ownsTripletBeamGuest(plane, track, heads.get(i), heads.get(i + 1), heads.get(i + 2),
                            guest, gap)) {
                        group = i;
                        break;
                    }
                }
                if (group < 0) return null;
                prior = group;
            }
            if (missed >= 0 && !downstemSecondaryHook(plane, track,
                    heads.get(missed - 1), heads.get(missed), heads.get(missed + 1), gap)) return null;
        }
        return adjusted;
    }


    /** Beam ink below a complete roster can be segmented as another head. */
    private static List<Head> qualifiedFourGroupsWithBeamAliases(
            Raster corrected, Raster raw, MeasureRegion region, List<Head> candidates) {
        var heads =
                candidates.stream()
                        .filter(h -> h.note.stemDirection() == -1)
                        .sorted(Comparator.comparingDouble(Head::x))
                        .toList();
        if (heads.size() != 12) return null;
        var aliases = candidates.stream().filter(h -> h.note.stemDirection() != -1).toList();
        if (aliases.stream().anyMatch(h -> !beamGuest(h.note))) return null;
        var adjusted = new ArrayList<>(heads);
        int missing = -1;
        for (int slot = 1; slot < 12; slot += 3) {
            var head = heads.get(slot);
            if (head.note.beamCount() != 1) continue;
            if (missing >= 0) return null;
            missing = slot;
            var n = head.note;
            adjusted.set(
                    slot,
                    new Head(
                            head.owner,
                            withRhythm(
                                    n,
                                    n.augmentationDots(),
                                    2,
                                    n.tupletDivisor(),
                                    n.tupletNormalNotes()),
                            head.x,
                            head.y));
        }
        if (!qualifiedFourGroups(corrected, raw, region, adjusted)) return null;
        float gap = (region.bottom() - region.top()) * raw.height / 8.2f;
        for (Raster plane : List.of(raw, corrected)) {
            var track = staffTrack(plane, region, gap);
            if (track == null) return null;
            var ownedGroups = new java.util.HashSet<Integer>();
            for (var guest : aliases) {
                int owner = -1;
                for (int i = 0; i < heads.size(); i += 3) {
                    if (!ownsTripletBeamGuest(
                            plane,
                            track,
                            heads.get(i),
                            heads.get(i + 1),
                            heads.get(i + 2),
                            guest,
                            gap)) continue;
                    if (owner >= 0) return null;
                    owner = i;
                }
                if (owner < 0 || !ownedGroups.add(owner)) return null;
            }
            if (missing >= 0
                    && !downstemSecondaryHook(
                            plane,
                            track,
                            heads.get(missing - 1),
                            heads.get(missing),
                            heads.get(missing + 1),
                            gap)) return null;
        }
        return adjusted;
    }

    /** Recovers four dotted triplets when each beam group contributes a false attack. */
    private static List<Head> qualifiedFullyMaskedGroups(Raster corrected, Raster raw, MeasureRegion bar,
            List<Head> candidates) {
        var heads = candidates.stream().filter(h -> h.note.stemDirection() == -1)
                .sorted(Comparator.comparingDouble(Head::x)).toList();
        if (heads.size() != 12 || candidates.size() != 16 || bar.cadenceQuarterBeats() != 0) return null;
        var aliases = candidates.stream().filter(h -> h.note.stemDirection() != -1)
                .sorted(Comparator.comparingDouble(Head::x)).toList();
        float gap = (bar.bottom() - bar.top()) * raw.height / 8.2f;
        float left = bar.left() * raw.width, right = bar.right() * raw.width;
        if (gap < 10 || gap > 26 || right - left < 23 * gap || right - left > 36 * gap) return null;

        int clef = heads.get(0).note.clefBottomDiatonic();
        int missingRail = 0;
        for (int i = 0; i < heads.size(); i++) {
            var n = heads.get(i).note;
            int slot = i % 3;
            if (!ordinary(n) || n.clefBottomDiatonic() != clef
                    || n.augmentationDots() != (slot == 0 ? 1 : 0)) return null;
            if (n.beamCount() == (slot == 1 ? 2 : 1) && n.unbeamedDurationBeats() == 0) continue;
            if (slot != 2 || n.beamCount() != 0 || n.unbeamedDurationBeats() != 1 || ++missingRail > 1) return null;
        }
        for (Raster plane : List.of(raw, corrected)) {
            var track = staffTrack(plane, bar, gap);
            if (track == null || !barline(plane, left, track.at(left)) || !barline(plane, right, track.at(right)))
                return null;
            for (int i = 0; i < heads.size(); i += 3) {
                var a = heads.get(i);
                var b = heads.get(i + 1);
                var c = heads.get(i + 2);
                var guest = aliases.get(i / 3);
                if (b.x - a.x < 2.2f * gap || b.x - a.x > 3.8f * gap
                        || c.x - b.x < 1.65f * gap || c.x - b.x > 2.8f * gap
                        || guest.x <= a.x || guest.x >= c.x
                        || !dot(plane, a, b, gap) || dot(plane, b, c, gap)) return null;

                var opening = bridge(plane, track, a, b, gap);
                var ending = bridge(plane, track, b, c, gap);
                if (opening == null || ending == null || Math.abs(opening.b.x - ending.a.x) > 2
                        || Math.abs(opening.b.y - ending.a.y) > .20f * gap) return null;

                boolean hookGuest = beamGuest(guest.note) && ownsTripletBeamGuest(plane, track, a, b, c, guest, gap);
                if (!hookGuest && (!primaryRibbonGuest(plane, track, opening, ending, a, c, guest, gap)
                        || !downstemSecondaryHook(plane, track, a, b, c, gap))) return null;
                if (i + 3 < heads.size()) {
                    var next = heads.get(i + 3);
                    if (!SeparateBeamGroups.between(plane.ink, plane.width, plane.height,
                            c.x, c.y, next.x, next.y, gap)) return null;
                }
            }
        }
        return heads;
    }

    /** Proves a reading entirely in a falling primary rail or on its shared closing shaft. */
    private static boolean primaryRibbonGuest(Raster plane, StaffPitchTrack track, Bridge opening, Bridge ending,
            Head a, Head c, Head guest, float gap) {
        var n = guest.note;
        if (!plainBeamGuest(n)) return false;
        boolean stemless = n.stemDirection() == 0 && n.beamCount() == 1 && n.unbeamedDurationBeats() == 0;
        boolean shared = n.stemDirection() == 1 && n.beamCount() == 0 && n.unbeamedDurationBeats() == 1;
        if (!stemless && !shared) return false;
        var rail = stemless ? opening : ending;
        float slope = (rail.b.y - rail.a.y) / (float) (rail.b.x - rail.a.x);
        if (slope < -.44f || slope > -.16f || Math.abs(guest.y - rail.center(guest.x)) > .24f * gap
                || guest.y - (stemless ? a.y : c.y) < 2.4f * gap) return false;

        var budget = new PrintedBeamShafts.Budget(30_000);
        var shafts = PrintedBeamShafts.at(plane.ink, plane.width, plane.height, guest.x, guest.y, gap, budget);
        if (budget.refused()) return false;
        if (stemless) {
            if (!shafts.isEmpty() || guest.x - rail.a.x < .9f * gap || guest.x - rail.a.x > 1.45f * gap)
                return false;
        } else {
            if (shafts.size() != 1 || rail.b.x - guest.x < .30f * gap || rail.b.x - guest.x > .59f * gap)
                return false;
            var reverse = shafts.get(0);
            if (reverse.direction() != -1 || rail.b.x < reverse.left() - 1 || rail.b.x > reverse.right() + 1
                    || Math.abs(reverse.endY() - c.y) > .3f * gap) return false;
        }
        int radius = Math.round(.42f * gap);
        int x = Math.round(guest.x), y = Math.round(guest.y);
        int observed = 0, foreign = 0;
        for (int col = x - radius; col <= x + radius; col++) {
            float beam = rail.center(col);
            var rules = track.at(col);
            if (!plane.dark(col, Math.round(beam))) return false;
            for (int row = y - radius; row <= y + radius; row++) {
                if (!plane.dark(col, row) || nearRule(rules, row, .16f)) continue;
                if (Math.abs(row - beam) <= .38f * gap) observed++;
                else if (!shared || Math.abs(col - rail.b.x) > .16f * gap || row > beam) foreign++;
            }
        }
        return observed >= gap * 2 && foreign <= 3;
    }


    private static boolean beamGuest(ScoreNoteEvent note) {
        return plainBeamGuest(note)
                && (note.stemDirection() == 1
                                && (note.beamCount() == 1 && note.unbeamedDurationBeats() == 0
                                        || note.beamCount() == 0
                                                && note.unbeamedDurationBeats() == 1)
                        || note.stemDirection() == 0
                                && note.beamCount() == 2
                                && note.unbeamedDurationBeats() == 0);
    }

    private static boolean plainBeamGuest(ScoreNoteEvent note) {
        return ordinaryRhythm(note) && note.augmentationDots() == 0 && note.articulations() == 0
                && note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
    }

    private static boolean staffAttachedHookGuest(ScoreNoteEvent note) {
        return beamGuest(note)
                || note.articulations() == NoteOrnament.GRACE && ordinaryRhythm(note, true)
                        && note.augmentationDots() == 0 && note.stemDirection() == 1
                        && note.beamCount() == 1 && note.unbeamedDurationBeats() == 0
                        && note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
    }

    private static boolean ownsTripletBeamGuest(
            Raster p, StaffPitchTrack track, Head a, Head b, Head c, Head guest, float gap) {
        return ownsTripletBeamGuest(p, track, a, b, c, guest, gap, .18f);
    }

    private static boolean ownsTripletBeamGuest(
            Raster p, StaffPitchTrack track, Head a, Head b, Head c, Head guest, float gap, float cornerTolerance) {
        boolean atCorner = guest.note.stemDirection() == 0;
        if (atCorner) {
            if (Math.abs(guest.x - a.x) > gap * cornerTolerance
                    || guest.y - a.y < gap * 2.6f
                    || guest.y - a.y > gap * 3.5f) return false;
        } else if (b.x - guest.x < gap * .58f
                || b.x - guest.x > gap * .90f
                || guest.y - b.y < gap * 3.3f
                || guest.y - b.y > gap * 4.5f) return false;

        var budget = new PrintedBeamShafts.Budget(30_000);
        if (!PrintedBeamShafts.at(p.ink, p.width, p.height, guest.x, guest.y, gap, budget).isEmpty()
                || budget.refused()) return false;
        var primary = bridge(p, track, a, c, gap);
        if (primary == null) return false;
        var firstHalf = bridge(p, track, a, b, gap);
        if (firstHalf == null
                || Math.abs(firstHalf.center(guest.x) - primary.center(guest.x)) > gap * .34f)
            return false;
        int shaft = atCorner ? firstHalf.a.x : firstHalf.b.x;
        float slope = (primary.b.y - primary.a.y) / (float) (primary.b.x - primary.a.x);
        float rise = guest.y - primary.center(guest.x);
        if (atCorner
                ? slope >= -.19f || rise < -gap * .18f || rise > gap * .22f
                : slope <= .15f
                        || Math.abs(guest.x - shaft) > gap * .25f
                        || rise < -gap * .55f
                        || rise > -gap * .22f) return false;

        int x = Math.round(guest.x), y = Math.round(guest.y), radius = Math.round(gap * .58f);
        int unowned = 0, visible = 0;
        for (int xx = x - radius; xx <= x + radius; xx++) {
            float beam = primary.center(xx);
            for (int yy = y - radius; yy <= y + radius; yy++) {
                if (!p.dark(xx, yy) || nearRule(track.at(xx), yy, .16f)) continue;
                float relative = yy - beam;
                if (Math.abs(xx - x) <= gap * .24f && Math.abs(yy - y) <= gap * .24f) visible++;
                if (relative >= -gap * .31f && relative <= gap * .42f
                        || Math.abs(xx - shaft) <= gap * .21f && relative < gap * .42f
                        || !atCorner
                                && xx >= shaft - gap * .98f
                                && xx < shaft
                                && relative >= -gap * 1.03f
                                && relative <= -gap * .38f) continue;
                unowned++;
            }
        }
        return visible >= (int) (gap * .48f) && unowned <= (atCorner ? Math.round(gap * .3f) : 0);
    }

    private static boolean downstemSecondaryHook(
            Raster p, StaffPitchTrack track, Head a, Head b, Head c, float gap) {
        var first = bridge(p, track, a, b, gap);
        var whole = bridge(p, track, a, c, gap);
        if (first == null
                || whole == null
                || Math.abs(first.center(b.x) - whole.center(b.x)) > gap * .21f) return false;
        int shaft = first.b.x;
        int tip = first.b.y;
        float slope = (whole.b.y - whole.a.y) / (float) (whole.b.x - whole.a.x);
        int near = Math.round(gap * .28f), far = Math.round(gap * .63f);
        int remote = Math.round(gap * 1.07f);
        for (int depth = Math.round(gap * .86f); depth <= Math.round(gap * 1.22f); depth++) {
            int yy = Math.round(tip - slope * far) - depth;
            if (!nearRule(track.at(shaft - far), yy, .23f)
                    && hookCore(p, shaft, tip, slope, -depth, near, far)
                    && !hookCore(p, shaft, tip, slope, -depth, remote, remote + 2)) return true;
        }
        return false;
    }

    private record MaskedTripletBar(int maskOwner, List<Head> lane) {}

    private static MaskedTripletBar maskedDottedTripletBar(
            Raster normal, Raster raw, List<MeasureRegion> regions, int index, List<Head> candidates) {
        var bar = regions.get(index);
        float gap = (bar.bottom() - bar.top()) * normal.height / 8.2f;
        if (gap < 10 || gap > 27 || bar.cadenceQuarterBeats() != 0) return null;
        var heads = candidates.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        MaskedTripletBar result = null;
        for (int i = 1; i < heads.size() - 2; i += 3) {
            var a = heads.get(i - 1);
            var mask = heads.get(i);
            var b = heads.get(i + 1);
            var c = heads.get(i + 2);
            if (!ordinary(mask.note) || mask.note.stemDirection() != 1 || mask.note.beamCount() != 1
                    || mask.note.augmentationDots() != 0 || mask.note.unbeamedDurationBeats() != 0
                    || mask.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                    || (mask.note.articulations() & ~NoteArticulation.STACCATO) != 0
                    || a.note.stemDirection() != -1 || b.note.stemDirection() != -1 || c.note.stemDirection() != -1
                    || a.note.augmentationDots() > 1 || b.note.augmentationDots() != 0
                    || b.x - mask.x < gap * .90f || b.x - mask.x > gap * 1.42f
                    || mask.y - b.y < gap * 2.55f || mask.y - b.y > gap * 3.20f) continue;
            if (!ownedShortHookMask(normal, bar, a, b, c, mask, gap)
                    || !ownedShortHookMask(raw, bar, a, b, c, mask, gap)) continue;
            var kept = new ArrayList<>(heads);
            kept.remove(i);
            if (!qualifiedAliasedMonophonic(normal, raw, regions, index, kept, false, true)) continue;
            if (result != null) return null;
            result = new MaskedTripletBar(mask.owner,List.copyOf(kept));
        }
        return result;
    }

    private static boolean ownedShortHookMask(
            Raster raster, MeasureRegion bar, Head a, Head b, Head c, Head mask, float coarseGap) {
        var track = staffTrack(raster, bar, coarseGap);
        if (track == null) return false;
        float gap = track.at(b.x)[1];
        var first = bridge(raster, track, a, b, gap);
        var following = bridge(raster, track, b, c, gap);
        if (first == null || following == null || Math.abs(first.b.x - following.a.x) > gap * .14f
                || Math.abs(first.b.y - following.a.y) > gap * .14f
                || !downstemSecondaryHook(raster, track, a, b, c, gap)) return false;
        return ownedShortHookMask(raster, track, b, mask, first, gap, .21f);
    }

    private static boolean ownedShortHookMask(
            Raster raster, StaffPitchTrack track, Head b, Head mask, Bridge first, float gap, float tipTolerance) {
        int shaft = first.b.x;
        if (shaft - mask.x < gap * .30f || shaft - mask.x > gap * .85f
                || mask.y - first.center(mask.x) < -gap * .95f
                || mask.y - first.center(mask.x) > -gap * .34f) return false;

        var budget = new PrintedBeamShafts.Budget(100_000);
        var reverse = PrintedBeamShafts.at(raster.ink,raster.width,raster.height,mask.x,mask.y,gap,budget);
        if (reverse.size() != 1 || budget.refused()) return false;
        var witness = reverse.get(0);
        if (witness.direction() != -1 || shaft < witness.left() - 1 || shaft > witness.right() + 1
                || Math.abs(witness.endY() - b.y) > gap * tipTolerance) return false;

        int x0 = Math.round(mask.x), y0 = Math.round(mask.y), radius = Math.round(gap * .53f);
        int core = Math.round(gap * .22f), visible = 0;
        for (int x = x0 - radius; x <= x0 + radius; x++) {
            float line = first.center(x);
            float[] rules = track.at(x);
            for (int y = y0 - radius; y <= y0 + radius; y++) {
                if (!raster.dark(x,y) || nearRule(rules,y,.16f)) continue;
                float pos = y - line;
                if (Math.abs(x - x0) <= core && Math.abs(y - y0) <= core) visible++;
                if ((pos >= -gap * .43f && pos <= gap * .43f)
                        || (Math.abs(x - shaft) <= gap * .18f && pos < gap * .45f)
                        || (x >= shaft - gap * 1.10f && x <= shaft + gap * .14f
                                && pos >= -gap * 1.45f && pos <= -gap * .36f)) continue;
                return false;
            }
        }
        return visible >= Math.round(gap);
    }

    /** A closed lane can contain a missed hook or a duplicate reading of one augmentation dot. */
    private static boolean qualifiedAliasedMonophonic(
            Raster normal, Raster raw, List<MeasureRegion> regions, int index, List<Head> heads,
            boolean openingSixFour, boolean provedMask) {
        return qualifiedAliasedMonophonic(normal, raw, regions, index, heads, openingSixFour, provedMask, false, -1);
    }

    private static boolean qualifiedAliasedMonophonic(
            Raster normal, Raster raw, List<MeasureRegion> regions, int index, List<Head> heads,
            boolean openingSixFour, boolean provedMask, boolean internalMeter, int verifiedHookOwner) {
        var bar = regions.get(index);
        if (bar.cadenceQuarterBeats() != 0) return false;
        float gap = (bar.bottom() - bar.top()) * normal.height / 8.2f;
        float left = bar.left() * normal.width, right = bar.right() * normal.width;
        int last = heads.size() - 1;
        if (gap < 10 || gap > 27 || heads.get(0).x - left < gap * .55f
                || right - heads.get(last).x < gap * .75f) return false;
        int clef = heads.get(0).note.clefBottomDiatonic();
        var first = heads.get(0).note;
        var middle = heads.get(1).note;
        var lastNote = heads.get(2).note;
        boolean openingAlias = openingSixFour && first.stemDirection() == 1
                && first.beamCount() == 1 && first.augmentationDots() == 1
                && middle.beamCount() == 2 && middle.augmentationDots() == 1
                && lastNote.beamCount() == 2 && lastNote.augmentationDots() == 0;
        int aliases = 0;
        for (int i = 0; i < heads.size(); i++) {
            var n = heads.get(i).note;
            int slot = i % 3;
            if (!ordinary(n) || n.clefBottomDiatonic() != clef || n.unbeamedDurationBeats() != 0
                    || n.stemDirection() != heads.get(i - slot).note.stemDirection()) return false;
            if (openingAlias && (i == 1 || i == 2)) continue;
            int dots = n.augmentationDots(), beams = n.beamCount();
            boolean borrowedStaccato = provedMask && slot == 1 && dots == 1 && beams == 2
                    && (n.articulations() & NoteArticulation.STACCATO) != 0;
            boolean missedLeadDot = provedMask && slot == 0 && dots == 0 && beams == 1;
            if ((slot == 0 && dots == 2 && beams == 1) || (slot == 1 && dots == 0 && beams == 1)
                    || borrowedStaccato || missedLeadDot) {
                if (++aliases > (provedMask ? 4 : 2)) return false;
            } else if (dots != (slot == 0 ? 1 : 0) || beams != (slot == 1 ? 2 : 1)) {
                return false;
            }
        }
        if (openingAlias ? aliases != 0 : aliases == 0 && !provedMask) return false;
        for (var raster : List.of(normal, raw)) {
            var track = staffTrack(raster, bar, gap);
            if (track == null && internalMeter) track = WrappedMeasureBoundaries.verifiedTripletStaff(
                    raster.ink, raster.width, raster.height, bar, true);
            if (track == null) return false;
            if (internalMeter) {
                if (!WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(
                        raster.ink, raster.width, raster.height, bar, heads.get(last).x, track)) return false;
            } else if (!barline(raster, right, track.at(right)) || !(openingAlias ? WrappedMeasureBoundaries.confirmsOpeningTripletBar(
                    raster.ink, raster.width, raster.height, regions, heads.get(0).x, heads.get(last).x, true)
                    : barline(raster, left, track.at(left)))) return false;
            for (int i = 0; i < heads.size(); i += 3) {
                var a = heads.get(i); var b = heads.get(i + 1); var c = heads.get(i + 2);
                float local = track.at(b.x)[1];
                int direction = b.note.stemDirection();
                boolean nearDot = internalMeter && a.note.augmentationDots() == 0
                        && b.note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FLAT
                        && compactOffRuleDot(raster, track, a, local);
                if (b.x - a.x < local * 1.9f || b.x - a.x > local * 3.85f
                        || c.x - b.x < local * 1.3f || c.x - b.x > local * 3.1f
                        || !dot(raster, a, b, local) && !nearDot
                        || (dot(raster, b, c, local) && !(provedMask && flatOwnsTripletGap(raster, b, c, local)))
                        || !tripletPrimaryLink(raster, track, a, b, local, direction, provedMask)
                        || !tripletPrimaryLink(raster, track, b, c, local, direction, provedMask))
                    return false;
                if (openingAlias && i == 0 && !openingStaccatoTriplet(raster, track, a, b, c, local))
                    return false;
                if (a.note.augmentationDots() == 2 && !oneDetachedAugmentationDot(raster, a, b.x, local)) return false;
                if (provedMask && a.note.augmentationDots() == 0 && !nearDot && !detachedDot(raster, a, b.x, local))
                    return false;
                if (provedMask && b.note.augmentationDots() == 1
                        && !neighborStaccatosOwnDot(raster, b, c, local))
                    return false;
                if (b.note.beamCount() == 1 && b.owner != verifiedHookOwner
                        && !missedHook(raster, track, a, b, c, local)) return false;
                if (i + 3 < heads.size()) {
                    var following = heads.get(i + 3);
                    if (dot(raster, c, following, local)
                            || direction == following.note.stemDirection() && !SeparateBeamGroups.between(raster.ink, raster.width, raster.height,
                                    c.x, c.y, following.x, following.y, local)
                                    && !(provedMask && confirmsSeparateDownstems(raster, c, following, local))) return false;
                }
            }
        }
        return true;
    }

    private static List<Head> internalOpeningHookAlias(Raster normalized, Raster raw,
            List<MeasureRegion> regions, int index, List<Head> candidates) {
        var region = regions.get(index);
        float estimatedGap = (region.bottom() - region.top()) * raw.height / 8.2f;
        var order = candidates.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        List<Head> result = null;
        for (int slot = 1; slot + 2 < order.size(); slot += 3) {
            var first = order.get(slot - 1);
            var guest = order.get(slot);
            var middle = order.get(slot + 1);
            var last = order.get(slot + 2);
            if (!staffAttachedHookGuest(guest.note) || middle.note.beamCount() != 1
                    || middle.note.stemDirection() != -1
                    || middle.x - guest.x < .8f * estimatedGap || middle.x - guest.x > 1.2f * estimatedGap)
                continue;
            if (!internalOpeningHook(normalized, region, first, middle, last, guest)
                    || !internalOpeningHook(raw, region, first, middle, last, guest)) continue;
            var retained = new ArrayList<>(order);
            retained.remove(slot);
            if (!qualifiedAliasedMonophonic(normalized, raw, regions, index, retained,
                    false, true, true, middle.owner)) continue;
            if (result != null) return null;
            retained.set(slot, new Head(middle.owner, withRhythm(middle.note, 0, 2, 3, 2), middle.x, middle.y));
            result = retained;
        }
        return result;
    }

    private static List<ScoreBeamSource.TupletMark> dottedEngraving(Raster normalized, Raster raw,
            List<MeasureRegion> regions, int index, List<Head> lane, boolean numberedOpening) {
        var bar = regions.get(index);
        var marks = new ArrayList<ScoreBeamSource.TupletMark>();
        for (var plane : List.of(raw, normalized)) {
            var track = WrappedMeasureBoundaries.verifiedTripletStaff(plane.ink, plane.width, plane.height, bar, true);
            if (track == null) return List.of();
            var rows = UnnumberedTripletInk.sourceRows(bar, regions, plane.height);
            for (int i = 0; i < lane.size(); i += 3) {
                var a = lane.get(i);
                var b = lane.get(i + 1);
                var c = lane.get(i + 2);
                float gap = track.at(b.x)[1];
                var first = bridge(plane, track, a, b, gap, numberedOpening ? 9f : 5.4f);
                var following = bridge(plane, track, b, c, gap, numberedOpening ? 9f : 5.4f);
                var shafts = new ArrayList<PrintedBeamShafts.Shaft>();
                var quota = new PrintedBeamShafts.Budget(180_000);
                for (int slot = 0; slot < 3; slot++) {
                    var head = lane.get(i + slot);
                    var evidence = PrintedBeamShafts.at(plane.ink, plane.width, plane.height, head.x, head.y, gap, quota);
                    if (quota.refused() || evidence.size() > 1) return List.of();
                    if (evidence.size() == 1) shafts.add(evidence.get(0));
                    else if (numberedOpening && first != null && following != null
                            && Math.abs(first.b.x - following.a.x) <= .16f * gap
                            && Math.abs(first.b.y - following.a.y) <= .18f * gap) {
                        var tip = slot == 0 ? first.a : slot == 1 ? first.b : following.b;
                        shafts.add(new PrintedBeamShafts.Shaft(tip.x, tip.x, Math.round(head.y), tip.y,
                                tip.travel, List.of(new PrintedBeamShafts.Column(tip.x, tip.y))));
                    } else return List.of();
                }
                if (first == null && numberedOpening) {
                    var x = shafts.get(0);
                    var y = shafts.get(1);
                    if (x.direction() == 1 && y.direction() == 1
                            && tripletPrimaryLink(plane, track, a, b, gap, -1, false)) {
                        var lead = new Shaft(x.left(), x.endY(), 1, 1);
                        var middle = new Shaft(y.left(), y.endY(), 1, 1);
                        for (int inset = Math.round(.23f * gap); inset <= Math.round(.52f * gap); inset++) {
                            var candidate = new Bridge(lead, middle, inset, gap);
                            if (backwardHook(plane, track, candidate, .52f, 1.40f, .19f)) {
                                first = candidate;
                                break;
                            }
                        }
                    }
                }
                if (first == null || !backwardHook(plane, track, first, .52f, 1.40f, .19f)) return List.of();
                boolean numbered = numberedOpening && i == 0;
                if (numbered) {
                    if (!ruleCrossedOpeningThree(plane, track, first, b.x, gap)) return List.of();
                } else {
                    float[] x = {a.x, b.x, c.x}, y = {a.y, b.y, c.y};
                    if (!UnnumberedTripletInk.clearWithTrackedBows(
                            plane.ink, plane.width, plane.height, gap, x, y, shafts, rows, track)
                            && !(numberedOpening && UnnumberedTripletInk.clearRuledFingeringBands(
                                plane.ink, plane.width, plane.height, gap, x, y,
                                Math.min(a.y, Math.min(b.y, c.y)), Math.max(a.y, Math.max(b.y, c.y)),
                                shafts.stream().mapToInt(PrintedBeamShafts.Shaft::endY).min().orElseThrow(),
                                shafts.stream().mapToInt(PrintedBeamShafts.Shaft::endY).max().orElseThrow(),
                                rows, track))) return List.of();
                }
                if (plane == raw) marks.add(numbered ? ScoreBeamSource.TupletMark.NUMBERED
                        : ScoreBeamSource.TupletMark.UNNUMBERED);
            }
        }
        return List.copyOf(marks);
    }

    private static boolean numeralFermataAlias(ScoreExpressiveEvent e, Head owner, int width) {
        var note = owner.note;
        String target = "printed-attack:" + note.measureIndex() + ":" + note.staffIndex() + ":"
                + note.staffCount() + ":" + Float.floatToIntBits(note.positionInMeasure());
        return e.kind() == ScoreExpressiveEvent.Kind.FERMATA && e.targetEventId().filter(target::equals).isPresent()
                && e.evidence().stream().allMatch(v -> "fermata-raw-ink".equals(v.sourceId())
                    && Math.abs(v.visualX() * width - owner.x) < width * .003f);
    }

    private static boolean ruleCrossedOpeningThree(Raster plane, StaffPitchTrack track, Bridge beam,
            float owner, float gap) {
        if (beam.b.travel != -1) return false;
        int l = Math.round(owner - .92f * gap), r = Math.round(owner + .95f * gap);
        if (l - Math.round(gap) < 0 || r + Math.round(gap) >= plane.width) return false;
        var rails = track.at(owner);
        for (int index = 0; index < 5; index++) {
            int rule = Math.round(rails[0] - index * rails[1]);
            float clearance = beam.center(owner) - rule;
            if (clearance < .8f * gap || clearance > 1.28f * gap) continue;
            int first = rule, last = rule;
            while (first > rule - 2 && continuousNumberRule(plane, l, r, first - 1)) first--;
            while (last < rule + 2 && continuousNumberRule(plane, l, r, last + 1)) last++;
            if (last - first + 1 < 2 || last - first + 1 > .23f * gap
                    || !continuousNumberRule(plane, l, r, rule)) continue;
            int top = Math.round(first - .82f * gap);
            int bottom = Math.min(Math.round(last + .56f * gap), Math.round(beam.center(owner) - .38f * gap));
            if (top < 0 || bottom >= plane.height || last >= bottom) continue;
            var upper = RawNumeralComponents.captureLongWindow(
                    plane.ink, plane.width, plane.height, l, top, r, first - 1, 145).components().stream()
                    .filter(g -> g.size() >= gap * gap * .065f).toList();
            if (upper.size() != 2) continue;
            var curl = upper.stream().max(Comparator.comparingInt(g -> g.size())).orElseThrow();
            var cap = upper.get(0) == curl ? upper.get(1) : upper.get(0);
            if (curl.left <= l || curl.right >= r || curl.top <= top || curl.bottom != first - 1
                    || curl.right - curl.left + 1 < .73f * gap || curl.bottom - curl.top + 1 < .59f * gap
                    || cap.right - cap.left + 1 > .38f * gap || cap.bottom - cap.top + 1 > .54f * gap
                    || Math.abs((cap.left + cap.right) * .5f - owner) > .25f * gap
                    || cap.bottom > first - .25f * gap) continue;
            for (var lower : RawNumeralComponents.captureLongWindow(
                    plane.ink, plane.width, plane.height, l, last + 1, r, bottom, 145).components()) {
                if (lower.left <= l || lower.right >= r || lower.top != last + 1
                        || lower.right - lower.left + 1 < .83f * gap || lower.right - lower.left + 1 > 1.24f * gap
                        || lower.bottom - lower.top + 1 < .43f * gap || Math.abs(lower.right - curl.right) > .25f * gap)
                    continue;
                int pocketRows = 0;
                for (int y = lower.top; y < lower.bottom; y++) {
                    for (int x = lower.left + 2; x < lower.right - 2; x++) {
                        if (!plane.dark(x - 1, y) || plane.dark(x, y)) continue;
                        int right = x + 1;
                        while (right < lower.right && !plane.dark(right, y)) right++;
                        if (right - x >= 2 && right - x <= .34f * gap && right < lower.right) {
                            pocketRows++;
                            break;
                        }
                    }
                }
                if (pocketRows >= 2) return true;
            }
        }
        return false;
    }

    private static boolean continuousNumberRule(Raster plane, int left, int right, int row) {
        int ink = 0;
        for (int x = left - 4; x <= right + 4; x++) if (plane.dark(x, row)) ink++;
        return ink >= .94f * (right - left + 9);
    }

    private static ScoreBeamSource completeDottedEngraving(
            ScoreBeamSource source, List<Head> lane, List<Head> all, List<ScoreBeamSource.TupletMark> marks) {
        var anchors = lane.stream().map(h -> source.noteOrigins().get(h.owner).orElse(null)).toList();
        if (anchors.contains(null)) return source;
        int page = anchors.get(0).pageIndex();
        if (anchors.stream().anyMatch(a -> a.pageIndex() != page)) return source;
        var local = new HashSet<ScoreBeamSource.Anchor>();
        for (var h : all)
            if (!source.noteOrigins().get(h.owner).map(local::add).orElse(true)) return source;
        var used = new HashSet<>(local);
        for (var origin : source.noteOrigins())
            if (origin.isPresent() && local.contains(origin.get()) && !used.remove(origin.get())) return source;
        var groups = new ArrayList<ScoreBeamSource.Group>();
        for (var group : source.groups()) {
            var members = group.columns().stream().flatMap(c -> c.members().stream()).toList();
            if (members.stream().noneMatch(local::contains)) groups.add(group);
            else if (!local.containsAll(members) || group.tupletMark() != null) return source;
        }
        var detached = new ArrayList<ScoreBeamSource.Column>();
        for (var column : source.detachedColumns()) {
            if (column.members().stream().noneMatch(local::contains)) detached.add(column);
            else if (!local.containsAll(column.members())) return source;
        }
        for (int i = 0; i < anchors.size(); i += 3) {
            var columns = anchors.subList(i, i + 3).stream().map(a -> new ScoreBeamSource.Column(List.of(a))).toList();
            String id = "printed-beam:" + page + ":" + anchors.get(i).noteIndex();
            if (groups.stream().anyMatch(g -> g.id().equals(id))) return source;
            var hooks = List.of(new ScoreBeamSource.Hook(1, 2, ScoreBeamSource.Direction.BACKWARD));
            groups.add(new ScoreBeamSource.Group(id, columns, List.of(1, 1), hooks, marks.get(i / 3)));
        }
        return new ScoreBeamSource(groups, detached, source.noteOrigins());
    }

    private static boolean internalOpeningHook(Raster plane, MeasureRegion bar, Head a, Head b, Head c,
            Head guest) {
        var track = WrappedMeasureBoundaries.verifiedTripletStaff(plane.ink, plane.width, plane.height, bar, true);
        if (track == null) return false;
        float gap = track.at(b.x)[1];
        var first = bridge(plane, track, a, b, gap);
        var second = bridge(plane, track, b, c, gap);
        if (first == null || second == null || Math.abs(first.b.x - second.a.x) > .14f * gap
                || Math.abs(first.b.y - second.a.y) > .14f * gap
                || !ownedShortHookMask(plane, track, b, guest, first, gap, .36f)) return false;
        return backwardHook(plane, track, first, .78f, 1.28f, .20f);
    }

    private static boolean backwardHook(Raster plane, StaffPitchTrack track, Bridge first,
            float minDepth, float maxDepth, float ruleMargin) {
        // A short ledge attaches to the middle stem, inward from the primary beam.
        float gap = first.gap;
        int shaft = first.b.x, tip = Math.round(first.center(shaft));
        float slope = (first.b.y - first.a.y) / (float) (first.b.x - first.a.x);
        int near = Math.round(.27f * gap), far = Math.round(.62f * gap), remote = Math.round(1.17f * gap);
        for (int d = Math.round(minDepth * gap); d <= Math.round(maxDepth * gap); d++) {
            int depth = -first.b.travel * d;
            float y = tip - slope * far + depth;
            if (!nearRule(track.at(shaft - far), y, ruleMargin)
                    && hookCore(plane, shaft, tip, slope, depth, near, far)
                    && !hookCore(plane, shaft, tip, slope, depth, remote, remote + 2)) return true;
        }
        return false;
    }

    private static boolean provesInternalMeter(byte[] raw, byte[] normalized, byte[] labels, int width,
            int height, List<MeasureRegion> bars, int index, List<ScoreNoteEvent> notes, ScoreMeterChange meter) {
        if (labels == null || labels.length != raw.length || index < 1 || meter.measureIndex() != index)
            return false;
        var bar = bars.get(index);
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        return (bar.top() - bars.get(index - 1).bottom()) * height > 2f * gap
                && MeterFontMatcher.standaloneReadings(labels, raw, width, height, bars, notes).contains(meter)
                && MeterFontMatcher.standaloneReadings(labels, normalized, width, height, bars, notes).contains(meter);
    }

    private static boolean compactOffRuleDot(Raster plane, StaffPitchTrack track, Head head, float gap) {
        if (!nearRule(track.at(head.x), head.y, .13f)) return false;
        int left = Math.round(head.x + .60f * gap), right = Math.round(head.x + 1.05f * gap);
        int top = Math.round(head.y - .93f * gap), bottom = Math.round(head.y - .36f * gap);
        if (left < 0 || top < 0 || right >= plane.width || bottom >= plane.height) return false;
        var glyphs = RawNumeralComponents.captureLongWindow(
                plane.ink, plane.width, plane.height, left, top, right, bottom, 155).components();
        int found = 0;
        for (var g : glyphs) {
            int w = g.right - g.left + 1, h = g.bottom - g.top + 1;
            if (g.left <= left || g.right >= right || g.top <= top || g.bottom >= bottom
                    || w < .23f * gap || h < .23f * gap || w > .49f * gap || h > .49f * gap
                    || g.size() < .70f * w * h) continue;
            found++;
        }
        return found == 1;
    }

    private static boolean tripletPrimaryLink(
            Raster raster, StaffPitchTrack track, Head a, Head b, float gap, int direction, boolean provedMask) {
        return PrintedTupletBeamOwner.connectedHeads(raster.ink,raster.width,raster.height,a.x,a.y,b.x,b.y,gap,direction)
                || provedMask && bridge(raster, track, a, b, gap) != null;
    }

    private static boolean confirmsSeparateDownstems(Raster raster, Head a, Head b, float gap) {
        if (a.note.stemDirection() != -1 || b.note.stemDirection() != -1
                || b.x - a.x < 1.65f * gap || b.x - a.x > 2.8f * gap) return false;
        var budget = new PrintedBeamShafts.Budget(170_000);
        var first = PrintedBeamShafts.at(raster.ink,raster.width,raster.height,a.x,a.y,gap,budget);
        var next = PrintedBeamShafts.at(raster.ink,raster.width,raster.height,b.x,b.y,gap,budget);
        return first.size() == 1 && next.size() == 1 && !budget.refused()
                && first.get(0).direction() == 1 && next.get(0).direction() == 1
                && WrappedEighthTriplets.matchesRailBridge(
                        raster.ink,raster.width,raster.height,first.get(0),next.get(0),gap,false);
    }

    private static boolean neighborStaccatosOwnDot(Raster raster, Head b, Head c, float gap) {
        if (OmrScoreInterpreter.centerOwnedLowerStaccato(
                raster.ink, raster.width, raster.height, b.x, b.y, gap, 1.02f, 1.82f, .68)) return true;
        for (var note : List.of(b, c)) {
            if ((note.note.articulations() & NoteArticulation.STACCATO) == 0
                    || !OmrScoreInterpreter.centerOwnedLowerStaccato(
                            raster.ink, raster.width, raster.height, note.x, note.y, gap, .94f, 1.65f, .48))
                return false;
        }
        return true;
    }

    private static boolean flatOwnsTripletGap(Raster raster, Head middle, Head next, float gap) {
        var glyph = WrappedTripletDotInk.uniqueCore(
                raster.ink, raster.width, raster.height, middle.x, middle.y, next.x, gap);
        if (glyph == null || next.x - glyph.x() < gap * 1.40f || next.x - glyph.x() > gap * 1.91f
                || glyph.y() - next.y < gap * .31f || glyph.y() - next.y > gap * .52f) return false;
        int left = Math.round(next.x - gap * 1.91f), right = Math.round(next.x - gap * .78f);
        if (!PrintedFlatGlyph.matches(raster.ink, raster.width, raster.height, left,
                Math.round(next.y - gap * 2.0f), right, Math.round(next.y + gap * .76f), gap)) return false;
        int top = glyph.y() - Math.round(gap * 1.92f);
        for (int x = Math.max(left, glyph.x() - Math.round(gap * .27f)); x <= glyph.x(); x++) {
            int filled = 0;
            for (int y = top; y <= glyph.y(); y++)
                if (raster.dark(x, y) && raster.dark(x + 1, y)) filled++;
            if (filled < (glyph.y() - top + 1) * .92f) continue;
            boolean connected = true;
            for (int xx = x; xx <= glyph.x(); xx++) connected &= raster.dark(xx, glyph.y());
            if (connected) return true;
        }
        return false;
    }

    private static boolean openingStaccatoTriplet(
            Raster raster, StaffPitchTrack track, Head a, Head b, Head c, float gap) {
        if (!OmrScoreInterpreter.centerOwnedLowerStaccato(
                raster.ink, raster.width, raster.height, b.x, b.y, gap, 1.05f, 1.75f, .68)
                || !missedHook(raster, track, a, b, c, gap)) return false;
        var beam = bridge(raster, track, b, c, gap);
        if (beam == null || beam.a.y - beam.b.y < gap * .43f) return false;
        int first = beam.a.x + Math.round(gap * .35f), last = beam.b.x - Math.round(gap * .22f);
        if (last - first < gap * .85f) return false;
        for (int x = first; x <= last; x++) {
            var rules = track.at(x);
            int primary = Math.round(beam.center(x));
            for (int depth = Math.round(gap * .40f); depth <= Math.round(gap * 1.25f); depth++) {
                int y = primary + depth;
                if (raster.dark(x, y) && !nearRule(rules, y, .195f)) return false;
            }
        }
        return true;
    }

    private static boolean oneDetachedAugmentationDot(Raster raster, Head head, float next, float gap) {
        if (!detachedDot(raster, head, next, gap)) return false;
        var dot = WrappedTripletDotInk.uniqueCore(
                raster.ink, raster.width, raster.height, head.x, head.y, next, gap);
        if (dot == null) return false;
        int left = dot.x() + Math.round(gap * .29f);
        int right = Math.min(dot.x() + Math.round(gap * 1.45f), Math.round(next - gap * .33f));
        if (right - left < gap * .23f) return false;
        int radius = Math.round(gap * .25f);
        int possibleCoreColumns = 0;
        for (int x = left; x <= right; x++) {
            int ink = 0;
            for (int y = dot.y() - radius; y <= dot.y() + radius; y++) {
                if (raster.dark(x, y)) ink++;
            }
            if (ink >= Math.round(gap * .24f) && ++possibleCoreColumns >= Math.round(gap * .20f))
                return false;
        }
        return true;
    }

    private static boolean missedHook(Raster raster, StaffPitchTrack track, Head a, Head b, Head c, float gap) {
        if (b.note.stemDirection() < 0) return downstemSecondaryHook(raster, track, a, b, c, gap);
        var budget = new PrintedBeamShafts.Budget(250_000);
        var first = PrintedBeamShafts.at(raster.ink, raster.width, raster.height, a.x, a.y, gap, budget);
        var middle = PrintedBeamShafts.at(raster.ink, raster.width, raster.height, b.x, b.y, gap, budget);
        return first.size() == 1 && middle.size() == 1 && !budget.refused()
                && hasOnlySecondaryHook(raster.ink, raster.width, raster.height, first.get(0), middle.get(0), gap);
    }

    /** Recheck directly owned hooks and ledger contact before accepting beam-count aliases. */
    private static boolean qualifiedStaffAttachedGroups(Raster normal, Raster raw, MeasureRegion bar,
            List<Head> heads) {
        return qualifiedStaffAttachedGroups(normal, raw, bar, heads, null, null);
    }

    private static boolean qualifiedStaffAttachedGroups(Raster normal, Raster raw, MeasureRegion bar,
            List<Head> heads, Head mask, byte[] labels) {
        if (bar.cadenceQuarterBeats() != 0) return false;
        float estimate = (bar.bottom() - bar.top()) * raw.height / 8.2f;
        float left = bar.left() * raw.width, right = bar.right() * raw.width;
        if (estimate < 10 || estimate > 26 || right - left < estimate * 23 || right - left > estimate * (mask == null ? 33 : 36)
                || heads.get(0).x - left < estimate * .65f
                || right - heads.get(heads.size() - 1).x < estimate * .75f) return false;
        int clef = heads.get(0).note.clefBottomDiatonic();
        int corrections = 0;
        for (int i = 0; i < heads.size(); i++) {
            var note = heads.get(i).note;
            if (!ordinary(note) || note.clefBottomDiatonic() != clef || note.unbeamedDurationBeats() != 0
                    || note.stemDirection() != -1 || (note.augmentationDots() != (i % 3 == 0 ? 1 : 0)
                            && !(mask != null && i == 0 && note.augmentationDots() == 0)))
                return false;
            if (note.beamCount() == (i % 3 == 1 ? 2 : 1)) continue;
            if (i % 3 == 0 || note.beamCount() < 1 || note.beamCount() > 2 || ++corrections > 2) return false;
        }
        if (corrections == 0) return false;
        for (Raster plane : List.of(normal, raw)) {
            var track = staffTrack(plane, bar, estimate);
            if (track == null || !barline(plane, left, track.at(left)) || !barline(plane, right, track.at(right)))
                return false;
            var shafts = new ArrayList<PrintedBeamShafts.Shaft>();
            var budget = new PrintedBeamShafts.Budget(300_000);
            boolean longShaft = false;
            for (var head : heads) {
                float gap = track.at(head.x)[1];
                var owned = PrintedBeamShafts.at(plane.ink, plane.width, plane.height, head.x, head.y, gap, budget);
                if (owned.isEmpty() && mask != null && !longShaft && !budget.refused()) {
                    var contrast = new PrintedBeamShaftPixels.Frame(
                            plane.ink, plane.ink, plane.width, plane.height, 0, budget::take, true);
                    owned = PrintedBeamShafts.atAll(
                            plane.ink, plane.width, plane.height, head.x, head.y, gap, budget, contrast);
                    if (owned.size() != 1 || owned.get(0).endY() - head.y < gap * 7) return false;
                    longShaft = true;
                }
                if (owned.size() != 1 || owned.get(0).direction() != 1 || budget.refused()) return false;
                shafts.add(owned.get(0));
            }
            boolean hasRuledDot = false;
            int maskOwners = 0;
            for (int i = 0; i < heads.size(); i += 3) {
                var a = heads.get(i);
                var b = heads.get(i + 1);
                var c = heads.get(i + 2);
                float gap = track.at(b.x)[1];
                if (b.x - a.x < gap * 1.9f || b.x - a.x > gap * 3.8f
                        || c.x - b.x < gap * 1.3f || c.x - b.x > gap * 3.1f
                        || !dot(plane, a, b, gap)
                        || !WrappedEighthTriplets.matchesBeamRails(plane.ink, plane.width, plane.height,
                                shafts.get(i), shafts.get(i + 1), gap, 1)
                        || !WrappedEighthTriplets.matchesBeamRails(plane.ink, plane.width, plane.height,
                                shafts.get(i + 1), shafts.get(i + 2), gap, 1)
                        || !staffAttachedSecondaryHook(plane, track, shafts.get(i),
                                shafts.get(i + 1), shafts.get(i + 2), gap)) return false;
                if (dot(plane, b, c, gap)) {
                    var core = WrappedTripletDotInk.uniqueCore(plane.ink, plane.width, plane.height, b.x, b.y,
                            c.x, gap);
                    if (core == null || !(WrappedEighthTriplets.headConnectedLedger(
                            plane.ink, plane.width, plane.height, b.x, b.y, core, gap, .38f)
                            || mask != null && c.note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL
                                && WrappedEighthTriplets.naturalOwnsDot(
                                        plane.ink, labels, plane.width, plane.height, core, c.x, c.y, gap)))
                        return false;
                    hasRuledDot = true;
                }
                if (mask != null && mask.x > a.x && mask.x < b.x) {
                    var first = shaftTip(shafts.get(i));
                    var second = shaftTip(shafts.get(i + 1));
                    var bridge = new Bridge(first, second, gap * .20f, gap);
                    if (!ownedShortHookMask(plane, track, b, mask, bridge, gap, .27f)) return false;
                    maskOwners++;
                }
                if (i + 3 < heads.size() && !SeparateBeamGroups.between(
                        plane.ink, plane.width, plane.height, c.x, c.y,
                        heads.get(i + 3).x, heads.get(i + 3).y, gap)
                        && !WrappedEighthTriplets.matchesBeamRails(plane.ink, plane.width, plane.height,
                                shafts.get(i + 2), shafts.get(i + 3), gap, 0)) return false;
            }
            // A short hook can mimic a note even when every owning shaft has its ordinary length.
            if ((mask == null || longShaft) && !hasRuledDot || mask != null && maskOwners != 1)
                return false;
        }
        return true;
    }

    private static Shaft shaftTip(PrintedBeamShafts.Shaft source) {
        return new Shaft((source.left() + source.right()) / 2, source.endY(), source.direction(), 1);
    }

    private static boolean staffAttachedSecondaryHook(Raster plane, StaffPitchTrack track,
            PrintedBeamShafts.Shaft first, PrintedBeamShafts.Shaft middle, PrintedBeamShafts.Shaft last, float gap) {
        float slope = (last.endY() - first.endY()) / (float) (last.left() - first.left());
        int near = Math.round(gap * .28f), far = Math.round(gap * .63f), remote = Math.round(gap * 1.07f);
        for (var shaft : middle.columns()) {
            int x = shaft.x(), tip = shaft.endY();
            for (int depth = Math.round(gap * .80f); depth <= Math.round(gap * 1.28f); depth++) {
                int y = Math.round(tip - slope * far) - depth;
                if (!nearRule(track.at(x - far), y, .18f)
                        && hookCore(plane, x, tip, slope, -depth, near, far)
                        && !hookCore(plane, x, tip, slope, -depth, remote, remote + 2)) return true;
            }
        }
        return false;
    }

    /** Four separate dotted triplets fill a closed common-time bar. */
    private static boolean qualifiedFourGroups(Raster corrected, Raster raw, MeasureRegion bar, List<Head> heads) {
        float gap = (bar.bottom() - bar.top()) * raw.height / 8.2f;
        float left = bar.left() * raw.width, right = bar.right() * raw.width;
        if (bar.cadenceQuarterBeats() != 0 || gap < 10 || gap > 27
                || right - left < gap * 23 || right - left > gap * 36
                || heads.get(0).x - left < gap * .65f || heads.get(0).x - left > gap * 3.15f
                || right - heads.get(11).x < gap * .75f || right - heads.get(11).x > gap * 3.15f)
            return false;
        int clef = heads.get(0).note.clefBottomDiatonic();
        int unreportedDot = -1;
        for (int i = 0; i < heads.size(); i++) {
            var note = heads.get(i).note;
            int slot = i % 3;
            if (!ordinary(note) || note.unbeamedDurationBeats() != 0
                    || note.clefBottomDiatonic() != clef || note.beamCount() != (slot == 1 ? 2 : 1)
                    || note.stemDirection() != heads.get(i - slot).note.stemDirection()) return false;
            if (slot == 0 && note.augmentationDots() == 0) {
                if (unreportedDot >= 0) return false;
                unreportedDot = i;
            }
        }
        for (Raster plane : List.of(raw, corrected)) {
            var track = staffTrack(plane, bar, gap);
            if (track == null || !barline(plane, left, track.at(left)) || !barline(plane, right, track.at(right)))
                return false;
            for (int i = 0; i < heads.size(); i += 3) {
                var a = heads.get(i);
                var b = heads.get(i + 1);
                var c = heads.get(i + 2);
                float local = track.at(b.x)[1];
                int direction = b.note.stemDirection();
                if (b.x - a.x < local * 2.25f || b.x - a.x > local * 3.8f
                        || c.x - b.x < local * 1.65f || c.x - b.x > local * 3.05f
                        || !dot(plane, a, b, local) || dot(plane, b, c, local)
                        || !PrintedTupletBeamOwner.connectedHeads(
                                plane.ink, plane.width, plane.height, a.x, a.y, b.x, b.y, local, direction)
                        || !PrintedTupletBeamOwner.connectedHeads(
                                plane.ink, plane.width, plane.height, b.x, b.y, c.x, c.y, local, direction))
                    return false;
                if (i + 3 >= heads.size()) continue;
                var following = heads.get(i + 3);
                if (following.x - c.x < local * 1.65f || following.x - c.x > local * 3.2f
                        || dot(plane, c, following, local)
                        || !SeparateBeamGroups.between(
                                plane.ink, plane.width, plane.height, c.x, c.y, following.x, following.y, local)
                                  && (i + 3 != unreportedDot || !provesTripletRailGap(plane, c, following, local)))
                    return false;
            }
        }
        return true;
    }

    private static boolean provesTripletRailGap(Raster plane, Head left, Head right, float gap) {
        var budget = new PrintedBeamShafts.Budget(60_000);
        var before = PrintedBeamShafts.at(plane.ink, plane.width, plane.height, left.x, left.y, gap, budget);
        var after = PrintedBeamShafts.at(plane.ink, plane.width, plane.height, right.x, right.y, gap, budget);
        return !budget.refused() && before.size() == 1 && after.size() == 1
                && before.get(0).direction() == after.get(0).direction()
                && WrappedEighthTriplets.matchesBeamRails(
                        plane.ink, plane.width, plane.height, before.get(0), after.get(0), gap, 0);
    }

    /** Two separated dotted triplets followed by four dotted-sixteenth and hook pairs. */
    private static List<Head> qualifiedMixedRun(
            Raster raster, MeasureRegion bar, List<Head> heads) {
        if (bar.cadenceQuarterBeats() != 0
                || heads.stream()
                        .anyMatch(
                                h ->
                                        !ordinaryRhythm(h.note)
                                                || h.note.unbeamedDurationBeats() != 0))
            return null;
        var opposite = heads.stream().filter(h -> h.note.stemDirection() == 1).toList();
        var stemless = heads.stream().filter(h -> h.note.stemDirection() == 0).toList();
        if (opposite.size() != 1 || stemless.size() != 1) return null;
        var safe =
                heads.stream()
                        .filter(h -> h.note.stemDirection() == -1)
                        .sorted(Comparator.comparingDouble(Head::x))
                        .toList();
        if (safe.size() != 14) return null;
        float left = bar.left() * raster.width, right = bar.right() * raster.width;
        float gap = (bar.bottom() - bar.top()) * raster.height / 8.2f;
        if (gap < 10
                || gap > 27
                || right - left < gap * 32
                || right - left > gap * 45
                || safe.get(0).x - left < gap * 1.2f
                || safe.get(0).x - left > gap * 3.2f
                || right - safe.get(13).x < gap * .85f
                || right - safe.get(13).x > gap * 2.4f) return null;
        var samples = new ArrayList<float[]>();
        for (int i = 0; i <= 12; i++) {
            float x = left + (right - left) * i / 12;
            var staff =
                    PrintedDirectionStaff.printedRules(
                            raster.ink,
                            raster.width,
                            raster.height,
                            x,
                            bar.bottom() * raster.height - 2.1f * gap,
                            gap);
            if (staff != null) samples.add(new float[] {x, staff[0], staff[1]});
        }
        if (samples.size() < 7) return null;
        var track = StaffPitchTrack.fromVerifiedSamples(samples);
        if (!barline(raster, right, track.at(right))) return null;
        int clef = safe.get(0).note.clefBottomDiatonic();
        for (int i = 0; i < safe.size(); i++) {
            var h = safe.get(i);
            int index = i < 6 ? i % 3 : (i - 6) % 2;
            int beams = i < 6 ? (index == 1 ? 2 : 1) : (index == 0 ? 2 : 3);
            if (h.note.beamCount() != beams
                    || h.note.clefBottomDiatonic() != clef
                    || h.note.augmentationDots() != (index == 0 ? 1 : 0)) return null;
            float end = i + 1 < safe.size() ? safe.get(i + 1).x : right;
            if (i + 1 < safe.size() && (end - h.x < gap * 1.55f || end - h.x > gap * 4.45f))
                return null;
            if (i < 6 && detachedDot(raster, h, end, gap) != (index == 0)) return null;
            if (i >= 6 && index == 0 && !dot(raster, h, safe.get(i + 1), gap)) return null;
        }
        for (int i = 0; i < 6; i++) {
            var a = safe.get(i);
            var b = safe.get(i + 1);
            if (i % 3 == 2) {
                if (!SeparateBeamGroups.between(
                        raster.ink, raster.width, raster.height, a.x, a.y, b.x, b.y, gap))
                    return null;
            } else if (!PrintedTupletBeamOwner.connectedAwayFromRules(
                    raster.ink, raster.width, raster.height, a.x, a.y, b.x, b.y, gap, -1, track))
                return null;
        }
        boolean oppositeAlias = false;
        for (int i : new int[] {1, 4}) {
            var a = safe.get(i);
            var b = safe.get(i + 1);
            if (opposite.get(0).x <= a.x || opposite.get(0).x >= b.x) continue;
            var beam = bridge(raster, track, a, b, gap);
            oppositeAlias = beam != null && reversedBeamTip(raster, opposite.get(0), b, beam);
        }
        if (!oppositeAlias) return null;

        boolean ribbonAlias = false;
        var lower = stemless.get(0);
        if (lower.note.beamCount() != 3
                || lower.note.augmentationDots() != 0
                || lower.note.articulations() != 0
                || PrintedStemDirection.detect(
                                raster.ink, raster.width, raster.height, lower.x, lower.y, gap)
                        != 0) return null;
        for (int i = 6; i < safe.size(); i += 2) {
            var a = safe.get(i);
            var b = safe.get(i + 1);
            if (!OwnedTertiaryBeamlets.provesBackwardHook(
                    raster.ink, raster.width, raster.height, b.x, b.y, a.x, a.y, gap)) return null;
            if (Math.abs(lower.x - a.x) < gap * .3f
                    && lower.y - a.y > gap * 3.5f
                    && ClosedDottedSixteenths.ownsDottedPairAlias(
                            raster.ink,
                            raster.width,
                            raster.height,
                            track,
                            gap,
                            a.x,
                            a.y,
                            b.x,
                            b.y,
                            lower.x,
                            lower.y)) ribbonAlias = true;
        }
        if (!ribbonAlias) return null;

        var trial = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < safe.size(); i++) {
            var n = safe.get(i).note;
            trial.add(i < 6 ? withTriplet(n, i % 3 == 0 ? 1 : 0) : n);
        }
        for (int i = 0; i < trial.size(); i++) {
            var place = ScoreNoteTiming.writtenPlacement(trial.get(i), trial, 4f).orElse(null);
            float start, duration;
            if (i < 6) {
                int slot = i % 3;
                start = i / 3 + (slot == 0 ? 0 : slot == 1 ? .5f : 2f / 3f);
                duration = slot == 0 ? .5f : slot == 1 ? 1f / 6f : 1f / 3f;
            } else {
                int slot = i - 6;
                start = 2 + slot / 2 * .5f + (slot % 2 == 0 ? 0 : .375f);
                duration = slot % 2 == 0 ? .375f : .125f;
            }
            if (place == null
                    || Math.abs(place.onsetBeats() - start) > .0001f
                    || Math.abs(place.durationBeats() - duration) > .0001f) return null;
        }
        return safe;
    }

    /** Two separated dotted triplets precede two double-rail quartets with reversed beam masks. */
    private static List<Head> mixedRibbonRun(
            Raster corrected, Raster raw, MeasureRegion bar, List<Head> row) {
        var kept = row.stream().filter(h -> h.note.stemDirection() == -1)
                .sorted(Comparator.comparingDouble(Head::x)).toList();
        var fragments = row.stream().filter(h -> h.note.stemDirection() == 1)
                .sorted(Comparator.comparingDouble(Head::x)).toList();
        float gap = (bar.bottom() - bar.top()) * raw.height / 8.2f;
        float left = bar.left() * raw.width, right = bar.right() * raw.width;
        if (kept.size() != 14 || fragments.isEmpty() || fragments.size() > 2 || bar.cadenceQuarterBeats() != 0
                || gap < 10 || gap > 27 || right - left < gap * 36 || right - left > gap * 46
                || kept.get(0).x - left < gap * 1.7f || kept.get(0).x - left > gap * 3.35f
                || right - kept.get(kept.size() - 1).x < gap || right - kept.get(kept.size() - 1).x > gap * 2.4f)
            return null;
        int clef = kept.get(0).note.clefBottomDiatonic(), absentLead = 0, missingHooks = 0;
        for (int i = 0; i < kept.size(); i++) {
            var head = kept.get(i);
            var n = head.note;
            if (!ordinaryRhythm(n) || n.clefBottomDiatonic() != clef || n.unbeamedDurationBeats() != 0)
                return null;
            if (i > 0) {
                float advance = head.x - kept.get(i - 1).x;
                if (advance < gap * 1.65f || advance > gap * 4.4f) return null;
            }
            if (i < 6) {
                int slot = i % 3;
                if (n.beamCount() != (slot == 1 ? 2 : 1) || n.augmentationDots() > (slot == 0 ? 1 : 0))
                    return null;
                if (slot == 0 && n.augmentationDots() == 0) absentLead++;
            } else {
                boolean longValue = (i & 1) == 0;
                if (n.augmentationDots() != (longValue ? 1 : 0)
                        || (longValue ? n.beamCount() != 2 : n.beamCount() < 1 || n.beamCount() > 3))
                    return null;
                if (!longValue && n.beamCount() < 3) missingHooks++;
            }
        }
        boolean singleHook = fragments.size() == 1;
        if (absentLead != (singleHook ? 0 : 1) || missingHooks < 1) return null;
        for (int i = 0; i < fragments.size(); i++) {
            var n = fragments.get(i).note;
            if (!ordinaryRhythm(n) || n.clefBottomDiatonic() != clef || n.augmentationDots() != 0
                    || n.articulations() != 0 || n.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                    || n.beamCount() != (singleHook ? 2 : i == 0 ? 1 : 0) || n.unbeamedDurationBeats() != i) return null;
        }
        return provesMixedRibbon(corrected, bar, kept, fragments, gap)
                && provesMixedRibbon(raw, bar, kept, fragments, gap) ? kept : null;
    }

    private static boolean provesMixedRibbon(Raster p, MeasureRegion bar, List<Head> row,
            List<Head> fragments, float gap) {
        var track = staffTrack(p, bar, gap);
        float right = bar.right() * p.width;
        if (track == null || !barline(p, right, track.at(right))) return false;
        gap = track.at((bar.left() + bar.right()) * p.width * .5f)[1];
        var budget = new PrintedBeamShafts.Budget(560_000);
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>();
        for (var h : row) {
            var actual = PrintedBeamShafts.at(p.ink, p.width, p.height, h.x, h.y, gap, budget);
            if (actual.size() != 1 || actual.get(0).direction() != 1 || budget.refused())
                return false;
            shafts.add(actual.get(0));
        }
        for (int i = 1; i < row.size(); i++) {
            var a = shafts.get(i - 1);
            var b = shafts.get(i);
            int rails = i == 3 || i == 6 || i == 10 ? 0 : i < 6 ? 1 : 2;
            if (!WrappedEighthTriplets.matchesBeamRails(p.ink, p.width, p.height, a, b, gap, rails))
                return false;
            if (i < 6 && i % 3 == 1) {
                var first = row.get(i - 1);
                var middle = row.get(i);
                var last = row.get(i + 1);
                if (!(first.note.augmentationDots() == 1
                                ? detachedDot(p, first, middle.x, gap)
                                : highDetachedDot(p, track, first, middle, gap))
                        || dot(p, middle, last, gap)
                        || !(downstemSecondaryHook(p, track, first, middle, last, gap)
                                || staffAttachedSecondaryHook(p, track, a, b, shafts.get(i + 1), gap)))
                    return false;
            } else if (i >= 6 && (i & 1) == 1) {
                if (!dot(p, row.get(i - 1), row.get(i), gap)
                        || !(OwnedTertiaryBeamlets.provesBackwardHookForShafts(
                                        p.ink, p.width, p.height, b, a, gap)
                                || ClosedDottedHookCadence.staffJoinedBackwardHook(
                                        p.ink, p.width, p.height, b, gap))) return false;
            }
        }
        if (fragments.size() == 1) {
            var fragment = fragments.get(0);
            return staffJoinedTertiaryOwns(p, track, fragment, row.get(9), shafts.get(9), gap, budget)
                    ^ staffJoinedTertiaryOwns(p, track, fragment, row.get(13), shafts.get(13), gap, budget);
        }
        int proved = 0;
        for (int i : new int[] {9, 13}) {
            var fragment = fragments.get(proved);
            if (!reversedRibbonOwns(p, fragment, row.get(i), shafts.get(i), gap, budget)) return false;
            proved++;
        }
        return true;
    }

    /** The staff and a backward third-rail hook can resemble a second head on one shaft. */
    private static boolean staffJoinedTertiaryOwns(
            Raster p, StaffPitchTrack track, Head fragment, Head owner,
            PrintedBeamShafts.Shaft shaft, float gap, PrintedBeamShafts.Budget budget) {
        int axis = (shaft.left() + shaft.right()) / 2;
        float inset = axis - fragment.x, depth = shaft.endY() - fragment.y;
        float separation = fragment.y - owner.y;
        if (inset < gap * .36f || inset > gap * .69f
                || depth < gap * 1.36f || depth > gap * 1.85f
                || separation < gap * 2.55f || separation > gap * 3.2f
                || !ClosedDottedHookCadence.staffJoinedBackwardHook(
                        p.ink, p.width, p.height, shaft, gap)) return false;
        var apparent = PrintedBeamShafts.at(p.ink, p.width, p.height, fragment.x, fragment.y, gap, budget);
        if (apparent.size() != 1 || budget.refused()) return false;
        var reverse = apparent.get(0);
        if (reverse.direction() != -1 || Math.max(shaft.left(), reverse.left())
                > Math.min(shaft.right(), reverse.right())
                || Math.abs(reverse.endY() - owner.y) > gap * .34f) return false;

        int y = Math.round(fragment.y), coreStart = Math.round(axis - gap * .70f);
        int remote = Math.round(axis - gap * 1.12f), outside = Math.round(axis + gap * .26f);
        int radius = Math.round(gap * .13f);
        if (!nearRule(track.at(fragment.x), y - Math.round(gap * .23f), .16f)) return false;
        for (int row = y - radius; row <= y + radius; row++) {
            if (p.dark(remote, row) || p.dark(outside, row)) return false;
            for (int x = coreStart; x <= axis; x++)
                if (!p.dark(x, row)) return false;
        }
        return true;
    }

    private static boolean highDetachedDot(Raster p, StaffPitchTrack track, Head h, Head next, float gap) {
        var rules = track.at(h.x);
        if (rules[0] - 4 * rules[1] - h.y < rules[1] * 2.2f) return false;
        var dot = WrappedTripletDotInk.uniqueCore(p.ink, p.width, p.height,
                h.x, h.y - gap * .23f, next.x, gap);
        if (dot == null || h.y - dot.y() < gap * .68f || h.y - dot.y() > gap * .95f)
            return false;
        for (int y = dot.y() - Math.round(gap * .31f); y <= dot.y() + Math.round(gap * .31f); y++)
            if (p.dark(dot.x() - Math.round(gap * .36f), y)) return false;
        return true;
    }

    private static boolean reversedRibbonOwns(Raster p, Head guest, Head owner,
            PrintedBeamShafts.Shaft shaft, float gap, PrintedBeamShafts.Budget budget) {
        float axis = (shaft.left() + shaft.right()) * .5f;
        float inset = axis - guest.x, depth = shaft.endY() - guest.y;
        if (inset < gap * .40f || inset > gap * .93f || depth < gap * .40f || depth > gap * .83f
                || guest.y - owner.y < gap * 3.50f) return false;
        var apparent = PrintedBeamShafts.at(p.ink, p.width, p.height, guest.x, guest.y, gap, budget);
        if (apparent.size() != 1 || budget.refused()) return false;
        var reverse = apparent.get(0);
        if (reverse.direction() != -1 || Math.max(shaft.left(), reverse.left())
                > Math.min(shaft.right(), reverse.right())
                || Math.abs(reverse.endY() - owner.y) > gap * .34f) return false;
        int row = Math.round(guest.y);
        for (int x = Math.round(guest.x - .54f * gap); x < Math.round(axis); x++)
            for (int y = row - 2; y <= row + 2; y++)
                if (!p.dark(x, y)) return false;
        return true;
    }

    private static ScoreBeamSource mixedRibbonBeams(
            ScoreBeamSource source, List<Head> owners, List<Head> all) {
        var anchors = owners.stream().map(h -> source.noteOrigins().get(h.owner).orElse(null)).toList();
        if (anchors.contains(null)) return source;
        int page = anchors.get(0).pageIndex();
        if (anchors.stream().anyMatch(a -> a.pageIndex() != page)) return source;
        var local = new HashSet<ScoreBeamSource.Anchor>();
        for (var h : all)
            if (!source.noteOrigins().get(h.owner).map(local::add).orElse(true)) return source;
        var used = new HashSet<>(local);
        for (var origin : source.noteOrigins())
            if (origin.isPresent() && local.contains(origin.get()) && !used.remove(origin.get()))
                return source;
        var groups = new ArrayList<ScoreBeamSource.Group>();
        for (var group : source.groups()) {
            var marks = group.columns().stream().flatMap(c -> c.members().stream()).toList();
            if (marks.stream().noneMatch(local::contains)) groups.add(group);
            else if (!local.containsAll(marks) || group.tupletMark() != null) return source;
        }
        var detached = new ArrayList<ScoreBeamSource.Column>();
        for (var c : source.detachedColumns()) {
            if (c.members().stream().noneMatch(local::contains)) detached.add(c);
            else if (!local.containsAll(c.members())) return source;
        }
        for (int first : new int[] {0, 3, 6, 10}) {
            boolean trio = first < 6;
            var columns = anchors.subList(first, first + (trio ? 3 : 4)).stream()
                    .map(a -> new ScoreBeamSource.Column(List.of(a))).toList();
            String id = "mixed-printed-beam:" + page + ":" + anchors.get(first).noteIndex();
            if (groups.stream().anyMatch(g -> g.id().equals(id))) return source;
            var hooks = trio
                    ? List.of(new ScoreBeamSource.Hook(1, 2, ScoreBeamSource.Direction.BACKWARD))
                    : List.of(new ScoreBeamSource.Hook(1, 3, ScoreBeamSource.Direction.BACKWARD),
                              new ScoreBeamSource.Hook(3, 3, ScoreBeamSource.Direction.BACKWARD));
            groups.add(new ScoreBeamSource.Group(id, columns, trio ? List.of(1, 1) : List.of(3, 3, 3),
                    hooks, trio ? ScoreBeamSource.TupletMark.UNNUMBERED : null));
        }
        return new ScoreBeamSource(groups, detached, source.noteOrigins());
    }

    private static boolean detachedDot(Raster raster, Head head, float next, float gap) {
        var dot =
                WrappedTripletDotInk.uniqueCore(
                        raster.ink, raster.width, raster.height, head.x, head.y, next, gap);
        if (dot == null) return false;
        int half = Math.round(gap * .21f);
        for (int x = Math.round(head.x + gap * .65f); x <= dot.x() - half; x++) {
            boolean clear = true;
            for (int y = dot.y() - half; y <= dot.y() + half; y++) {
                if (raster.dark(x, y)) {
                    clear = false;
                    break;
                }
            }
            if (clear) return true;
        }
        return false;
    }

    /** The reversed apparent shaft retraces the actual last note's lower beam end. */
    private static boolean reversedBeamTip(Raster raster, Head guest, Head owner, Bridge beam) {
        float gap = beam.gap;
        if (guest.note.beamCount() != 2
                || guest.note.augmentationDots() != 0
                || guest.note.articulations() != 0
                || beam.b.x - guest.x < gap * .35f
                || beam.b.x - guest.x > gap * .85f
                || guest.y - owner.y < gap * 3.5f
                || guest.y - owner.y > gap * 6
                || Math.abs(guest.y - beam.center(guest.x)) > gap * .26f) return false;
        var budget = new PrintedBeamShafts.Budget(100_000);
        var falseShaft =
                PrintedBeamShafts.at(
                        raster.ink, raster.width, raster.height, guest.x, guest.y, gap, budget);
        var realShaft =
                PrintedBeamShafts.at(
                        raster.ink, raster.width, raster.height, owner.x, owner.y, gap, budget);
        if (budget.refused() || falseShaft.size() != 1 || realShaft.size() != 1) return false;
        var a = falseShaft.get(0);
        var b = realShaft.get(0);
        return a.direction() == -1
                && b.direction() == 1
                && a.left() <= beam.b.x
                && a.right() >= beam.b.x
                && b.left() <= beam.b.x
                && b.right() >= beam.b.x
                && Math.abs(a.startY() - b.endY()) < gap * .6f
                && Math.abs(a.endY() - b.startY()) < gap * .6f;
    }

    private static StaffPitchTrack staffTrack(Raster plane, MeasureRegion bar, float gap) {
        var samples = new ArrayList<float[]>();
        float left = bar.left() * plane.width, right = bar.right() * plane.width;
        for (int i = 0; i <= 12; i++) {
            float x = left + (right - left) * i / 12;
            var sample = PrintedDirectionStaff.printedRules(plane.ink, plane.width, plane.height,
                    x, bar.bottom() * plane.height - 2.1f * gap, gap);
            if (sample != null) samples.add(new float[]{x, sample[0], sample[1]});
        }
        return samples.size() < 7 ? null : StaffPitchTrack.fromVerifiedSamples(samples);
    }

    private static boolean barline(Raster p, float edge, float[] rails) {
        float gap=rails[1];
        if (gap<10 || gap>27 || rails[0]-4*gap<1 || rails[0]>p.height-2) return false;
        var staff=new PlayingTechniqueDetector.Staff(rails[0]-4*gap,rails[0],gap,0,1);
        return PrintedCadenceDurations.boundaryColumn(p.ink,p.width,staff,edge,true,false)>=0;
    }

    private static boolean couldBeBorrowed(Raster p, StaffPitchTrack track, Head h) {
        return h.note.stemDirection()==1 && h.note.beamCount()<=1 && h.note.augmentationDots()==0
                && PrintedStemDirection.detect(p.ink,p.width,p.height,h.x,h.y,track.at(h.x)[1])==0;
    }

    private static boolean pattern(List<Head> notes, int clef) {
        int missingDots=0;
        for (int i=0;i<notes.size();i++) {
            var n=notes.get(i).note;
            int slot=i%3;
            if (n.beamCount()!=(slot==1 ? 2 : 1) || n.clefBottomDiatonic()!=clef
                    || n.stemDirection()!=notes.get(i-slot).note.stemDirection()
                    || n.augmentationDots()>(slot==0 ? 1 : 0)) return false;
            if (slot==0 && n.augmentationDots()==0) missingDots++;
        }
        return missingDots==1;
    }

    private static boolean printedGroups(Raster p, StaffPitchTrack track,
            List<Head> heads, List<Head> extras) {
        int provedExtras=0;
        for (int offset=0;offset<heads.size();offset+=3) {
            var a=heads.get(offset); var b=heads.get(offset+1); var c=heads.get(offset+2);
            float gap=track.at(b.x)[1];
            int direction=b.note.stemDirection();
            if (gap<10 || gap>27 || b.x-a.x<gap*1.7f || b.x-a.x>gap*3.5f
                    || c.x-b.x<gap*1.45f || c.x-b.x>gap*2.25f
                    || !dot(p,a,b,gap) || dot(p,b,c,gap)) return false;
            if (offset+3<heads.size()) {
                var d=heads.get(offset+3);
                if (d.x-c.x<gap*1.7f || d.x-c.x>gap*3.5f || dot(p,c,d,gap)
                        || direction==d.note.stemDirection()
                        && !SeparateBeamGroups.between(p.ink,p.width,p.height,c.x,c.y,d.x,d.y,gap))
                    return false;
            }
            boolean normalFirst=PrintedTupletBeamOwner.connectedAwayFromRules(
                    p.ink,p.width,p.height,a.x,a.y,b.x,b.y,gap,direction,track);
            boolean normalLast=PrintedTupletBeamOwner.connectedAwayFromRules(
                    p.ink,p.width,p.height,b.x,b.y,c.x,c.y,gap,direction,track);
            var extra=extras.stream().filter(h -> h.x>a.x && h.x<b.x).toList();
            if (extra.isEmpty()) {
                if (!normalFirst || !normalLast) return false;
                continue;
            }
            if (extra.size()!=1 || direction!=-1) return false;
            var first=bridge(p,track,a,b,gap);
            var next=bridge(p,track,b,c,gap);
            if (first==null || next==null || Math.abs(first.b.x-next.a.x)>gap*.17f
                    || Math.abs(first.center(b.x)-next.center(b.x))>gap*.38f
                    || !ownedBranch(p,track,extra.get(0),b,first)) return false;
            provedExtras++;
        }
        return provedExtras==extras.size();
    }

    private static boolean dot(Raster p, Head h, Head next, float gap) {
        return WrappedTripletDotInk.uniqueCore(p.ink,p.width,p.height,h.x,h.y,next.x,gap)!=null
                || LegacyTripletDotInk.singleDotCore(p.ink,p.width,p.height,h.x,h.y,next.x,gap);
    }

    private static Bridge bridge(Raster p, StaffPitchTrack track, Head a, Head b, float gap) {
        return bridge(p, track, a, b, gap, 5.4f);
    }

    private static Bridge bridge(Raster p, StaffPitchTrack track, Head a, Head b, float gap, float maxReach) {
        var left=shaftEnds(p,a,gap,maxReach);
        var right=shaftEnds(p,b,gap,maxReach);
        Bridge best=null;
        float bestScore=Float.NEGATIVE_INFINITY;
        int margin=Math.round(gap*.24f);
        int band=Math.max(1,Math.round(gap*.12f));
        for (var x:left) for (var y:right) {
            if (y.x-x.x<gap*1.15f || Math.abs(y.y-x.y)>.63f*(y.x-x.x)) continue;
            for (int inset=Math.round(gap*.23f);inset<=Math.round(gap*.45f);inset++) {
                var candidate=new Bridge(x,y,inset,gap);
                int full=0;
                int isolated=0;
                int cols=y.x-x.x-2*margin+1;
                for (int px=x.x+margin;px<=y.x-margin;px++) {
                    int cy=Math.round(candidate.center(px));
                    int hits=0;
                    for (int dy=-band;dy<=band;dy++) if (p.dark(px,cy+dy)) hits++;
                    if (hits>=2*band) full++;
                    if (!nearRule(track.at(px),cy,.22f)) isolated++;
                }
                if (cols<8 || full<cols*.94f || isolated<cols*.35f) continue;
                float score=full/(float)cols+x.density+y.density-.01f*inset;
                if (score>bestScore) {
                    best=candidate;
                    bestScore=score;
                }
            }
        }
        return best;
    }

    /** Allow small paper holes only between an ink-attached head shoulder and a dark far tip. */
    private static List<Shaft> shaftEnds(Raster p, Head head, float gap) {
        return shaftEnds(p, head, gap, 5.4f);
    }

    private static List<Shaft> shaftEnds(Raster p, Head head, float gap, float maxReach) {
        int travel=-head.note.stemDirection();
        int center=Math.round(head.x-travel*gap*.52f), shoulder=Math.round(gap*.24f);
        int first=Math.round(head.y+travel*gap*.23f);
        int minimum=Math.round(gap*1.8f), limit=Math.round(gap*maxReach);
        List<Shaft> shafts=new ArrayList<>();
        for (int x=center-shoulder;x<=center+shoulder;x++) {
            if (!p.dark(x,first) && !p.dark(x,first+travel) && !p.dark(x,first+2*travel))
                continue;
            int dark=0, missed=0;
            for (int step=0;step<=limit;step++) {
                int y=first+step*travel;
                boolean hit=p.dark(x,y);
                if (hit) { dark++; missed=0; }
                else if (++missed>gap*.57f) break;
                float density=dark/(float)(step+1);
                if (step<minimum || !hit || density<.78f) continue;
                int clear=0;
                for (int d=1;d<=4;d++) if (!p.dark(x,y+travel*d)) clear++;
                if (clear>=3) shafts.add(new Shaft(x,y,travel,density));
            }
        }
        return shafts;
    }

    private static boolean ownedBranch(Raster p, StaffPitchTrack track, Head guest, Head owner,
            Bridge primary) {
        float gap=primary.gap;
        int shaft=primary.b.x;
        if (shaft-guest.x<gap*.22f || shaft-guest.x>gap*.63f
                || owner.x-guest.x<gap*.7f || owner.x-guest.x>gap*1.25f
                || guest.y-owner.y<gap*2.3f || guest.y-owner.y>gap*4.1f
                || primary.center(guest.x)-guest.y<gap*.45f
                || primary.center(guest.x)-guest.y>gap*1.05f) return false;
        int left=Math.round(guest.x-gap*.96f), right=Math.round(guest.x+gap*.91f);
        int top=Math.round(guest.y-gap*1.02f), bottom=Math.round(guest.y+gap*.65f);
        int minX=right+1, maxX=left-1, foreign=0, area=0;
        for (int x=left;x<=right;x++) {
            float[] rails=track.at(x);
            if (x<shaft-gap*.12f || x>shaft+gap*.14f) {
                int mark=0;
                for (int y=top;y<=bottom;y++) {
                    if (!p.dark(x,y) || nearRule(rails,y,.16f)
                            || y>=primary.center(x)-gap*(x>shaft ? .63f : .43f)) continue;
                    mark++;
                }
                if (mark>=3) {
                    minX=Math.min(minX,x);
                    maxX=Math.max(maxX,x);
                    area+=mark;
                    if (x>shaft+gap*.14f) foreign+=mark;
                }
            }
        }
        // Separate a true right shoulder from a staff rule crossing the branch.
        int rightRival=0;
        int referenceStart=shaft+Math.round(gap*.68f);
        for (int x=shaft+Math.round(gap*.18f); x<=shaft+Math.round(gap*.5f); x++) {
            for (int y=Math.round(guest.y-gap*.48f);
                    y<=Math.round(guest.y+gap*.11f); y++) {
                if (y>=primary.center(x)-gap*.37f || !p.dark(x,y)) continue;
                int light=0;
                for(int sample=referenceStart;sample<referenceStart+4;sample++)
                    if(!p.dark(sample,y)) light++;
                if(light>=3) rightRival++;
            }
        }
        if (rightRival >= Math.round(gap * gap * .03f)) return false;
        if (foreign>gap*.4f || shaft-minX<gap*.58f || shaft-minX>gap*.99f
                || maxX<shaft-gap*.32f || area<gap*gap*.25f || area>gap*gap*.65f) return false;
        // The branch must join the owned shaft on three rows. A detached spot cannot vote.
        for (int shift=-Math.round(gap*.75f);shift<=Math.round(gap*.14f);shift++) {
            int y=Math.round(guest.y)+shift;
            if (nearRule(track.at(shaft),y,.21f)
                    || y>primary.center(shaft)-gap*.42f) continue;
            boolean full=true;
            for (int x=Math.round(guest.x);x<=shaft;x++)
                for (int d=-1;d<=1;d++)
                    if (!p.dark(x,y+d)) full=false;
            if (full) return true;
        }
        return false;
    }

    private static boolean nearRule(float[] rails, float y, float tolerance) {
        float line=(rails[0]-y)/rails[1];
        return line>=-.18f && line<=4.18f && Math.abs(line-Math.round(line))<=tolerance;
    }

    private static ScoreNoteEvent withTriplet(ScoreNoteEvent n,int dots) {
        return withRhythm(n, dots, n.beamCount(), 3, 2);
    }

    private static ScoreNoteEvent withRhythm(ScoreNoteEvent n, int dots, int beams, int actual, int normal) {
        return withRhythm(n,dots,beams,actual,normal,n.followingRestBeats());
    }

    private static ScoreNoteEvent withRhythm(ScoreNoteEvent n, int dots, int beams, int actual, int normal,
                                              float followingRestBeats) {
        return withRhythm(n, dots, beams, actual, normal, followingRestBeats, n.unbeamedDurationBeats());
    }

    private static ScoreNoteEvent withRhythm(ScoreNoteEvent n, int dots, int beams, int actual, int normal,
            float followingRestBeats, float unbeamedDurationBeats) {
        return new ScoreNoteEvent(n.measureIndex(),n.positionInMeasure(),n.staffStep(),n.staffIndex(),
                n.staffCount(),n.pageY(),n.tiedFromPrevious(),dots,beams,n.writtenAccidental(),
                unbeamedDurationBeats,actual,followingRestBeats,n.articulations(),
                n.clefBottomDiatonic(),n.crossStaffBeam(),n.leadingRestBeats(),n.compactOpening(),
                n.octaveShift(),n.boundaryTies(),normal,n.stemDirection(),n.kind(),n.visualSize(),
                n.harmonicSemitones(),n.openStringTouch());
    }
}
