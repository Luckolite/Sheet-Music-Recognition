// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;

/** Restore the shared secondary rail on a closing head when the entire three-beat bar proves it. */
final class ClosedMonophonicSixteenths {
    private ClosedMonophonicSixteenths() {}

    static List<ScoreNoteEvent> restoreClosingRail(
            List<ScoreNoteEvent> notes, List<MeasureRegion> measures, List<ScoreMeterChange> meters,
            ScoreMeterChange initial, byte[] gray, byte[] raw, int width, int height) {
        if (gray == null || raw == null || gray.length != (long) width * height
                || raw.length != gray.length) return notes;
        var byMeasure = new ArrayList<List<Integer>>(measures.size());
        for (int i = 0; i < measures.size(); i++) byMeasure.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) {
            var n = notes.get(i);
            if (n.measureIndex() >= 0 && n.measureIndex() < measures.size()) byMeasure.get(n.measureIndex()).add(i);
        }
        List<ScoreNoteEvent> repaired = null;
        var meter = initial;
        int nextMeter = 0;
        for (int i = 0; i < measures.size(); i++) {
            while (nextMeter < meters.size() && meters.get(nextMeter).measureIndex() <= i)
                meter = meters.get(nextMeter++);
            var row = byMeasure.get(i);
            var bar = measures.get(i);
            if (meter == null || meter.numerator() != 3 || meter.denominator() != 4
                    || row.size() != 12 || bar.cadenceQuarterBeats() != 0) continue;
            row.sort(Comparator.comparingDouble(k -> notes.get(k).positionInMeasure()));
            var music = row.stream().map(notes::get).toList();
            var closing = music.get(music.size() - 1);
            if (closing.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_NATURAL
                    || closing.staffStep() < 8 || closing.staffStep() != music.get(10).staffStep()) continue;
            boolean compatible = true;
            for (int j = 0; j < music.size(); j++) {
                var n = music.get(j);
                if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffCount() != 1 || n.staffIndex() != 0
                        || n.beamCount() != (j == 11 ? 1 : 2) || n.stemDirection() != -1
                        || n.unbeamedDurationBeats() != 0 || n.tupletDivisor() != 1 || n.tupletNormalNotes() != 1
                        || n.augmentationDots() != 0 || n.articulations() != 0 || n.crossStaffBeam()
                        || n.visualSize() != ScoreNoteEvent.VisualSize.FULL || n.harmonicSemitones() != 0
                        || n.openStringTouch() != null || n.tiedFromPrevious() || n.boundaryTies() != 0
                        || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0) {
                    compatible = false;
                    break;
                }
            }
            if (!compatible || !closedThreeGroups(gray, width, height, bar, music)
                    || !closedThreeGroups(raw, width, height, bar, music)) continue;
            if (repaired == null) repaired = new ArrayList<>(notes);
            repaired.set(row.get(row.size() - 1), new ScoreNoteEvent(closing.measureIndex(), closing.positionInMeasure(),
                    closing.staffStep(), closing.staffIndex(), closing.staffCount(), closing.pageY(),
                    closing.tiedFromPrevious(), closing.augmentationDots(), 2, closing.writtenAccidental(),
                    closing.unbeamedDurationBeats(), closing.tupletDivisor(), closing.followingRestBeats(),
                    closing.articulations(), closing.clefBottomDiatonic(), closing.crossStaffBeam(),
                    closing.leadingRestBeats(), closing.compactOpening(), closing.octaveShift(),
                    closing.boundaryTies(), closing.tupletNormalNotes(), closing.stemDirection(),
                    closing.kind(), closing.visualSize(), closing.harmonicSemitones(), closing.openStringTouch()));
        }
        return repaired == null ? notes : List.copyOf(repaired);
    }

    /** A closed common-time bar can disprove an apparent eighth carried entirely by two beam rails. */
    static ScorePageInterpretation removeBorrowedRailEighth(ScorePageInterpretation score, ScoreMeterChange initial,
            byte[] gray, byte[] raw, int width, int height) {
        if (gray == null || raw == null || gray.length != (long) width * height
                || raw.length != gray.length) return score;
        var bars = score.measures();
        var notes = score.notes();
        var rows = new ArrayList<List<Integer>>(bars.size());
        for (int i = 0; i < bars.size(); i++) rows.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) rows.get(notes.get(i).measureIndex()).add(i);
        var removed = new HashSet<Integer>();
        var corrected = new java.util.HashMap<Integer, ScoreNoteEvent>();
        var beams = score.beamSource();
        var meter = initial;
        int nextMeter = 0;
        for (int i = 0; i < bars.size(); i++) {
            while (nextMeter < score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex() <= i)
                meter = score.meterChanges().get(nextMeter++);
            var row = rows.get(i);
            var bar = bars.get(i);
            final int measure = i;
            boolean halfCadence = row.size() == 10;
            if (meter == null || meter.numerator() != 4 || meter.denominator() != 4
                    || bar.cadenceQuarterBeats() != 0 || !halfCadence && row.size() != 17
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == measure)) continue;
            row.sort(Comparator.comparingDouble(j -> notes.get(j).positionInMeasure()));
            var candidates = row.stream().filter(j -> railEighth(notes.get(j), halfCadence)).toList();
            if (candidates.size() != 1) continue;
            int owner = candidates.get(0);
            int slot = row.indexOf(owner);
            if (slot == 0 || slot == 16 || slot % 4 == 0 || halfCadence && slot != 3) continue;
            row.remove(Integer.valueOf(owner));
            var held = halfCadence ? notes.get(row.remove(row.size() - 1)) : null;
            var music = new ArrayList<>(row.stream().map(notes::get).toList());
            var alias = notes.get(owner);
            var after = music.get(slot);
            if (halfCadence) {
                if (!cadenceHalf(held) || held.stemDirection() != -1 || after.beamCount() != 1
                        || after.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                        || held.octaveShift() != alias.octaveShift()
                        || music.stream().anyMatch(n -> n.octaveShift() != alias.octaveShift())) continue;
                music.set(slot, withSecondaryRail(after));
            }
            if (music.stream().anyMatch(n -> !regularQuartetHead(n) || n.stemDirection() != -1
                    || n.tupletDivisor() != 1 || n.augmentationDots() != 0 || n.tiedFromPrevious())) continue;
            var before = music.get(slot - 1);
            if (!railOwnsEighth(gray, width, height, bar, alias, before, after, halfCadence)
                    || !railOwnsEighth(raw, width, height, bar, alias, before, after, halfCadence)
                    || !provesRegularQuartets(gray, width, height, bar, music)
                    || !provesRegularQuartets(raw, width, height, bar, music)) continue;
            if (halfCadence) {
                var last = music.get(music.size() - 1);
                if (!isolatedHalf(gray, width, height, bar, last, held)
                        || !isolatedHalf(raw, width, height, bar, last, held)) continue;
                corrected.put(row.get(slot), music.get(slot));
                beams = mergeCadenceQuartets(beams, row);
            }
            removed.add(owner);
        }
        if (removed.isEmpty()) return score;
        var owners = java.util.stream.IntStream.range(0, notes.size())
                .filter(i -> !removed.contains(i)).boxed().toList();
        return score.withBeamSource(beams).withNotes(
                owners.stream().map(i -> corrected.getOrDefault(i, notes.get(i))).toList(), owners);
    }

    private static boolean railEighth(ScoreNoteEvent n, boolean halfCadence) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.staffIndex() == 0 && n.staffCount() == 1
                && n.stemDirection() == 1 && n.beamCount() == 1 && n.unbeamedDurationBeats() == 0
                && n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                && n.harmonicSemitones() == 0 && n.openStringTouch() == null && (halfCadence || n.octaveShift() == 0)
                && n.tupletDivisor() == 1 && n.augmentationDots() == 0 && !n.crossStaffBeam()
                && !n.tiedFromPrevious() && n.boundaryTies() == 0 && n.articulations() == 0
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0;
    }

    private static boolean railOwnsEighth(byte[] ink, int width, int height, MeasureRegion bar,
            ScoreNoteEvent alias, ScoreNoteEvent before, ScoreNoteEvent after, boolean halfCadence) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25) return false;
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float x = left + alias.positionInMeasure() * span, y = alias.pageY() * height;
        float bx = left + before.positionInMeasure() * span, by = before.pageY() * height;
        float ax = left + after.positionInMeasure() * span, ay = after.pageY() * height;
        if (x - bx < gap * .75f || ax - x < gap * .75f || ax - x > gap * 1.4f) return false;
        var budget = new PrintedBeamShafts.Budget(45_000);
        var borrowed = PrintedBeamShafts.at(ink, width, height, x, y, gap, budget);
        var preceding = PrintedBeamShafts.at(ink, width, height, bx, by, gap, budget);
        var following = PrintedBeamShafts.at(ink, width, height, ax, ay, gap, budget);
        if (budget.refused() || borrowed.size() != 1 || preceding.size() != 1 || following.size() != 1)
            return false;
        var reverse = borrowed.get(0);
        var a = preceding.get(0);
        var b = following.get(0);
        if (reverse.direction() != -1 || a.direction() != 1 || b.direction() != 1
                || reverse.left() < b.left() || reverse.left() > b.right() || reverse.right() > b.right() + 1
                || Math.abs(reverse.endY() - (halfCadence ? ay : ay - 1.5f * gap)) > gap * .42f
                || x <= a.right() || x >= b.left() - gap * .2f) return false;
        float x0 = (a.left() + a.right()) * .5f, x1 = (b.left() + b.right()) * .5f;
        float slope = (b.endY() - a.endY()) / (x1 - x0);
        float edge = a.endY() + slope * (x - x0);
        if (Math.abs(slope) > .3f || edge - y < gap * .40f || edge - y > gap * .86f)
            return false;
        int first = Math.round(x - gap * .34f), last = Math.min(b.left() - 1, Math.round(x + gap * .22f));
        if (last - first < gap * .38f) return false;
        for (int col = first; col <= last; col++) {
            float boundary = edge + slope * (col - x);
            int below = Math.round(boundary + gap * .33f);
            if (!halfCadence && (below < 0 || below >= height || (ink[below * width + col] & 255) < 205))
                return false;
            if (halfCadence) {
                // A staff rule can cross the window below a sloped beam.
                int paper = 0;
                int limit = Math.round(boundary + gap * .73f);
                if (below < 0 || limit >= height) return false;
                for (int at = below; at <= limit; at++) {
                    paper = (ink[at * width + col] & 255) >= 205 ? paper + 1 : 0;
                    if (paper >= 2) break;
                }
                if (paper < 2) return false;
            }
        }
        return true;
    }

    /** Reconcile a shortened inferred rail when both image planes prove a complete printed quartet. */
    static ScorePageInterpretation restoreOwnedSecondaryRails(
            ScorePageInterpretation score, byte[] gray, byte[] raw, int width, int height) {
        if (gray == null || raw == null || gray.length != (long) width * height || raw.length != gray.length
                || score.beamSource().groups().isEmpty()) return score;
        var notes = score.notes();
        List<ScoreNoteEvent> repaired = null;
        for (var group : score.beamSource().resolve().groups()) {
            if (!group.source().bridges().equals(List.of(3, 3, 3)) || !group.source().hooks().isEmpty()
                    || group.columns().stream().anyMatch(c -> !c.usable()
                            || c.source().members().size() != 1)) continue;
            var owners = group.columns().stream().map(c -> c.owners().get(0)).toList();
            var music = owners.stream().map(notes::get).toList();
            var first = music.get(0);
            if (music.stream().noneMatch(n -> n.beamCount() == 1)
                    || music.stream().noneMatch(n -> n.beamCount() == 2)
                    || music.stream().anyMatch(n -> n.beamCount() < 1 || n.beamCount() > 2
                            || !regularBeamHead(n, n.beamCount()) || n.stemDirection() != first.stemDirection()
                            || n.measureIndex() != first.measureIndex() || n.tupletDivisor() != 1
                            || n.tupletNormalNotes() != 1 || n.augmentationDots() != 0 || n.tiedFromPrevious()))
                continue;
            var bar = score.measures().get(first.measureIndex());
            if (!provesOwnedSecondaryRails(raw, width, height, bar, music)
                    || !provesOwnedSecondaryRails(gray, width, height, bar, music)) continue;
            if (repaired == null) repaired = new ArrayList<>(notes);
            for (int owner : owners) {
                var note = notes.get(owner);
                if (note.beamCount() == 1) repaired.set(owner, note.withBeamAndStem(2, note.stemDirection()));
            }
        }
        return repaired == null ? score : score.withNotes(repaired,
                java.util.stream.IntStream.range(0, notes.size()).boxed().toList());
    }

    private static boolean provesOwnedSecondaryRails(byte[] ink, int width, int height,
            MeasureRegion bar, List<ScoreNoteEvent> music) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 8 || gap > 26) return false;
        float floor = bar.bottom() * height - 2.1f * gap;
        var staff = StaffPitchTrack.linear(width, floor, gap, 0);
        var budget = new PrintedBeamShafts.Budget(180_000);
        PrintedBeamShafts.Shaft previous = null;
        float previousX = 0;
        for (var note : music) {
            float x = (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
            if (previous != null && (x - previousX < 1.5f * gap || x - previousX > 6.0f * gap)) return false;
            var shafts = PrintedBeamShafts.at(ink, width, height, x, note.pageY() * height, gap, budget);
            if (shafts.size() != 1 || shafts.get(0).direction() != -note.stemDirection()) return false;
            var shaft = shafts.get(0);
            if (previous != null && !WrappedEighthTriplets.matchesExactlyTwoBeamRails(
                    ink, width, height, previous, shaft, gap, staff)) return false;
            previous = shaft;
            previousX = x;
        }
        return !budget.refused();
    }


    static boolean cadenceHalf(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.staffIndex() == 0 && n.staffCount() == 1 && Math.abs(n.stemDirection()) == 1
                && n.beamCount() == 0 && n.unbeamedDurationBeats() == 2
                && n.augmentationDots() == 0 && n.tupletDivisor() == 1 && !n.crossStaffBeam()
                && !n.tiedFromPrevious() && n.boundaryTies() == 0
                && (n.articulations() & NoteOrnament.GRACE) == 0
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0
                && n.harmonicSemitones() == 0 && n.openStringTouch() == null;
    }

    static boolean isolatedHalf(byte[] ink, int width, int height, MeasureRegion bar,
            ScoreNoteEvent before, ScoreNoteEvent half) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float x = left + half.positionInMeasure() * span, y = half.pageY() * height;
        float previousX = left + before.positionInMeasure() * span;
        if (x - previousX < 1.75f * gap || x - previousX > 3.2f * gap
                || !ArtificialHarmonics.enclosedCounter(ink, width, height, x, y, .72f * gap, .31f * gap))
            return false;
        var budget = new PrintedBeamShafts.Budget(65_000);
        var a = PrintedBeamShafts.at(ink, width, height, previousX, before.pageY() * height, gap, budget);
        var b = PrintedBeamShafts.at(ink, width, height, x, y, gap, budget);
        return !budget.refused() && a.size() == 1 && b.size() == 1
                && b.get(0).direction() == -half.stemDirection()
                && !WrappedEighthTriplets.matchesBeamRails(ink, width, height, a.get(0), b.get(0), gap, 1);
    }

    private static ScoreNoteEvent withSecondaryRail(ScoreNoteEvent note) {
        return new ScoreNoteEvent(note.measureIndex(), note.positionInMeasure(), note.staffStep(), note.staffIndex(),
                note.staffCount(), note.pageY(), note.tiedFromPrevious(), note.augmentationDots(), 2,
                note.writtenAccidental(), note.unbeamedDurationBeats(), note.tupletDivisor(), note.followingRestBeats(),
                note.articulations(), note.clefBottomDiatonic(), note.crossStaffBeam(), note.leadingRestBeats(),
                note.compactOpening(), note.octaveShift(), note.boundaryTies(), note.tupletNormalNotes(),
                note.stemDirection(), note.kind(), note.visualSize(), note.harmonicSemitones(), note.openStringTouch());
    }

    private static ScoreBeamSource mergeCadenceQuartets(ScoreBeamSource source, List<Integer> owners) {
        return mergeCadenceQuartets(source, owners, false);
    }

    private static ScoreBeamSource mergeCadenceQuartets(
            ScoreBeamSource source, List<Integer> owners, boolean restoreMissing) {
        var origins = owners.stream().map(source.noteOrigins()::get).toList();
        if (origins.stream().anyMatch(java.util.Optional::isEmpty)) return source;
        var anchors = origins.stream().map(java.util.Optional::orElseThrow).toList();
        if (owners.isEmpty() || owners.size() % 4 != 0 || new HashSet<>(anchors).size() != owners.size()
                || anchors.stream().anyMatch(a -> a.pageIndex() != anchors.get(0).pageIndex())) return source;
        var groups = new ArrayList<>(source.groups());
        for (int i = 0; i < anchors.size(); i += 4) {
            var quartet = anchors.subList(i, i + 4);
            var columns = quartet.stream().map(a -> new ScoreBeamSource.Column(List.of(a))).toList();
            if (source.detachedColumns().stream().anyMatch(c -> c.members().stream().anyMatch(quartet::contains)))
                return source;
            var existing = groups.stream().filter(g -> g.columns().stream()
                    .anyMatch(c -> c.members().stream().anyMatch(quartet::contains))).toList();
            if (existing.isEmpty() && restoreMissing) {
                var anchor = quartet.get(0);
                groups.add(new ScoreBeamSource.Group("proved-straight-rail:" + anchor.pageIndex() + ":" + anchor.noteIndex(),
                        columns, List.of(3, 3, 3), List.of()));
                continue;
            }
            if (existing.isEmpty() || existing.stream().anyMatch(g -> !columns.containsAll(g.columns())
                    || !g.hooks().isEmpty() || g.tupletMark() != null)) return source;
            int position = groups.indexOf(existing.get(0));
            groups.removeAll(existing);
            groups.add(position, new ScoreBeamSource.Group(
                    existing.get(0).id(), columns, List.of(3, 3, 3), List.of()));
        }
        return new ScoreBeamSource(groups, source.detachedColumns(), source.noteOrigins());
    }

    /** A verified straight bar can retain only the leading three columns of its final rail. */
    static ScorePageInterpretation restorePrintedGroups(
            ScorePageInterpretation score, ScoreMeterChange initial,
            byte[] gray, byte[] raw, int width, int height) {
        if (gray == null || raw == null || gray.length != (long) width * height
                || raw.length != gray.length) return score;
        var source = score.beamSource();
        if (source.groups().isEmpty() || source.noteOrigins().size() != score.notes().size()) return score;
        var notes = score.notes();
        var byMeasure = new ArrayList<List<Integer>>(score.measures().size());
        for (int i = 0; i < score.measures().size(); i++) byMeasure.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) {
            var n = notes.get(i);
            if (n.measureIndex() >= 0 && n.measureIndex() < byMeasure.size()) byMeasure.get(n.measureIndex()).add(i);
        }
        var groups = new ArrayList<>(source.groups());
        var detached = new ArrayList<>(source.detachedColumns());
        var meter = initial;
        int nextMeter = 0;
        for (int bar = 0; bar < byMeasure.size(); bar++) {
            while (nextMeter < score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex() <= bar)
                meter = score.meterChanges().get(nextMeter++);
            var geometry = score.measures().get(bar);
            if (meter == null || meter.numerator() != 3 || meter.denominator() != 4
                    || geometry.cadenceQuarterBeats() != 0 || byMeasure.get(bar).size() != 12) continue;
            final int measure = bar;
            if (score.rests().stream().anyMatch(r -> r.measureIndex() == measure)) continue;
            var row = byMeasure.get(bar);
            row.sort(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure()));
            var members = row.stream().map(notes::get).toList();
            if (members.stream().anyMatch(n -> n.kind() != ScoreNoteEvent.Kind.PITCHED
                    || n.staffCount() != 1 || n.staffIndex() != 0 || n.beamCount() != 2
                    || n.stemDirection() != -1 || n.unbeamedDurationBeats() != 0
                    || n.tupletDivisor() != 1 || n.tupletNormalNotes() != 1 || n.augmentationDots() != 0
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL || n.crossStaffBeam()
                    || n.harmonicSemitones() != 0 || n.openStringTouch() != null
                    || n.tiedFromPrevious() || n.boundaryTies() != 0 || n.articulations() != 0
                    || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0)) continue;
            var origins = row.stream().map(source.noteOrigins()::get).toList();
            if (origins.stream().anyMatch(java.util.Optional::isEmpty)) continue;
            var anchors = origins.stream().map(java.util.Optional::orElseThrow).toList();
            var owned = new HashSet<>(anchors);
            if (owned.size() != 12 || anchors.stream().anyMatch(a -> a.pageIndex() != anchors.get(0).pageIndex()))
                continue;
            var columns = anchors.stream().map(a -> new ScoreBeamSource.Column(List.of(a))).toList();
            var overlaps = groups.stream().filter(g -> g.columns().stream()
                    .anyMatch(c -> c.members().stream().anyMatch(owned::contains))).toList();
            if (overlaps.size() != 1) continue;
            var partial = overlaps.get(0);
            if (!partial.columns().equals(columns.subList(8, 11))
                    || !partial.bridges().equals(List.of(3, 3)) || !partial.hooks().isEmpty()
                    || partial.tupletMark() != null
                    || detached.stream().anyMatch(c -> c.members().size() != 1
                            && c.members().stream().anyMatch(owned::contains))) continue;
            if (!closedThreeGroups(gray, width, height, geometry, members)
                    || !closedThreeGroups(raw, width, height, geometry, members)) continue;
            int first = groups.indexOf(partial);
            groups.set(first, new ScoreBeamSource.Group(partial.id(), columns.subList(8, 12),
                    List.of(3, 3, 3), List.of()));
            for (int i = 4; i >= 0; i -= 4) {
                var anchor = anchors.get(i);
                groups.add(first, new ScoreBeamSource.Group(
                        "closed-monophonic-sixteenths:" + anchor.pageIndex() + ":" + anchor.noteIndex(),
                        columns.subList(i, i + 4), List.of(3, 3, 3), List.of()));
            }
            detached.removeIf(c -> owned.contains(c.members().get(0)));
        }
        if (groups.equals(source.groups())) return score;
        return score.withBeamSource(new ScoreBeamSource(groups, detached, source.noteOrigins()));
    }

    /** Paired printed barlines and three complete quartets can disprove a local clock alias. */
    static ScorePageInterpretation restoreWrittenClock(
            ScorePageInterpretation score, ScoreMeterChange initial, List<PlayingTechniqueDetector.Word> words,
            byte[] gray, byte[] raw, int width, int height) {
        if (gray == null || raw == null || gray.length != (long) width * height
                || raw.length != gray.length) return score;
        var bars = score.measures();
        var notes = score.notes();
        var rows = new ArrayList<List<Integer>>(bars.size());
        for (int i = 0; i < bars.size(); i++) rows.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            if (note.measureIndex() >= 0 && note.measureIndex() < bars.size())
                rows.get(note.measureIndex()).add(i);
        }
        var hasRest = new java.util.BitSet(bars.size());
        for (var rest : score.rests()) hasRest.set(rest.measureIndex());
        var meter = initial;
        int nextMeter = 0;
        List<ScoreNoteEvent> repaired = null;
        var beamSource = score.beamSource();
        for (int i = 0; i + 1 < bars.size(); i++) {
            while (nextMeter < score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex() <= i)
                meter = score.meterChanges().get(nextMeter++);
            var row = rows.get(i);
            var bar = bars.get(i);
            if ((meter == null || meter.numerator() == 4 && meter.denominator() == 4)
                    && (row.size() == 14 || row.size() == 16)
                    && !hasRest.get(i) && bar.cadenceQuarterBeats() == 0
                    && (i != 0 || !score.openingPickup())) {
                row.sort(Comparator.comparingDouble(j -> notes.get(j).positionInMeasure()));
                var music = row.stream().map(notes::get).toList();
                var lengths = commonTimeQuartetPattern(music);
                if (lengths != null && !ownsPrintedTuplet(beamSource, row)
                        && confirmsCommonTimeRailClock(gray, width, height, bars, i, music, lengths)
                        && confirmsCommonTimeRailClock(raw, width, height, bars, i, music, lengths)) {
                    if (repaired == null) repaired = new ArrayList<>(notes);
                    for (int slot = 0; slot < music.size(); slot++) {
                        if (music.get(slot).tupletDivisor() == 3)
                            repaired.set(row.get(slot), music.get(slot).withTupletRatio(1, 1));
                    }
                    if (row.size() == 16) beamSource = mergeCadenceQuartets(beamSource, row);
                    continue;
                }
            }
            if (i > 0 && meter != null && meter.measureIndex() == i
                    && meter.numerator() == 4 && meter.denominator() == 4
                    && row.size() == 16 && !hasRest.get(i)
                    && bars.get(i-1).bottom() < bars.get(i).top()
                    && Math.abs(bars.get(i+1).top()-bars.get(i).top()) * height < 5f) {
                row.sort(Comparator.comparingDouble(j -> notes.get(j).positionInMeasure()));
                var music = row.stream().map(notes::get).toList();
                if (openingStaccatoQuartets(music)
                        && provesOpeningStaccatoQuartets(gray, width, height, bars.get(i),
                                bars.get(i+1), music)
                        && provesOpeningStaccatoQuartets(raw, width, height, bars.get(i),
                                bars.get(i+1), music)) {
                    if (repaired == null) repaired = new ArrayList<>(notes);
                    repaired.set(row.get(0), withoutAugmentationDot(music.get(0)));
                } else if (openingPairedStaccatos(music)
                        && provesOpeningStaccatoQuartets(gray, width, height, bars.get(i), bars.get(i+1), music, true)
                        && provesOpeningStaccatoQuartets(raw, width, height, bars.get(i), bars.get(i+1), music, true)) {
                    if (repaired == null) repaired = new ArrayList<>(notes);
                    repaired.set(row.get(2), withoutAugmentationDot(music.get(2)));
                    repaired.set(row.get(4), withSecondaryRail(music.get(4)));
                }
                continue;
            }
            if (meter != null && meter.numerator() == 4 && meter.denominator() == 4
                    && row.size() == 16 && !hasRest.get(i) && i > 0
                    && bars.get(i-1).bottom() < bars.get(i).top()
                    && bars.get(i).cadenceQuarterBeats() == 0) {
                row.sort(Comparator.comparingDouble(j -> notes.get(j).positionInMeasure()));
                var music = row.stream().map(notes::get).toList();
                if (openingFingeringQuartets(gray, width, height, bars.get(i), music, words)
                        && openingFingeringQuartets(raw, width, height, bars.get(i), music, words)) {
                    if (repaired == null) repaired = new ArrayList<>(notes);
                    for (int j = 0; j < 3; j++)
                        repaired.set(row.get(j), music.get(j).withTupletRatio(1, 1));
                    continue;
                }
            }

            if (meter != null && meter.numerator() == 4 && meter.denominator() == 4
                    && row.size() == 9 && !hasRest.get(i) && bars.get(i).cadenceQuarterBeats() == 0) {
                row.sort(Comparator.comparingDouble(j -> notes.get(j).positionInMeasure()));
                var music = row.stream().map(notes::get).toList();
                int borrowed = FingeringOneAugmentation.borrowedDot(gray, raw, width, height, bars.get(i), music);
                if (borrowed >= 0) {
                    if (repaired == null) repaired = new ArrayList<>(notes);
                    repaired.set(row.get(borrowed), withoutAugmentationDot(music.get(borrowed)));
                }
                continue;
            }

            if (i == 0 || meter == null || meter.numerator() != 3 || meter.denominator() != 4
                    || row.size() != 12 || hasRest.get(i) || bars.get(i).cadenceQuarterBeats() != 0)
                continue;
            row.sort(Comparator.comparingDouble(j -> notes.get(j).positionInMeasure()));
            var music = row.stream().map(notes::get).toList();
            if (!ambiguousClockNotes(music)) continue;
            var ratios = new ArrayList<Integer>(3);
            var dots = new ArrayList<Integer>(1);
            for (int j = 0; j < music.size(); j++) {
                if (music.get(j).tupletDivisor() == 3) ratios.add(j);
                if (music.get(j).augmentationDots() == 1) dots.add(j);
            }
            if (dots.isEmpty() && (ratios.size() == 3 || ratios.size() == 6)
                    && upperFingeringQuartets(ratios, music, words, bars.get(i), gray, width, height)
                    && upperFingeringQuartets(ratios, music, words, bars.get(i), raw, width, height)) {
                if (repaired == null) repaired = new ArrayList<>(notes);
                for (int slot : ratios) repaired.set(row.get(slot), music.get(slot).withTupletRatio(1, 1));
                continue;
            }
            boolean fingers = dots.isEmpty() && ratios.size() == 3
                    && ratios.get(ratios.size() - 1) - ratios.get(0) == 2
                    && ratios.get(ratios.size() - 1) / 4 == ratios.get(0) / 4;
            boolean dotted = ratios.isEmpty() && dots.size() == 1 && dots.get(0) < 11;
            if (!fingers && !dotted) continue;
            int slot = fingers ? ratios.get(1) : dots.get(0);
            if (!provesQuartetClock(gray,width,height,bars,i,music,words,slot,fingers)
                    || !provesQuartetClock(raw,width,height,bars,i,music,words,slot,fingers)) continue;
            if (repaired == null) repaired = new ArrayList<>(notes);
            if (fingers) {
                for (int k : ratios) repaired.set(row.get(k), music.get(k).withTupletRatio(1,1));
            } else {
                repaired.set(row.get(slot), withoutAugmentationDot(music.get(slot)));
            }
        }
        if (repaired == null) return score;
        return score.withNotes(repaired, java.util.stream.IntStream.range(0, notes.size()).boxed().toList())
                .withBeamSource(beamSource);
    }

    private static int[] commonTimeQuartetPattern(List<ScoreNoteEvent> music) {
        // Each group occupies a quarter-note beat: four sixteenths, or one eighth
        // followed by two sixteenths. Shortened subgroups cannot fill that written bar.
        int[] lengths = music.size() == 16 ? new int[] {4, 4, 4, 4} : new int[] {3, 4, 3, 4};
        int reduced = 0;
        int start = 0;
        for (int length : lengths) {
            boolean ratio = music.get(start).tupletDivisor() == 3;
            if (ratio && length != 4) return null;
            for (int j = 0; j < length; j++) {
                var note = music.get(start + j);
                boolean third = ratio && j < 3;
                if (!regularBeamHead(note, length == 3 && j == 0 ? 1 : 2)
                        || note.stemDirection() != music.get(start).stemDirection()
                        || note.augmentationDots() != 0 || note.tiedFromPrevious()
                        || note.tupletDivisor() != (third ? 3 : 1) || note.tupletNormalNotes() != (third ? 2 : 1))
                    return null;
            }
            if (ratio) reduced++;
            start += length;
        }
        return reduced > 0 && reduced < lengths.length ? lengths : null;
    }

    private static boolean ownsPrintedTuplet(ScoreBeamSource source, List<Integer> owners) {
        var anchors = new HashSet<ScoreBeamSource.Anchor>();
        for (int owner : owners) source.noteOrigins().get(owner).ifPresent(anchors::add);
        return source.groups().stream().anyMatch(group -> group.tupletMark() != null
                && group.columns().stream().anyMatch(c -> c.members().stream().anyMatch(anchors::contains)));
    }

    /** A separately proved double-beam clock can reject a fingering-derived 3:2 subgroup. */
    static ScorePageInterpretation restoreMisreadQuartets(ScorePageInterpretation score,
            byte[] gray, byte[] raw, int width, int height) {
        if (gray == null || raw == null || gray.length != (long) width * height
                || gray.length != raw.length || score.meterChanges().isEmpty()) return score;
        var bars = score.measures();
        var notes = score.notes();
        var rows = new ArrayList<List<Integer>>(bars.size());
        for (int i = 0; i < bars.size(); i++) rows.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) rows.get(notes.get(i).measureIndex()).add(i);
        var rests = new java.util.BitSet(bars.size());
        for (var rest : score.rests()) rests.set(rest.measureIndex());
        var beams = score.beamSource();
        List<ScoreNoteEvent> edited = null;
        ScoreMeterChange meter = null;
        int next = 0;
        for (int index = 1; index+1 < bars.size(); index++) {
            while (next < score.meterChanges().size() && score.meterChanges().get(next).measureIndex() <= index)
                meter = score.meterChanges().get(next++);
            var owners = rows.get(index);
            if (meter == null || meter.denominator() != 4 || rests.get(index)
                    || bars.get(index).cadenceQuarterBeats() != 0
                    || !(meter.numerator() == 4 && owners.size() == 16
                            || meter.numerator() == 3 && owners.size() == 12)) continue;
            owners.sort(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure()));
            var row = owners.stream().map(notes::get).toList();
            if (!ambiguousClockNotes(row) || row.stream().anyMatch(n -> n.augmentationDots() != 0)
                    || ownsPrintedTuplet(beams, owners)) continue;
            if (meter.numerator() == 4) {
                var lengths = commonTimeQuartetPattern(row);
                if (lengths == null
                        || !confirmsCommonTimeRailClock(gray, width, height, bars, index, row, lengths, true)
                        || !confirmsCommonTimeRailClock(raw, width, height, bars, index, row, lengths, true)) continue;
            } else {
                var reduced = java.util.stream.IntStream.range(0, row.size())
                        .filter(i -> row.get(i).tupletDivisor() == 3).boxed().toList();
                if (reduced.size() != 3 || reduced.get(2) - reduced.get(0) != 2 || reduced.get(0) % 4 != 1)
                    continue;
                int quartet = reduced.get(0) / 4 * 4;
                if (!provesFingeredStraightQuartet(gray, width, height, bars, index, row, quartet)
                        || !provesFingeredStraightQuartet(raw, width, height, bars, index, row, quartet)) continue;
            }
            var projected = mergeCadenceQuartets(beams, owners, true);
            if (projected == beams) continue;
            if (edited == null) edited = new ArrayList<>(notes);
            for (int owner : owners) {
                var note = notes.get(owner);
                if (note.tupletDivisor() == 3) edited.set(owner, note.withTupletRatio(1, 1));
            }
            beams = projected;
        }
        return edited == null ? score : score.withNotes(
                edited, java.util.stream.IntStream.range(0, notes.size()).boxed().toList())
                .withBeamSource(beams).withClockContextSource(score.clockContextSource());
    }

    private static boolean provesFingeredStraightQuartet(byte[] ink, int width, int height,
            List<MeasureRegion> bars, int index, List<ScoreNoteEvent> row, int quartet) {
        if (!provesQuartetClock(ink, width, height, bars, index, row, List.of(), quartet + 2, false))
            return false;
        var bar = bars.get(index);
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        var a = row.get(quartet + 1);
        var b = row.get(quartet + 2);
        float ax = left + span*a.positionInMeasure(), ay = a.pageY()*height;
        float bx = left + span*b.positionInMeasure(), by = b.pageY()*height;
        if (Math.abs(ay - by) > .35f * gap) return false;
        var first = upperQuartetFinger(ink, width, height, ax, ay, gap);
        var second = upperQuartetFinger(ink, width, height, bx, by, gap);
        if (first == null || second == null || Math.abs(first.top - second.top) > .15f * gap
                || Math.abs(first.bottom - second.bottom) > .15f * gap
                || !FingeringAnnotationFilter.sameDetachedDigit(ink, width, first, second)) return false;
        var digits = new java.util.BitSet();
        var glyph = JoinedDigitTemplates.inspect(ink, width, height, digits,
                first.left, first.top, first.right, first.bottom, () -> {});
        if (glyph == null || glyph.scores()[3] < .68f
                || glyph.best() - glyph.scores()[3] > .015f || glyph.best() - glyph.runner() < .035f)
            return false;
        var staff = dividerStaff(ink, width, height, bar, adjacentDividers(bars, index, width, height, gap), gap);
        if (staff == null) return false;
        float top = height, bottom = 0, lastTip = 0;
        float firstTip = 0;
        var budget = new PrintedBeamShafts.Budget(100_000);
        for (var n : row.subList(quartet, quartet + 4)) {
            float x = left + span*n.positionInMeasure(), y = n.pageY()*height;
            var shafts = PrintedBeamShafts.at(ink, width, height, x, y, gap, budget);
            if (budget.refused() || shafts.size() != 1) return false;
            if (n == row.get(quartet)) {
                firstTip = shafts.get(0).endY();
                continue;
            }
            top = Math.min(top, y);
            bottom = Math.max(bottom, y);
            lastTip = Math.max(lastTip, shafts.get(0).endY());
        }
        var region = UnnumberedTripletInk.sourceRows(bar, bars, height);
        var firstNote = row.get(quartet);
        var lastNote = row.get(quartet + 3);
        float end = left + span * lastNote.positionInMeasure(), firstY = firstNote.pageY() * height;
        return !UnnumberedTripletInk.bracketBesideBeam(ink, width, height, gap,
                    ax, end, top, bottom, lastTip, region, staff, true)
                && !UnnumberedTripletInk.bracketBesideBeam(ink, width, height, gap,
                        left + span * firstNote.positionInMeasure(), end, Math.min(firstY, top), Math.max(firstY, bottom),
                        Math.max(firstTip, lastTip), region, staff, true);
    }

    private static RawNumeralComponents.Component upperQuartetFinger(
            byte[] ink, int width, int height, float x, float y, float gap) {
        int left = Math.round(x - .80f*gap), right = Math.round(x + .80f*gap);
        int top = Math.round(y - 2.23f*gap), bottom = Math.round(y - .80f*gap);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return null;
        var candidates = RawNumeralComponents.captureLongWindow(ink, width, height, left, top, right, bottom, 165)
                .components().stream().filter(c -> c.left > left && c.right < right && c.top > top && c.bottom < bottom
                        && Math.abs(x - (c.left+c.right)*.5f) < .24f*gap
                        && c.bottom - c.top >= .87f*gap && c.bottom - c.top <= 1.35f*gap
                        && c.right - c.left >= .53f*gap && c.right - c.left <= .93f*gap).toList();
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static boolean confirmsCommonTimeRailClock(byte[] ink, int width, int height,
            List<MeasureRegion> bars, int index, List<ScoreNoteEvent> music, int[] lengths) {
        return confirmsCommonTimeRailClock(ink, width, height, bars, index, music, lengths, false);
    }

    static boolean confirmsCommonTimeRailClock(byte[] ink, int width, int height,
            List<MeasureRegion> bars, int index, List<ScoreNoteEvent> music, int[] lengths, boolean recoverShafts) {
        var bar = bars.get(index);
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25 || !ClosedDottedSixteenths.hasClosingRule(ink, width, height, bar)) return false;
        float firstX = left + span * music.get(0).positionInMeasure();
        float lastX = left + span * music.get(music.size() - 1).positionInMeasure();
        float opening = firstX - left, closing = left + span - lastX;
        if (opening < .9f * gap || opening > 3.5f * gap || closing < (recoverShafts ? .72f : .90f) * gap || closing > 2.6f * gap)
            return false;
        if (index > 0 && !WrappedMeasureBoundaries.confirmsClosed(ink, width, height, bar, firstX, lastX)) {
            var preceding = bars.get(index - 1);
            if ((bar.top() - preceding.bottom()) * height < 3 * gap
                    || !ClosedDottedSixteenths.hasClosingRule(ink, width, height, preceding))
                return false;
        }
        float floor = bar.bottom() * height - 2.1f * gap;
        var staff = StaffPitchTrack.forPrintedSlurs(ink, width, height, floor - 4 * gap, floor, gap);
        if (staff == null) return false;
        var rows = UnnumberedTripletInk.sourceRows(bar, bars, height);
        var budget = new PrintedBeamShafts.Budget(340_000);
        PrintedBeamShafts.Shaft before = null, earlier = null;
        int tracedShafts = 0;
        float beforeX = 0;
        int start = 0;
        for (int length : lengths) {
            float groupFirst = 0, headTop = Float.POSITIVE_INFINITY, headBottom = 0, tip = 0;
            for (int j = 0; j < length; j++) {
                var note = music.get(start + j);
                float x = left + span * note.positionInMeasure(), y = note.pageY() * height;
                if (before != null && (x - beforeX < 1.55f * gap || x - beforeX > 4.12f * gap))
                    return false;
                var shafts = PrintedBeamShafts.at(ink, width, height, x, y, gap, budget);
                if (shafts.size() > 1 || budget.refused()) return false;
                var at = shafts.isEmpty() ? null : shafts.get(0);
                if (at == null) {
                    if (!recoverShafts || ++tracedShafts > 2
                            || OpeningShaftTrace.scan(ink, width, height, x, y, gap, -1) != null) return false;
                    at = OpeningShaftTrace.scan(ink, width, height, x, y, gap, 1);
                }
                if (at == null || at.direction() != -note.stemDirection()) return false;
                if (shafts.isEmpty() && j == 3
                        && !WrappedEighthTriplets.matchesExactlyTwoBeamRails(ink,width,height,before,at,gap,staff)) {
                    at = trimStaffJoinedQuartetTip(earlier, before, at, gap);
                    if (at == null) return false;
                }
                if (before != null) {
                    boolean edge = j == 0
                            ? WrappedEighthTriplets.cutBetweenRecoveredGroups(ink, width, height, bar, before, at, gap)
                                    || recoverShafts && WrappedEighthTriplets.separateBorrowedTripletRails(
                                            ink, width, height, staff, before, at, gap)
                            : length == 3 && j == 1
                                    ? WrappedEighthTriplets.matchesBeamRails(ink, width, height, before, at, gap, 1)
                                            && !WrappedEighthTriplets.matchesBeamRails(ink, width, height, before, at, gap, 2)
                                    : WrappedEighthTriplets.matchesExactlyTwoBeamRails(ink, width, height, before, at, gap, staff);
                    if (!edge) return false;
                }
                if (j == 0) {
                    groupFirst = x;
                    tip = at.endY();
                } else {
                    tip = at.direction() == 1 ? Math.max(tip, at.endY()) : Math.min(tip, at.endY());
                }
                headBottom = Math.max(headBottom, y);
                headTop = Math.min(headTop, y);
                if (recoverShafts && j == 2 && music.get(start).tupletDivisor() == 3
                        && UnnumberedTripletInk.bracketBesideBeam(
                                ink, width, height, gap, groupFirst, x, headTop, headBottom, tip, rows, staff, true))
                    return false;
                earlier = before;
                before = at;
                beforeX = x;
            }
            if (music.get(start).tupletDivisor() == 3
                    && UnnumberedTripletInk.bracketBesideBeam(
                            ink, width, height, gap, groupFirst, beforeX, headTop, headBottom, tip, rows, staff, true))
                return false;
            start += length;
        }
        return true;
    }

    private static PrintedBeamShafts.Shaft trimStaffJoinedQuartetTip(PrintedBeamShafts.Shaft earlier,
            PrintedBeamShafts.Shaft before, PrintedBeamShafts.Shaft last, float gap) {
        float a = (earlier.left() + earlier.right()) * .5f;
        float b = (before.left() + before.right()) * .5f;
        float c = (last.left() + last.right()) * .5f;
        int tip = Math.round(before.endY() + (before.endY() - earlier.endY()) * (c - b) / (b - a));
        if (tip < last.startY() + gap || last.endY() - tip < .60f*gap || last.endY() - tip > 1.6f*gap)
            return null;
        return new PrintedBeamShafts.Shaft(last.left(), last.right(), last.startY(), tip, last.direction(),
                last.columns().stream().map(col -> new PrintedBeamShafts.Column(col.x(), tip)).toList());
    }

    private static boolean upperFingeringQuartets(List<Integer> ratios, List<ScoreNoteEvent> music,
            List<PlayingTechniqueDetector.Word> words, MeasureRegion bar, byte[] ink, int width, int height) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25) return false;
        for (int group = 0; group < ratios.size(); group += 3) {
            int first = ratios.get(group), last = ratios.get(group + 2);
            if (last != first + 2 || first / 4 != last / 4
                    || !retainsOppositeStemFingeringThree(
                            ink, width, height, bar, music.get(first + 1), words, gap)) return false;
        }
        return provesRegularQuartets(ink, width, height, bar, music);
    }

    private static boolean openingFingeringQuartets(byte[] ink, int width, int height,
            MeasureRegion bar, List<ScoreNoteEvent> music, List<PlayingTechniqueDetector.Word> words) {
        for (int i = 0; i < music.size(); i++) {
            var note = music.get(i);
            if (!regularQuartetHead(note) || note.stemDirection() != 1 || note.tiedFromPrevious()
                    || note.augmentationDots() != 0 || note.tupletDivisor() != (i < 3 ? 3 : 1)
                    || note.tupletNormalNotes() != (i < 3 ? 2 : 1)) return false;
        }
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        return gap >= 10 && gap <= 25
                && retainsOppositeStemFingeringThree(ink, width, height, bar, music.get(1), words, gap)
                && retainsOppositeStemFingeringThree(ink, width, height, bar, music.get(5), words, gap)
                && provesRegularQuartets(ink, width, height, bar, music);
    }

    private static boolean retainsOppositeStemFingeringThree(byte[] ink, int width, int height, MeasureRegion bar,
            ScoreNoteEvent note, List<PlayingTechniqueDetector.Word> words, float gap) {
        float x = (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
        float y = note.pageY() * height;
        boolean above = note.stemDirection() < 0;
        var matches = words.stream().filter(word -> "3".equals(word.text())
                && Math.abs((word.left() + word.right()) * width * .5f - x) < .38f * gap
                && (above ? y - word.bottom() * height > .40f * gap
                                  && y - word.top() * height < 3.30f * gap
                          : word.top() * height - y > .73f * gap
                                  && word.bottom() * height - y < 3.52f * gap)).toList();
        if (matches.size() != 1) return false;
        var word = matches.get(0);
        int l = Math.round(word.left() * width), r = Math.round(word.right() * width);
        int t = Math.round(word.top() * height), b = Math.round(word.bottom() * height);
        if (l < 0 || r >= width || t < 0 || b >= height || r-l > 2.2f * gap || b-t > 2.3f * gap)
            return false;
        return RawNumeralComponents.capture(ink, width, height, l, t, r, b, () -> {}, () -> {})
                .components().stream().filter(c -> c.left > l && c.right < r && c.top > t && c.bottom < b
                        && c.size() > .20f * gap * gap
                        && c.bottom-c.top >= .78f * gap && c.bottom-c.top <= 1.28f * gap
                        && c.right-c.left >= .44f * gap && c.right-c.left <= .94f * gap).count() == 1;
    }

    static boolean regularQuartetHead(ScoreNoteEvent n) {
        return regularBeamHead(n, 2);
    }

    static boolean regularBeamHead(ScoreNoteEvent n, int beams) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffIndex() == 0 && n.staffCount() == 1
                && n.beamCount() == beams && Math.abs(n.stemDirection()) == 1 && n.unbeamedDurationBeats() == 0
                && (n.articulations() & NoteOrnament.GRACE) == 0
                && n.boundaryTies() == 0 && n.harmonicSemitones() == 0
                && n.openStringTouch() == null && !n.crossStaffBeam() && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0;
    }

    static boolean provesRegularQuartets(byte[] ink, int width, int height,
            MeasureRegion bar, List<ScoreNoteEvent> row) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25 || !ClosedDottedSixteenths.hasClosingRule(ink, width, height, bar))
            return false;
        float floor = bar.bottom() * height - 2.1f * gap;
        var track = StaffPitchTrack.forPrintedSlurs(ink, width, height, floor - 4 * gap, floor, gap);
        if (track == null) return false;
        var budget = new PrintedBeamShafts.Budget(340_000);
        PrintedBeamShafts.Shaft previous = null;
        float previousX = 0;
        for (int i = 0; i < row.size(); i++) {
            var note = row.get(i);
            float x = (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
            if (i > 0 && (x - previousX < 1.55f * gap || x - previousX > 4.12f * gap)) return false;
            var axes = PrintedBeamShafts.at(ink, width, height, x, note.pageY() * height, gap, budget);
            if (axes.size() != 1 || axes.get(0).direction() != -note.stemDirection()) return false;
            var shaft = axes.get(0);
            if (previous != null && (previous.direction() != shaft.direction()
                    || !(i % 4 == 0
                            ? WrappedEighthTriplets.cutBetweenRecoveredGroups(ink, width, height, bar, previous, shaft, gap)
                            : WrappedEighthTriplets.matchesExactlyTwoBeamRails(ink, width, height, previous, shaft, gap, track))))
                return false;
            previous = shaft;
            previousX = x;
        }
        return !budget.refused() && bar.right() * width - previousX >= .9f * gap;
    }

    private static boolean openingStaccatoQuartets(List<ScoreNoteEvent> row) {
        for (int i = 0; i < row.size(); i++) {
            var n = row.get(i);
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffIndex() != 0 || n.staffCount() != 1
                    || n.beamCount() != 2 || n.stemDirection() != 1 || n.unbeamedDurationBeats() != 0
                    || n.tupletDivisor() != 1 || n.augmentationDots() != (i == 0 ? 1 : 0)
                    || n.articulations() != (i < 4 ? NoteArticulation.STACCATO : 0)
                    || n.tiedFromPrevious() || n.boundaryTies() != 0 || n.crossStaffBeam()
                    || n.harmonicSemitones() != 0 || n.openStringTouch() != null
                    || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL) return false;
        }
        return true;
    }

    private static boolean openingPairedStaccatos(List<ScoreNoteEvent> row) {
        for (int i = 0; i < row.size(); i++) {
            var n = row.get(i);
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffIndex() != 0 || n.staffCount() != 1
                    || n.beamCount() != (i == 4 ? 1 : 2) || n.stemDirection() != (i < 12 ? 1 : -1)
                    || n.unbeamedDurationBeats() != 0 || n.tupletDivisor() != 1 || n.tupletNormalNotes() != 1
                    || n.augmentationDots() != (i == 2 ? 1 : 0)
                    || n.articulations() != (i == 2 || i == 3 ? NoteArticulation.STACCATO : 0)
                    || n.tiedFromPrevious() || n.boundaryTies() != 0 || n.crossStaffBeam()
                    || n.harmonicSemitones() != 0 || n.openStringTouch() != null
                    || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL) return false;
        }
        return true;
    }

    private static boolean provesOpeningStaccatoQuartets(byte[] ink, int width, int height,
            MeasureRegion bar, MeasureRegion after, List<ScoreNoteEvent> notes) {
        return provesOpeningStaccatoQuartets(ink, width, height, bar, after, notes, false);
    }

    private static boolean provesOpeningStaccatoQuartets(byte[] ink, int width, int height,
            MeasureRegion bar, MeasureRegion after, List<ScoreNoteEvent> notes, boolean pairedStaccatos) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25 || bar.cadenceQuarterBeats() != 0) return false;
        float cut = (bar.right() + after.left()) * width * .5f;
        float floor = bar.bottom() * height - 2.1f * gap;
        var rules = PrintedDirectionStaff.printedRules(ink, width, height, cut, floor, gap);
        if (rules == null && pairedStaccatos)
            rules = DottedDyadMeasureSeparator.localRails(ink, width, height, Math.round(cut), floor, gap);
        if (rules == null) return false;
        var staff = new PlayingTechniqueDetector.Staff(
                rules[0] - 4 * rules[1], rules[0], rules[1], 0, 1);
        int edge = PrintedCadenceDurations.boundaryColumn(ink, width, staff, cut, true, false);
        if (edge < 0 || Math.abs(edge - cut) > gap * .3f) return false;
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        int marked = pairedStaccatos ? 2 : 0;
        float firstX = left + notes.get(marked).positionInMeasure() * span;
        float firstY = notes.get(marked).pageY() * height;
        float nextX = left + notes.get(marked + 1).positionInMeasure() * span;
        if (WrappedTripletDotInk.uniqueCore(ink, width, height, firstX, firstY, nextX, gap) != null)
            return false;
        var budget = new PrintedBeamShafts.Budget(220_000);
        PrintedBeamShafts.Shaft previous = null;
        float previousX = Float.NaN;
        int nearbyDots = 0;
        for (int i = 0; i < notes.size(); i++) {
            var n = notes.get(i);
            float x = left + n.positionInMeasure() * span, y = n.pageY() * height;
            if (i > 0 && (x - previousX < gap * 1.6f || x - previousX > gap * (pairedStaccatos ? 3.45f : 3f))) return false;
            int direction = pairedStaccatos && i >= 12 ? 1 : -1;
            var shaft = singleOpeningShaft(ink, width, height, x, y, gap, direction, budget);
            if (shaft == null && pairedStaccatos)
                shaft = openingStaccatoShaft(ink, width, height, x, y, gap, direction);
            if (shaft == null) return false;
            if (previous != null && WrappedEighthTriplets.matchesBeamRails(
                    ink, width, height, previous, shaft, gap, 2) != (i % 4 != 0)) return false;
            if (i < 4) {
                var head = new PrintedSlurOwners.GeometryHead(i, x, y, 0, 1);
                boolean dot = PrintedStaccatoSlurs.looseLowerDot(ink, width, height, head, gap);
                if ((i == marked || pairedStaccatos && i == marked + 1) && !dot) return false;
                if (dot) nearbyDots++;
            }
            previous = shaft;
            previousX = x;
        }
        return !budget.refused() && nearbyDots >= (pairedStaccatos ? 2 : 3) && edge - previousX > gap * .9f;
    }

    private static PrintedBeamShafts.Shaft singleOpeningShaft(byte[] ink, int width, int height,
            float x, float y, float gap, int direction, PrintedBeamShafts.Budget budget) {
        var axes = PrintedBeamShafts.at(ink, width, height, x, y, gap, budget);
        return axes.size() == 1 && axes.get(0).direction() == direction ? axes.get(0) : null;
    }

    static PrintedBeamShafts.Shaft openingStaccatoShaft(byte[] ink, int width, int height,
            float x, float y, float gap, int direction) {
        int a = Math.round(x - direction * gap * .40f), b = Math.round(x - direction * gap * .68f);
        int start = Math.round(y + direction * gap * .23f), max = Math.round(5.6f * gap);
        if (Math.min(a,b) < 0 || Math.max(a,b) >= width || start < 0 || start >= height
                || start + max * direction < 0 || start + max * direction >= height) return null;
        var axes = new ArrayList<PrintedBeamShafts.Column>();
        int strongest = 0;
        for (int cx = Math.min(a,b); cx <= Math.max(a,b); cx++) {
            int last = -1, hits = 0;
            for (int step = 0; step <= max; step++) {
                if ((ink[(start + step * direction) * width + cx] & 255) < 174) {
                    hits++;
                    last = step;
                }
                if (step - last > .29f * gap) break;
            }
            if (last < 2.55f * gap || last == max || hits < (last + 1) * .91f) continue;
            strongest = Math.max(strongest, last);
            axes.add(new PrintedBeamShafts.Column(cx, start + last * direction));
        }
        int end = start + strongest * direction;
        var columns = axes.stream().filter(c -> Math.abs(end - c.endY()) <= gap * .24f).toList();
        if (columns.size() < 2 || columns.get(columns.size() - 1).x() - columns.get(0).x() + 1 != columns.size()) return null;
        return new PrintedBeamShafts.Shaft(columns.get(0).x(), columns.get(columns.size() - 1).x(),
                start, end, direction, columns);
    }

    private static boolean ambiguousClockNotes(List<ScoreNoteEvent> music) {
        return music.stream().allMatch(n -> n.kind() == ScoreNoteEvent.Kind.PITCHED
                && n.staffCount() == 1 && n.staffIndex() == 0 && n.beamCount() == 2
                && n.stemDirection() == -1 && n.unbeamedDurationBeats() == 0
                && (n.tupletDivisor() == 1 && n.tupletNormalNotes() == 1
                        || n.tupletDivisor() == 3 && n.tupletNormalNotes() == 2)
                && n.augmentationDots() <= 1 && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && !n.crossStaffBeam() && n.harmonicSemitones() == 0 && n.openStringTouch() == null
                && !n.tiedFromPrevious() && n.boundaryTies() == 0
                && (n.articulations() & ~NoteArticulation.STACCATO) == 0
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0);
    }

    static boolean provesQuartetClock(byte[] gray, int width, int height,
            List<MeasureRegion> bars, int index, List<ScoreNoteEvent> row,
            List<PlayingTechniqueDetector.Word> words, int slot, boolean fingers) {
        var bar = bars.get(index);
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        var bounds = adjacentDividers(bars,index,width,height,gap);
        if (bounds == null) return false;
        float left = bar.left()*width, span = (bar.right()-bar.left())*width;
        float[] x = new float[row.size()], y = new float[row.size()];
        for (int i = 0; i < row.size(); i++) {
            var note = row.get(i);
            x[i] = left + note.positionInMeasure()*span;
            y[i] = note.pageY()*height;
            if (i > 0 && (x[i]-x[i-1] < 1.65f*gap || x[i]-x[i-1] > 3.55f*gap)) return false;
        }
        if (x[0]-bounds[0] < .9f*gap || x[0]-bounds[0] > 3.25f*gap
                || bounds[1]-x[11] < .85f*gap || bounds[1]-x[11] > 3.25f*gap) return false;
        var staff = dividerStaff(gray,width,height,bar,bounds,gap);
        if (staff == null) return false;
        var budget = new PrintedBeamShafts.Budget(180_000);
        PrintedBeamShafts.Shaft previous = null;
        for (int i = 0; i < row.size(); i++) {
            var shafts = PrintedBeamShafts.at(gray,width,height,x[i],y[i],gap,budget);
            if (shafts.size() != 1 || shafts.get(0).direction() != 1) return false;
            var shaft = shafts.get(0);
            if (previous != null) {
                boolean joined = i % 4 == 0
                        ? WrappedEighthTriplets.matchesBeamRails(gray,width,height,previous,shaft,gap,0)
                        : WrappedEighthTriplets.matchesExactlyTwoBeamRails(
                                gray,width,height,previous,shaft,gap,staff);
                if (!joined) return false;
            }
            previous = shaft;
        }
        if (budget.refused()) return false;
        if (!fingers) return WrappedTripletDotInk.uniqueCore(
                gray,width,height,x[slot],y[slot],x[slot+1],gap) == null;
        var two = lowerFingering(gray,width,height,staff,x[slot-1],"2",words);
        var three = lowerFingering(gray,width,height,staff,x[slot],"3",words);
        return two != null && three != null && Math.abs(three.top-two.top) <= .24f*gap;
    }

    private static RawNumeralComponents.Component lowerFingering(byte[] gray, int width, int height,
            StaffPitchTrack staff, float x, String digit, List<PlayingTechniqueDetector.Word> words) {
        var frame = staff.at(x);
        float floor = frame[0], gap = frame[1];
        int left=Math.round(x-.90f*gap), right=Math.round(x+.90f*gap);
        int top=Math.round(floor+.4f*gap), bottom=Math.round(floor+2.85f*gap);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return null;
        var candidates = RawNumeralComponents.capture(gray,width,height,left,top,right,bottom,
                () -> {}, () -> {}).components().stream().filter(c -> c.size() > .13f*gap*gap).toList();
        if (candidates.size() != 1) return null;
        var c = candidates.get(0);
        if (c.left <= left || c.right >= right || c.top <= top || c.bottom >= bottom
                || c.bottom-c.top < .75f*gap || c.bottom-c.top > 1.35f*gap
                || Math.abs((c.left+c.right)*.5f-x) > .43f*gap) return null;
        var matches = words.stream()
                .filter(word -> digit.equals(word.text().strip())
                        && c.left >= word.left()*width-2 && c.right <= word.right()*width+2
                        && c.top >= word.top()*height-2 && c.bottom <= word.bottom()*height+2
                        && Math.abs((word.left()+word.right())*width*.5f-x) <= .50f*gap)
                .toList();
        return matches.size() == 1 ? c : null;
    }

    static float[] adjacentDividers(List<MeasureRegion> bars, int index, int width, int height, float gap) {
        var before = bars.get(index-1);
        var bar = bars.get(index);
        var after = bars.get(index+1);
        float a=(bar.left()-before.right())*width, b=(after.left()-bar.right())*width;
        if (gap < 10 || gap > 25 || a < .3f*gap || a > 1.5f*gap || b < .3f*gap || b > 1.5f*gap
                || Math.abs(before.top()-bar.top())*height > .35f*gap
                || Math.abs(after.top()-bar.top())*height > .35f*gap) return null;
        return new float[]{(before.right()+bar.left())*width*.5f,(bar.right()+after.left())*width*.5f};
    }

    static StaffPitchTrack dividerStaff(byte[] gray, int width, int height,
            MeasureRegion bar, float[] edges, float gap) {
        float floor = bar.bottom()*height - 2.1f*gap;
        var samples = new ArrayList<float[]>();
        for (float fraction : new float[] {0,.08f,.29f,.50f,.71f,.92f,1}) {
            float x = edges[0]+(edges[1]-edges[0])*fraction;
            var local = PrintedDirectionStaff.printedRules(gray,width,height,x,floor,gap);
            if (local == null || Math.abs(local[1]-gap) > .10f*gap) continue;
            samples.add(new float[]{x,local[0],local[1]});
        }
        var staff = samples.size() >= 2 && samples.get(0)[0] <= edges[0]+4*gap
                && samples.get(samples.size() - 1)[0] >= edges[1]-4*gap
                ? StaffPitchTrack.fromVerifiedSamples(samples)
                : StaffPitchTrack.forPrintedSlurs(gray,width,height,floor-4*gap,floor,gap);
        if (staff == null) return null;
        for (float edge : edges) {
            var sample = staff.at(edge);
            if (Math.abs(sample[0]-floor) > .34f*gap || Math.abs(sample[1]-gap) > .10f*gap)
                return null;
            var frame = new PlayingTechniqueDetector.Staff(
                    sample[0]-4*sample[1],sample[0],sample[1],0,1);
            int column=PrintedCadenceDurations.boundaryColumn(gray,width,frame,edge,true,false);
            if (column<0 || Math.abs(column-edge)>.27f*gap) return null;
        }
        return staff;
    }

    static ScoreNoteEvent withoutAugmentationDot(ScoreNoteEvent n) {
        return new ScoreNoteEvent(n.measureIndex(),n.positionInMeasure(),n.staffStep(),n.staffIndex(),
                n.staffCount(),n.pageY(),n.tiedFromPrevious(),0,n.beamCount(),n.writtenAccidental(),
                n.unbeamedDurationBeats(),n.tupletDivisor(),n.followingRestBeats(),n.articulations(),
                n.clefBottomDiatonic(),n.crossStaffBeam(),n.leadingRestBeats(),n.compactOpening(),
                n.octaveShift(),n.boundaryTies(),n.tupletNormalNotes(),n.stemDirection(),n.kind(),
                n.visualSize(),n.harmonicSemitones(),n.openStringTouch());
    }

    private static boolean closedThreeGroups(
            byte[] gray, int width, int height, MeasureRegion bar, List<ScoreNoteEvent> row) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25) return false;
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float first = left + row.get(0).positionInMeasure() * span;
        float last = left + row.get(row.size() - 1).positionInMeasure() * span;
        if (!WrappedMeasureBoundaries.confirmsClosedTripletBar(gray, width, height, bar, first, last))
            return false;
        var budget = new PrintedBeamShafts.Budget(180_000);
        PrintedBeamShafts.Shaft previous = null;
        float previousX = left;
        for (int i = 0; i < row.size(); i++) {
            var n = row.get(i);
            float x = left + n.positionInMeasure() * span, y = n.pageY() * height;
            if (x - previousX < gap * (i == 0 ? .9f : 1.65f) || x - previousX > gap * 3.55f) return false;
            var stems = PrintedBeamShafts.at(gray, width, height, x, y, gap, budget);
            if (stems.size() != 1 || stems.get(0).direction() != 1) return false;
            var shaft = stems.get(0);
            if (previous != null) {
                var joined = WrappedEighthTriplets.matchesBeamRails(gray, width, height, previous, shaft, gap, 2);
                if (joined != (i % 4 != 0)) return false;
            }
            previous = shaft;
            previousX = x;
        }
        return !budget.refused();
    }
}
