// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
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
        if (gray == null || gray.length != (long) width*height || width < 256 || height < 128)
            return score;
        var raster = new Raster(gray, width, height);
        var notes = score.notes();
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
                if (qualifiedAliasedMonophonic(raster, new Raster(raw, width, height),
                        score.measures(), index, lane, openingSixFour, false)) {
                    for (int slot = 0; slot < lane.size(); slot++) {
                        var n = lane.get(slot);
                        rewrite[n.owner] = withRhythm(n.note, slot % 3 == 0 ? 1 : 0, slot % 3 == 1 ? 2 : 1, 3, 2);
                    }
                    changed = true;
                    continue;
                }
            }
            if (meter.numerator() == 4
                    && (heads.size() == 13 || heads.size() == 14)
                    && raw != null
                    && raw.length == gray.length
                    && score.rests().stream().noneMatch(r -> r.measureIndex() == index)) {
                var triplets =
                        qualifiedFourGroupsWithBeamAliases(
                                raster,
                                new Raster(raw, width, height),
                                score.measures().get(barIndex),
                                heads);
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
            boolean mixed = meter.numerator() == 4 && heads.size() == 16;
            if (mixed || meter.numerator() == 6 && heads.size() == 20) {
                if (score.rests().stream().anyMatch(r -> r.measureIndex() == index)) continue;
            } else continue;
            if (mixed) {
                var kept = qualifiedMixedRun(raster, score.measures().get(barIndex), heads);
                if (kept == null) continue;
                for (var head : heads) remove[head.owner] = true;
                for (int slot = 0; slot < kept.size(); slot++) {
                    var head = kept.get(slot);
                    remove[head.owner] = false;
                    if (slot < 6)
                        rewrite[head.owner] = withTriplet(head.note, slot % 3 == 0 ? 1 : 0);
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
        var changedNotes = score.withNotes(edited, owners);
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
                && (n.articulations() & NoteOrnament.GRACE) == 0
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

    private static boolean beamGuest(ScoreNoteEvent note) {
        return ordinaryRhythm(note)
                && note.augmentationDots() == 0
                && note.articulations() == 0
                && note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                && (note.stemDirection() == 1
                                && (note.beamCount() == 1 && note.unbeamedDurationBeats() == 0
                                        || note.beamCount() == 0
                                                && note.unbeamedDurationBeats() == 1)
                        || note.stemDirection() == 0
                                && note.beamCount() == 2
                                && note.unbeamedDurationBeats() == 0);
    }

    private static boolean ownsTripletBeamGuest(
            Raster p, StaffPitchTrack track, Head a, Head b, Head c, Head guest, float gap) {
        boolean atCorner = guest.note.stemDirection() == 0;
        if (atCorner) {
            if (Math.abs(guest.x - a.x) > gap * .18f
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
        int shaft = first.b.x;
        if (shaft - mask.x < gap * .30f || shaft - mask.x > gap * .85f
                || mask.y - first.center(mask.x) < -gap * .95f
                || mask.y - first.center(mask.x) > -gap * .34f) return false;

        var budget = new PrintedBeamShafts.Budget(100_000);
        var reverse = PrintedBeamShafts.at(raster.ink,raster.width,raster.height,mask.x,mask.y,gap,budget);
        if (reverse.size() != 1 || budget.refused()) return false;
        var witness = reverse.get(0);
        if (witness.direction() != -1 || shaft < witness.left() - 1 || shaft > witness.right() + 1
                || Math.abs(witness.endY() - b.y) > gap * .21f) return false;

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
            if (track == null || !barline(raster, right, track.at(right))) return false;
            if (!(openingAlias ? WrappedMeasureBoundaries.confirmsOpeningTripletBar(
                    raster.ink, raster.width, raster.height, regions, heads.get(0).x, heads.get(last).x, true)
                    : barline(raster, left, track.at(left)))) return false;
            for (int i = 0; i < heads.size(); i += 3) {
                var a = heads.get(i); var b = heads.get(i + 1); var c = heads.get(i + 2);
                float local = track.at(b.x)[1];
                int direction = b.note.stemDirection();
                if (b.x - a.x < local * 1.9f || b.x - a.x > local * 3.85f
                        || c.x - b.x < local * 1.3f || c.x - b.x > local * 3.1f
                        || !dot(raster, a, b, local)
                        || (dot(raster, b, c, local) && !(provedMask && flatOwnsTripletGap(raster, b, c, local)))
                        || !tripletPrimaryLink(raster, track, a, b, local, direction, provedMask)
                        || !tripletPrimaryLink(raster, track, b, c, local, direction, provedMask))
                    return false;
                if (openingAlias && i == 0 && !openingStaccatoTriplet(raster, track, a, b, c, local))
                    return false;
                if (a.note.augmentationDots() == 2 && !oneDetachedAugmentationDot(raster, a, b.x, local)) return false;
                if (provedMask && a.note.augmentationDots() == 0 && !detachedDot(raster, a, b.x, local))
                    return false;
                if (provedMask && b.note.augmentationDots() == 1
                        && !neighborStaccatosOwnDot(raster, b, c, local))
                    return false;
                if (b.note.beamCount() == 1 && !missedHook(raster, track, a, b, c, local)) return false;
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
        for (int i = 0; i < heads.size(); i++) {
            var note = heads.get(i).note;
            int slot = i % 3;
            if (!ordinary(note) || note.unbeamedDurationBeats() != 0
                    || note.clefBottomDiatonic() != clef || note.beamCount() != (slot == 1 ? 2 : 1)
                    || note.stemDirection() != heads.get(i - slot).note.stemDirection()
                    || slot == 0 && note.augmentationDots() < 1) return false;
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
                                plane.ink, plane.width, plane.height, c.x, c.y, following.x, following.y, local))
                    return false;
            }
        }
        return true;
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
        var left=shaftEnds(p,a,gap);
        var right=shaftEnds(p,b,gap);
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
        int travel=-head.note.stemDirection();
        int center=Math.round(head.x-travel*gap*.52f), shoulder=Math.round(gap*.24f);
        int first=Math.round(head.y+travel*gap*.23f);
        int minimum=Math.round(gap*1.8f), limit=Math.round(gap*5.4f);
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
        return new ScoreNoteEvent(n.measureIndex(),n.positionInMeasure(),n.staffStep(),n.staffIndex(),
                n.staffCount(),n.pageY(),n.tiedFromPrevious(),dots,beams,n.writtenAccidental(),
                n.unbeamedDurationBeats(),actual,followingRestBeats,n.articulations(),
                n.clefBottomDiatonic(),n.crossStaffBeam(),n.leadingRestBeats(),n.compactOpening(),
                n.octaveShift(),n.boundaryTies(),normal,n.stemDirection(),n.kind(),n.visualSize(),
                n.harmonicSemitones(),n.openStringTouch());
    }
}
