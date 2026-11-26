// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Restores closed common-time dotted-sixteenth groups with proved beam fragments. */
final class ClosedDottedHookCadence {
    private ClosedDottedHookCadence() {}

    static ScorePageInterpretation apply(ScorePageInterpretation score, byte[] normalized, byte[] raw,
            int width, int height) {
        return apply(score, normalized, raw, width, height, -1);
    }

    static ScorePageInterpretation apply(ScorePageInterpretation score, byte[] normalized, byte[] raw,
            int width, int height, int physicalPage) {
        if (raw == null || normalized == null || raw.length != (long) width * height
                || raw.length != normalized.length) return score;
        var notes = score.notes();
        var regions = score.measures();
        var byBar = new ArrayList<List<Integer>>(regions.size());
        for (int i = 0; i < regions.size(); i++) byBar.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) byBar.get(notes.get(i).measureIndex()).add(i);
        List<RawStaffLineDetector.StaffLines> rawStaffs = null, normalizedStaffs = null;
        int next = 0;
        ScoreMeterChange meter = null;
        var suppressed = new java.util.BitSet();
        var beams = score.beamSource();
        var replacements = new ScoreNoteEvent[notes.size()];
        boolean repaired = false;
        for (int m = 1; m + 1 < byBar.size(); m++) {
            while (next < score.meterChanges().size() && score.meterChanges().get(next).measureIndex() <= m)
                meter = score.meterChanges().get(next++);
            final int barIndex = m;
            var row = byBar.get(m);
            if (meter == null || meter.denominator() != 4
                    || score.rests().stream().anyMatch(rest -> rest.measureIndex() == barIndex)) continue;
            boolean chromatic = meter.numerator() == 3 && row.size() == 13;
            boolean commonTime = meter.numerator() == 4
                    && (row.size() == 10 || row.size() == 11 || row.size() == 12 || row.size() == 16 || row.size() == 18 || row.size() == 19 || row.size() == 20);
            if (!chromatic && !commonTime) continue;
            var bar = regions.get(m);
            var owners = row.stream().filter(i -> notes.get(i).stemDirection() == -1)
                    .sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure())).toList();
            var guests = row.stream().filter(i -> notes.get(i).stemDirection() != -1).toList();
            if (commonTime && owners.size() == 14 && (guests.size() == 2 || guests.size() == 4)
                    && ClosedDottedSixteenths.provesEighthAndDottedGroups(
                            notes, owners, guests, bar, raw, normalized, width, height)) {
                var projected = projectRun(beams, owners, guests, physicalPage, true);
                if (projected == beams) continue;
                beams = projected;
                for (int i = 0; i < owners.size(); i++) {
                    int owner = owners.get(i), slot = i % 7;
                    replacements[owner] = rhythm(notes.get(owner), slot == 0 ? 1 : slot % 2 == 1 ? 2 : 3,
                            slot % 2);
                }
                repaired = true;
                for (int guest : guests) suppressed.set(guest);
                continue;
            }
            boolean ribbonCadence = owners.size() == 9 && guests.size() == 2
                    && ClosedDottedSixteenths.provesDottedPairsWithBeamAliases(
                            notes, row, bar, raw, normalized, width, height);
            boolean cadence = ribbonCadence
                    || owners.size() == 9 && guests.size() == 1 && shaped(notes, owners, guests.get(0));
            boolean fullRun = owners.size() == 16 && guests.size() == 4 && shapedRun(notes, owners, guests);
            boolean lowerRibbon = owners.size() == 16 && guests.size() == 3
                    && shapedLowerRibbon(notes, owners, guests);
            boolean staffOpening = owners.size() == 16 && guests.size() == 2 && shapedStaffOpening(notes, owners, guests);
            boolean tiedQuarter = owners.size() == 12 && shapedTiedQuarter(notes, owners);
            chromatic = chromatic && owners.size() == 12 && guests.size() == 1
                    && shapedChromatic(notes, owners, guests.get(0));
            if (!cadence && !fullRun && !lowerRibbon && !staffOpening && !tiedQuarter && !chromatic) continue;
            if (rawStaffs == null) {
                rawStaffs = RawStaffLineDetector.detect(raw, width, height);
                normalizedStaffs = RawStaffLineDetector.detect(normalized, width, height);
            }
            var heads = owners.stream().map(i -> head(notes.get(i), bar, width, height)).toList();
            var fragments = guests.stream().map(i -> head(notes.get(i), bar, width, height)).toList();
            if (staffOpening) {
                if (!provesStaffOpening(normalized, width, height, regions, m, heads, fragments, normalizedStaffs)
                        || !provesStaffOpening(raw, width, height, regions, m, heads, fragments, rawStaffs)) continue;
                for (int i = 0; i < owners.size(); i++) {
                    int owner = owners.get(i);
                    var note = notes.get(owner);
                    int depth = (i & 1) == 0 ? 2 : 3, dots = (i & 1) == 0 ? 1 : 0;
                    if (note.beamCount() != depth || note.augmentationDots() != dots)
                        replacements[owner] = rhythm(note, depth, dots);
                }
                beams = projectRun(beams, owners, guests, physicalPage, false);
                repaired = true;
                for (int guest : guests) suppressed.set(guest);
                continue;
            }
            if (chromatic) {
                if (!provesChromatic(normalized, width, height, regions, m, heads,
                            fragments.get(0), normalizedStaffs)
                        || !provesChromatic(raw, width, height, regions, m, heads,
                            fragments.get(0), rawStaffs)) continue;
                for (int i = 0; i < owners.size(); i++) {
                    int owner = owners.get(i);
                    var note = notes.get(owner);
                    if ((i & 1) == 0 && note.augmentationDots() == 0)
                        replacements[owner] = rhythm(note, 2, 1);
                    else if ((i & 1) == 1 && note.beamCount() == 2)
                        replacements[owner] = withTertiary(note);
                }
                repaired = true;
                suppressed.set(guests.get(0));
                continue;
            }
            if (tiedQuarter) {
                if (!provesTiedQuarter(normalized, width, height, regions, m, heads, normalizedStaffs)
                        || !provesTiedQuarter(raw, width, height, regions, m, heads, rawStaffs)) continue;
                for (int i = 1; i < 10; i += 2) {
                    int owner = owners.get(i);
                    if (notes.get(owner).beamCount() == 2) replacements[owner] = withTertiary(notes.get(owner));
                }
                repaired = true;
                continue;
            }
            if (cadence && !ribbonCadence) {
                boolean atShoulder = notes.get(guests.get(0)).beamCount() == 2;
                if (!proves(normalized, width, height, regions, m, heads, fragments.get(0),
                            normalizedStaffs, atShoulder)
                        || !proves(raw, width, height, regions, m, heads, fragments.get(0),
                            rawStaffs, atShoulder)) continue;
            } else if (!cadence && (!provesRun(normalized, width, height, regions, m, heads, fragments,
                            normalizedStaffs, lowerRibbon)
                    || !provesRun(raw, width, height, regions, m, heads, fragments,
                            rawStaffs, lowerRibbon))) continue;
            beams = projectRun(beams, cadence ? owners.subList(0, 8) : owners,
                    guests, lowerRibbon || ribbonCadence ? physicalPage : -1, false);
            if (lowerRibbon) {
                for (int i = 0; i < owners.size(); i += 2) {
                    int owner = owners.get(i);
                    var note = notes.get(owner);
                    if (note.beamCount() == 1) replacements[owner] = rhythm(note, 2, 1);
                }
            }
            repaired = true;
            for (int guest : guests) suppressed.set(guest);
            for (int i = 1; i < (cadence ? 8 : 16); i += 2) {
                int owner = owners.get(i);
                replacements[owner] = withTertiary(notes.get(owner));
            }
        }
        if (!repaired) return score;
        var retained = new ArrayList<ScoreNoteEvent>();
        var indices = new ArrayList<Integer>();
        for (int i = 0; i < notes.size(); i++) {
            if (suppressed.get(i)) continue;
            retained.add(replacements[i] == null ? notes.get(i) : replacements[i]);
            indices.add(i);
        }
        return score.withBeamSource(beams).withNotes(retained, indices);
    }

    static ScoreBeamSource projectRun(ScoreBeamSource source, List<Integer> owners,
            List<Integer> fragments, int physicalPage, boolean eighthEntries) {
        var origins = new ArrayList<>(source.noteOrigins());
        if (physicalPage >= 0 && owners.stream().anyMatch(i -> origins.get(i).isEmpty())) {
            var unavailable = new HashSet<ScoreBeamSource.Anchor>();
            for (var origin : origins) origin.ifPresent(unavailable::add);
            for (var group : source.groups())
                for (var column : group.columns()) unavailable.addAll(column.members());
            for (var column : source.detachedColumns()) unavailable.addAll(column.members());
            for (int owner : owners) {
                if (origins.get(owner).isPresent()) continue;
                var anchor = new ScoreBeamSource.Anchor(physicalPage, owner);
                if (!unavailable.add(anchor)) return source;
                origins.set(owner, java.util.Optional.of(anchor));
            }
        }
        var anchors = owners.stream().map(i -> origins.get(i).orElse(null)).toList();
        if (anchors.contains(null)) return source;
        int page = anchors.get(0).pageIndex();
        var local = new HashSet<>(anchors);
        if (physicalPage >= 0 && page != physicalPage || local.size() != owners.size() || anchors.stream().anyMatch(a -> a.pageIndex() != page)) return source;
        for (int i : fragments) source.noteOrigins().get(i).ifPresent(local::add);

        var unmatched = new HashSet<>(local);
        for (var origin : origins)
            if (origin.isPresent() && local.contains(origin.get()) && !unmatched.remove(origin.get())) return source;
        var groups = new ArrayList<ScoreBeamSource.Group>();
        var ids = new HashSet<String>();
        for (var group : source.groups()) {
            var members = group.columns().stream().flatMap(c -> c.members().stream()).toList();
            if (members.stream().anyMatch(local::contains)) {
                if (!local.containsAll(members) || group.tupletMark() != null) return source;
            } else {
                groups.add(group);
                ids.add(group.id());
            }
        }
        var detached = new ArrayList<ScoreBeamSource.Column>();
        for (var column : source.detachedColumns()) {
            if (column.members().stream().noneMatch(local::contains)) detached.add(column);
            else if (!local.containsAll(column.members())) return source;
        }
        var hooks = List.of(new ScoreBeamSource.Hook(1, 3, ScoreBeamSource.Direction.BACKWARD),
                new ScoreBeamSource.Hook(3, 3, ScoreBeamSource.Direction.BACKWARD));
        for (int first = 0; first < anchors.size();) {
            boolean eighth = eighthEntries && first % 7 == 0;
            int length = eighth ? 3 : 4;
            var columns = anchors.subList(first, first + length).stream()
                    .map(a -> new ScoreBeamSource.Column(List.of(a))).toList();
            String id = "printed-beam:" + page + ":" + anchors.get(first).noteIndex();
            if (!ids.add(id)) return source;
            groups.add(new ScoreBeamSource.Group(id, columns, eighth ? List.of(1, 3) : List.of(3, 3, 3),
                    eighth ? List.of(new ScoreBeamSource.Hook(2, 3, ScoreBeamSource.Direction.BACKWARD)) : hooks));
            first += length;
        }
        return new ScoreBeamSource(groups, detached, origins);
    }

    private static boolean shaped(List<ScoreNoteEvent> notes, List<Integer> owners, int guestIndex) {
        var guest = notes.get(guestIndex);
        if (!plain(guest) || guest.stemDirection() != 0
                || (guest.beamCount() != 1 && guest.beamCount() != 2)
                || guest.augmentationDots() != 0 || guest.unbeamedDurationBeats() != 0
                || guest.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY || guest.articulations() != 0)
            return false;
        var half = notes.get(owners.get(owners.size() - 1));
        if (!plain(half) || half.beamCount() != 0 || half.unbeamedDurationBeats() != 2
                || half.augmentationDots() != 0) return false;
        for (int i = 0; i < 8; i++) {
            var n = notes.get(owners.get(i));
            int dots = i % 2 == 0 ? 1 : 0;
            if (!plain(n) || n.clefBottomDiatonic() != half.clefBottomDiatonic()
                    || n.unbeamedDurationBeats() != 0 || n.augmentationDots() != dots
                    || n.beamCount() < 2 || n.beamCount() > (dots == 1 ? 2 : 3)) return false;
        }
        return true;
    }

    private static boolean plain(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffIndex() == 0 && n.staffCount() == 1
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL && !n.crossStaffBeam()
                && !n.tiedFromPrevious() && n.boundaryTies() == 0 && n.tupletDivisor() == 1
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0
                && n.harmonicSemitones() == 0 && n.openStringTouch() == null
                && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static boolean shapedStaffOpening(List<ScoreNoteEvent> notes, List<Integer> owners, List<Integer> guests) {
        int clef = notes.get(owners.get(0)).clefBottomDiatonic(), doubleDot = 0, weakRail = 0;
        for (int i = 0; i < owners.size(); i++) {
            var note = notes.get(owners.get(i));
            boolean dotted = (i & 1) == 0;
            if (!plain(note) || note.clefBottomDiatonic() != clef || note.unbeamedDurationBeats() != 0)
                return false;
            if (dotted) {
                if (note.augmentationDots() == 2) doubleDot++;
                else if (note.augmentationDots() != 1) return false;
                if (note.beamCount() == 1) weakRail++;
                else if (note.beamCount() != 2) return false;
            } else if (note.augmentationDots() != 0 || note.beamCount() < 2 || note.beamCount() > 3) {
                return false;
            }
        }
        if (doubleDot != 1 || weakRail != 1) return false;
        for (int i : guests) {
            var note = notes.get(i);
            if (!plain(note) || note.augmentationDots() != 0 || note.articulations() != 0
                    || note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) return false;
        }
        var a = notes.get(guests.get(0));
        var b = notes.get(guests.get(guests.size() - 1));
        return a.stemDirection() == 0 && a.beamCount() == 3 && a.unbeamedDurationBeats() == 0
                && b.stemDirection() == 1 && b.beamCount() == 0 && b.unbeamedDurationBeats() == 1;
    }

    private static boolean provesStaffOpening(byte[] gray, int width, int height, List<MeasureRegion> regions,
            int index, List<Head> heads, List<Head> fragments, List<RawStaffLineDetector.StaffLines> staffs) {
        var bar = regions.get(index);
        var next = regions.get(index + 1);
        var staff = staffs.stream().min(Comparator.comparingDouble(s ->
                Math.abs((bar.top() + bar.bottom()) * height * .5f - s.center()))).orElse(null);
        if (staff == null) return false;
        float gap = staff.gap(), left = bar.left() * width, right = bar.right() * width;
        float pocket = next.left() * width - right;
        if (bar.cadenceQuarterBeats() != 0 || gap < 10 || gap > 24 || right - left < 31 * gap
                || right - left > 41 * gap || heads.get(0).x - left > gap * 2.55f
                || (bar.top() - regions.get(index - 1).bottom()) * height < gap * 2.8f
                || Math.abs(next.top() - bar.top()) * height > gap * .45f
                || pocket < gap * .55f || pocket > gap * 1.5f
                || right - heads.get(heads.size() - 1).x > gap * 1.55f) return false;
        var rules = new PlayingTechniqueDetector.Staff(staff.top(), staff.bottom(), gap, 0, 1);
        if (PrintedCadenceDurations.boundaryColumn(gray, width, rules, right + pocket * .5f, true, false) < 0)
            return false;
        var budget = new PrintedBeamShafts.Budget(520_000);
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>();
        int covered = 0, reverse = 0;
        for (int i = 0; i < heads.size(); i++) {
            var head = heads.get(i);
            var read = PrintedBeamShafts.at(gray, width, height, head.x, head.y, gap, budget);
            if (head.y > staff.top() - gap * .9f || read.size() > 1 || budget.refused()) return false;
            var shaft = read.isEmpty() ? staffCoveredShaft(gray, width, height, head, staff) : read.get(0);
            if (shaft == null || shaft.direction() != 1) return false;
            if (i > 0) {
                var prior = heads.get(i - 1);
                var preceding = shafts.get(shafts.size() - 1);
                float spacing = head.x - prior.x;
                if (spacing < gap * 1.62f || spacing > gap * 3.6f) return false;
                if (i % 4 == 0) {
                    if (!WrappedEighthTriplets.matchesBeamRails(gray, width, height, preceding, shaft, gap, 0)
                            && !staffMaskedBeamBreak(gray, width, height, preceding, shaft, staff)) return false;
                } else if (!WrappedEighthTriplets.matchesBeamRails(gray, width, height, preceding, shaft, gap, 2)) {
                    return false;
                }
                if ((i & 1) == 1) {
                    if (!LegacyTripletDotInk.singleDotCore(gray, width, height, prior.x, prior.y,
                                    Math.min(head.x, prior.x + gap * 2.52f), gap)
                            && WrappedTripletDotInk.uniqueCore(gray, width, height, prior.x, prior.y, head.x, gap) == null)
                        return false;
                    var low = fragments.get(fragments.size() - 1);
                    boolean shortHook = staffRuleOwnsHook(gray, width, height, low, prior, head, shaft, staff, budget);
                    if (!shortHook && !OwnedTertiaryBeamlets.provesBackwardHookForShafts(
                            gray, width, height, shaft, preceding, gap)) return false;
                    if (shortHook) reverse++;
                    if (coveredBetweenShafts(gray, width, height, fragments.get(0), prior, head,
                            preceding, shaft, gap, budget)) covered++;
                }
            }
            shafts.add(shaft);
        }
        return covered == 1 && reverse == 1 && !budget.refused();
    }

    private static PrintedBeamShafts.Shaft staffCoveredShaft(byte[] gray, int width, int height,
            Head note, RawStaffLineDetector.StaffLines staff) {
        float gap = staff.gap();
        int x = Math.round(note.x - gap * .6f), radius = Math.round(gap * .2f);
        int start = Math.round(note.y + gap * .2f);
        int minimum = Math.round(staff.bottom() - gap * 1.6f);
        int limit = Math.round(staff.bottom() - gap * .55f);
        if (x - radius < 1 || x + radius >= width - 1 || start < 1 || limit >= height || minimum <= start)
            return null;
        var support = new ArrayList<PrintedBeamShafts.Column>();
        for (int col = x - radius; col <= x + radius; col++) {
            int clear = 0, missed = 0, last = -1;
            for (int y = start; y <= limit; y++) {
                if ((gray[y * width + col] & 255) >= 155) {
                    missed++;
                    if (++clear > (y > minimum ? 1 : gap * .24f)) break;
                } else {
                    clear = 0;
                    last = y;
                }
            }
            if (last >= minimum && missed - clear <= gap * .30f)
                support.add(new PrintedBeamShafts.Column(col, last));
        }
        if (support.size() < 2 || support.size() > 3) return null;
        var first = support.get(0);
        var last = support.get(support.size() - 1);
        if (last.x() - first.x() + 1 != support.size() || Math.abs(last.endY() - first.endY()) > 2) return null;
        return new PrintedBeamShafts.Shaft(first.x(), last.x(), start, Math.max(first.endY(), last.endY()), 1, support);
    }

    private static boolean staffMaskedBeamBreak(byte[] gray, int width, int height,
            PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, RawStaffLineDetector.StaffLines staff) {
        float gap = staff.gap();
        int x0 = a.right() + Math.round(gap * .5f), x1 = b.left() - Math.round(gap * .5f);
        int y0 = Math.min(a.endY(), b.endY()) - Math.round(gap * 1.38f);
        int y1 = Math.max(a.endY(), b.endY()) + Math.round(gap * .24f);
        if (x0 < 0 || x1 >= width || y0 < 0 || y1 >= height || x1 - x0 < gap * .47f) return false;
        int longest = 0, run = 0;
        int tolerance = Math.round(gap * .17f);
        for (int x = x0; x <= x1; x++) {
            int offRule = 0;
            for (int y = y0; y <= y1; y++) {
                if ((gray[y * width + x] & 255) >= 155) continue;
                boolean masked = false;
                for (int rule : staff.rows()) if (Math.abs(y - rule) <= tolerance) masked = true;
                if (!masked) offRule++;
            }
            run = offRule <= gap * .12f ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest >= Math.round(gap * .58f);
    }

    private static boolean coveredBetweenShafts(byte[] gray, int width, int height, Head alias,
            Head before, Head after, PrintedBeamShafts.Shaft from, PrintedBeamShafts.Shaft to,
            float gap, PrintedBeamShafts.Budget budget) {
        if (alias.x - before.x < gap * .55f || after.x - alias.x < gap * 1.1f
                || alias.y - Math.max(before.y, after.y) < gap * 3.5f
                || !PrintedBeamShafts.at(gray, width, height, alias.x, alias.y, gap, budget).isEmpty()) return false;
        int x = Math.round(alias.x), row = Math.round(alias.y), radius = Math.round(gap * .65f);
        int half = Math.round(gap * .4f);
        if (x - radius <= from.right() || x + radius >= to.left()
                || row - half < 0 || row + half >= height) return false;
        for (int col = x - radius; col <= x + radius; col++)
            if (columnInk(gray, width, col, row, half) < (2 * half + 1) * .86f) return false;
        return true;
    }

    private static boolean staffRuleOwnsHook(byte[] gray, int width, int height, Head alias,
            Head before, Head owner, PrintedBeamShafts.Shaft shaft,
            RawStaffLineDetector.StaffLines staff, PrintedBeamShafts.Budget budget) {
        float gap = staff.gap();
        int axis = (shaft.left() + shaft.right()) / 2;
        if (alias.x - before.x < gap || owner.x - alias.x < gap * .85f
                || axis - alias.x < gap * .28f || axis - alias.x > gap * .58f
                || alias.y - owner.y < gap * 3.4f || alias.y - owner.y > gap * 5.2f
                || shaft.endY() - alias.y < gap * 1.65f || shaft.endY() - alias.y > gap * 2.35f
                || Math.abs(alias.y - staff.rows()[1]) > .18f * gap) return false;
        var read = PrintedBeamShafts.at(gray, width, height, alias.x, alias.y, gap, budget);
        if (read.size() != 1 || read.get(0).direction() != -1 || budget.refused()) return false;
        var reverse = read.get(0);
        if (Math.max(reverse.left(), shaft.left()) > Math.min(reverse.right(), shaft.right())
                || Math.abs(reverse.endY() - owner.y) > gap * .23f) return false;
        int col = Math.round(alias.x), row = Math.round(alias.y);
        int left = col - Math.round(gap * .7f), right = axis + Math.round(gap * .4f);
        if (left < 0 || right >= width || row < 7 || row >= height - 7) return false;
        int strip = Math.round(gap * .26f);
        for (int x = col; x < axis; x++)
            if (columnInk(gray, width, x, row, strip) < strip * 2) return false;
        return columnInk(gray, width, left, row, strip) <= strip
                && columnInk(gray, width, right, row, strip) <= strip;
    }

    private static boolean shapedChromatic(List<ScoreNoteEvent> notes, List<Integer> owners, int guestIndex) {
        var guest = notes.get(guestIndex);
        if (!plain(guest) || guest.stemDirection() != 0 || guest.beamCount() != 3
                || guest.augmentationDots() != 0 || guest.unbeamedDurationBeats() != 0
                || guest.articulations() != 0
                || guest.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) return false;
        var previous = notes.get(owners.get(0));
        int clef = previous.clefBottomDiatonic();
        int omittedDots = 0, omittedHooks = 0;
        for (int i = 0; i < owners.size(); i++) {
            var note = notes.get(owners.get(i));
            boolean dotted = (i & 1) == 0;
            if (!plain(note) || note.clefBottomDiatonic() != clef || note.unbeamedDurationBeats() != 0
                    || note.staffStep() > previous.staffStep() || previous.staffStep() - note.staffStep() > 1
                    || note.beamCount() != 2 && (dotted || note.beamCount() != 3)
                    || note.augmentationDots() > (dotted ? 1 : 0)) return false;
            if (dotted && note.augmentationDots() == 0) omittedDots++;
            if (!dotted && note.beamCount() == 2) omittedHooks++;
            previous = note;
        }
        return omittedDots == 1 && omittedHooks > 0;
    }

    private static boolean provesChromatic(byte[] gray, int width, int height, List<MeasureRegion> regions,
            int index, List<Head> heads, Head fragment, List<RawStaffLineDetector.StaffLines> staffs) {
        var bar = regions.get(index);
        var staff = staffs.stream().min(Comparator.comparingDouble(s ->
                Math.abs((bar.top() + bar.bottom()) * height * .5f - s.center()))).orElse(null);
        if (staff == null) return false;
        float gap = staff.gap(), left = bar.left() * width, right = bar.right() * width;
        float leftPocket = left - regions.get(index - 1).right() * width;
        float rightPocket = regions.get(index + 1).left() * width - right;
        if (bar.cadenceQuarterBeats() != 0 || gap < 10 || gap > 24 || right - left < 27 * gap
                || right - left > 36 * gap || leftPocket < gap * .6f || leftPocket > gap * 1.55f
                || rightPocket < gap * .6f || rightPocket > gap * 1.55f) return false;
        var rules = new PlayingTechniqueDetector.Staff(staff.top(), staff.bottom(), gap, 0, 1);
        if (PrintedCadenceDurations.boundaryColumn(gray, width, rules, left - leftPocket * .5f, true, false) < 0
                || PrintedCadenceDurations.boundaryColumn(gray, width, rules, right + rightPocket * .5f, true, false) < 0)
            return false;
        var budget = new PrintedBeamShafts.Budget(440_000);
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(12);
        int adjacentRuleShafts = 0;
        int fragmentOwners = 0;
        for (int i = 0; i < heads.size(); i++) {
            var head = heads.get(i);
            var own = PrintedBeamShafts.at(gray, width, height, head.x, head.y, gap, budget);
            if (budget.refused() || own.size() > 1) return false;
            var shaft = own.isEmpty() ? adjacentRuleShaft(gray, width, height, head, staff) : own.get(0);
            if (shaft == null || shaft.direction() != 1 || own.isEmpty() && ++adjacentRuleShafts > 1) return false;
            if (i > 0) {
                var prior = heads.get(i - 1);
                var preceding = shafts.get(shafts.size() - 1);
                float step = head.x - prior.x;
                if (step < gap * 1.65f || step > gap * 4.1f
                        || !WrappedEighthTriplets.matchesBeamRails(gray, width, height, preceding, shaft,
                                gap, i % 4 == 0 ? 0 : 2)) return false;
                if ((i & 1) == 1) {
                    float dotEnd = Math.min(head.x, prior.x + gap * 2.52f);
                    if (!LegacyTripletDotInk.singleDotCore(gray, width, height, prior.x, prior.y, dotEnd, gap)
                            && WrappedTripletDotInk.uniqueCore(gray, width, height, prior.x, prior.y, head.x, gap) == null)
                        return false;
                    if (!OwnedTertiaryBeamlets.provesBackwardHookForShafts(
                            gray, width, height, shaft, preceding, gap)) return false;
                    if (ownedByTwoRails(gray, width, height, fragment, preceding, shaft, gap, .25f, .73f, budget)) fragmentOwners++;
                }
            }
            shafts.add(shaft);
        }
        return fragmentOwners == 1 && !budget.refused();
    }

    private static PrintedBeamShafts.Shaft adjacentRuleShaft(byte[] gray, int width, int height,
            Head note, RawStaffLineDetector.StaffLines staff) {
        float gap = staff.gap();
        int edge = Math.round(note.x - .6f * gap), radius = Math.round(gap * .21f);
        int start = Math.round(note.y + .2f * gap);
        int limit = Math.min(height - 2, start + Math.round(gap * 6.4f));
        if (edge - radius < 1 || edge + radius >= width - 1 || start < 1 || start >= limit) return null;
        var columns = new ArrayList<PrintedBeamShafts.Column>();
        int last = 0;
        for (int x = edge - radius; x <= edge + radius; x++) {
            int y = start;
            while (y < limit && (gray[y * width + x] & 255) < 155) y++;
            if (y - start < gap * 3.5f || y == limit) continue;
            int end = y - 1;
            if (Math.abs(end - staff.bottom()) > gap * 1.4f
                    || columns.size() > 0 && (x != last + 1 || Math.abs(end - columns.get(0).endY()) > 1))
                return null;
            columns.add(new PrintedBeamShafts.Column(x, end));
            last = x;
        }
        if (columns.size() < 2 || columns.size() > 3) return null;
        var first = columns.get(0);
        var end = columns.get(columns.size() - 1);
        return new PrintedBeamShafts.Shaft(first.x(), end.x(), start, Math.max(first.endY(), end.endY()),
                1, columns);
    }

    private static boolean shapedTiedQuarter(List<ScoreNoteEvent> notes, List<Integer> owners) {
        var eighth = notes.get(owners.get(10));
        var quarter = notes.get(owners.get(owners.size() - 1));
        if (!plain(eighth) || eighth.beamCount() != 1 || eighth.augmentationDots() != 0
                || eighth.unbeamedDurationBeats() != 0
                || !quarter.tiedFromPrevious() || quarter.boundaryTies() != 0
                || quarter.beamCount() != 0 || quarter.augmentationDots() != 0
                || quarter.unbeamedDurationBeats() != 1 || quarter.tupletDivisor() != 1
                || quarter.leadingRestBeats() != 0 || quarter.followingRestBeats() != 0
                || quarter.kind() != ScoreNoteEvent.Kind.PITCHED
                || quarter.staffIndex() != 0 || quarter.staffCount() != 1
                || quarter.visualSize() != ScoreNoteEvent.VisualSize.FULL || quarter.crossStaffBeam()
                || quarter.harmonicSemitones() != 0 || quarter.openStringTouch() != null
                || (quarter.articulations() & NoteOrnament.GRACE) != 0
                || eighth.staffStep() != quarter.staffStep() || eighth.octaveShift() != quarter.octaveShift()
                || eighth.clefBottomDiatonic() != quarter.clefBottomDiatonic()) return false;
        int missing = 0;
        for (int i = 0; i < 10; i++) {
            var n = notes.get(owners.get(i));
            boolean shortNote = (i & 1) == 1;
            if (!plain(n) || n.clefBottomDiatonic() != eighth.clefBottomDiatonic()
                    || n.unbeamedDurationBeats() != 0 || n.augmentationDots() != (shortNote ? 0 : 1)
                    || n.beamCount() != 2 && (!shortNote || n.beamCount() != 3)) return false;
            if (shortNote && n.beamCount() == 2) missing++;
        }
        return missing == 1;
    }

    private static boolean provesTiedQuarter(byte[] gray, int width, int height, List<MeasureRegion> regions,
            int index, List<Head> heads, List<RawStaffLineDetector.StaffLines> staffs) {
        var bar = regions.get(index);
        var staff = staffs.stream().min(Comparator.comparingDouble(s ->
                Math.abs((bar.top() + bar.bottom()) * height * .5f - s.center()))).orElse(null);
        if (staff == null) return false;
        float gap = staff.gap(), left = bar.left() * width, right = bar.right() * width;
        float pocket = left - regions.get(index - 1).right() * width;
        if (bar.cadenceQuarterBeats() != 0 || gap < 10 || gap > 24 || right - left < 27 * gap
                || right - left > 36 * gap || pocket < gap * .55f || pocket > gap * 1.5f
                || right - heads.get(heads.size() - 1).x < gap * 2 || right - heads.get(heads.size() - 1).x > gap * 3.5f) return false;
        var rules = new PlayingTechniqueDetector.Staff(staff.top(), staff.bottom(), gap, 0, 1);
        if (PrintedCadenceDurations.boundaryColumn(gray, width, rules, left - pocket * .5f, true, false) < 0
                || PrintedCadenceDurations.boundaryColumn(gray, width, rules, right, true, false) < 0) return false;
        var budget = new PrintedBeamShafts.Budget(220_000);
        PrintedBeamShafts.Shaft previous = null;
        for (int i = 0; i < heads.size(); i++) {
            var head = heads.get(i);
            var shafts = PrintedBeamShafts.at(gray, width, height, head.x, head.y, gap, budget);
            if (shafts.size() != 1 || shafts.get(0).direction() != 1 || budget.refused()) return false;
            var shaft = shafts.get(0);
            if (i > 0) {
                var prior = heads.get(i - 1);
                float step = head.x - prior.x;
                if (step < 1.55f * gap || step > 3.7f * gap) return false;
                int rails = switch (i) {
                    case 4, 8, 11 -> 0;
                    case 10 -> 1;
                    default -> 2;
                };
                if (!WrappedEighthTriplets.matchesBeamRails(gray, width, height, previous, shaft, gap, rails))
                    return false;
                if (i < 10 && (i & 1) == 1 && (!LegacyTripletDotInk.singleDotCore(
                                gray, width, height, prior.x, prior.y, Math.min(head.x, prior.x + 2.52f * gap), gap)
                        || !OwnedTertiaryBeamlets.provesBackwardHookOnJoinedRails(
                                gray, width, height, head.x, head.y, prior.x, prior.y, gap))) return false;
            }
            previous = shaft;
        }
        return true;
    }

    private static boolean shapedRun(List<ScoreNoteEvent> notes, List<Integer> owners, List<Integer> guests) {
        for (int i : guests) {
            var n = notes.get(i);
            if (!plain(n) || n.stemDirection() != 1 || n.beamCount() != 1
                    || n.unbeamedDurationBeats() != 0 || n.augmentationDots() != 0
                    || n.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY || n.articulations() != 0)
                return false;
        }
        int clef = notes.get(owners.get(0)).clefBottomDiatonic();
        int falseDot = 0;
        for (int i = 0; i < owners.size(); i++) {
            var n = notes.get(owners.get(i));
            if (!plain(n) || n.clefBottomDiatonic() != clef || n.unbeamedDurationBeats() != 0
                    || n.beamCount() < 2 || n.beamCount() > 3) return false;
            if (i % 2 == 0) {
                if (n.beamCount() != 2 || n.augmentationDots() != 1) return false;
            } else if (n.augmentationDots() != 0 && (n.augmentationDots() != 1 || ++falseDot > 1)) return false;
        }
        return true;
    }

    private static boolean shapedLowerRibbon(
            List<ScoreNoteEvent> notes, List<Integer> owners, List<Integer> guests) {
        int clef = notes.get(owners.get(0)).clefBottomDiatonic(), weakDotted = 0, shortHooks = 0;
        for (int i = 0; i < owners.size(); i++) {
            var n = notes.get(owners.get(i));
            boolean dotted = (i & 1) == 0;
            if (!plain(n) || n.clefBottomDiatonic() != clef || n.unbeamedDurationBeats() != 0
                    || n.augmentationDots() != (dotted ? 1 : 0)) return false;
            if (dotted && n.beamCount() == 1) weakDotted++;
            else if (!dotted && n.beamCount() == 3) shortHooks++;
            else if (n.beamCount() != 2) return false;
        }
        if (weakDotted != 1 || shortHooks < 5) return false;
        for (int i = 0; i < guests.size(); i++) {
            var n = notes.get(guests.get(i));
            int beams = i == 2 ? 0 : 2 + i;
            if (!plain(n) || n.clefBottomDiatonic() != clef || n.augmentationDots() != 0
                    || n.articulations() != 0 || n.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                    || n.beamCount() != beams || n.stemDirection() != (i == 2 ? 1 : 0)
                    || n.unbeamedDurationBeats() != (i == 2 ? 1 : 0)) return false;
        }
        return true;
    }

    private record Head(float x, float y) {}

    private static boolean provesRun(byte[] gray, int width, int height, List<MeasureRegion> regions, int index,
            List<Head> heads, List<Head> fragments, List<RawStaffLineDetector.StaffLines> staffs,
            boolean lowerRibbon) {
        var bar = regions.get(index);
        var staff = staffs.stream().min(Comparator.comparingDouble(s ->
                Math.abs((bar.top() + bar.bottom()) * height * .5f - s.center()))).orElse(null);
        if (staff == null) return false;
        float gap = staff.gap(), left = bar.left() * width, right = bar.right() * width;
        if (bar.cadenceQuarterBeats() != 0 || gap < 10 || gap > 24 || right - left < 35 * gap
                || right - left > 43 * gap || heads.get(0).x - left < gap
                || right - heads.get(heads.size() - 1).x < gap * .9f || right - heads.get(heads.size() - 1).x > gap * 2.1f
                || regions.get(index + 1).top() - bar.bottom() < gap * 2 / height) return false;
        var rules = new PlayingTechniqueDetector.Staff(staff.top(), staff.bottom(), gap, 0, 1);
        float leftPocket = left - regions.get(index - 1).right() * width;
        if (leftPocket < gap * .55f || leftPocket > gap * 1.5f
                || PrintedCadenceDurations.boundaryColumn(gray, width, rules, left - leftPocket * .5f, true, false) < 0
                || PrintedCadenceDurations.boundaryColumn(gray, width, rules, right, true, false) < 0) return false;
        var budget = new PrintedBeamShafts.Budget(480_000);
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(16);
        for (var head : heads) {
            var own = PrintedBeamShafts.at(gray, width, height, head.x, head.y, gap, budget);
            if (own.size() != 1 || own.get(0).direction() != 1 || budget.refused()) return false;
            shafts.add(own.get(0));
        }
        for (int i = 1; i < heads.size(); i++) {
            var previous = heads.get(i - 1);
            var head = heads.get(i);
            var a = shafts.get(i - 1);
            var b = shafts.get(i);
            float space = head.x - previous.x;
            if (space < gap * 1.5f || space > gap * 4.1f) return false;
            if (i % 4 == 0) {
                if (!WrappedEighthTriplets.matchesBeamRails(gray,width,height,a,b,gap,0)
                        && !staffSeparates(gray,width,height,a,b,staff)) return false;
            } else if (!WrappedEighthTriplets.matchesBeamRails(gray,width,height,a,b,gap,2)) return false;
            if (i % 2 == 1) {
                if (!LegacyTripletDotInk.singleDotCore(gray, width, height, previous.x, previous.y,
                                Math.min(head.x, previous.x + gap * 2.52f), gap)
                        && (i != 1 || !attachedStaffDot(gray, width, height, previous, head, staff))) return false;
                if (!OwnedTertiaryBeamlets.provesBackwardHookOnJoinedRails(
                                gray, width, height, head.x, head.y, previous.x, previous.y, gap)
                        && (i != 1 || !shortAboveStaffContact(gray, width, height, a, b, staff))
                        && (!lowerRibbon || !staffJoinedBackwardHook(gray, width, height, b, gap)))
                    return false;
            } else {
                var core = WrappedTripletDotInk.uniqueCore(gray, width, height, previous.x, previous.y,
                        head.x, gap);
                if (core != null && !WrappedEighthTriplets.headConnectedLedger(
                        gray, width, height, previous.x, previous.y, core, gap, .38f)) return false;
            }
        }
        for (var fragment : fragments) {
            if (lowerRibbon
                    ? !lowerRailRibbonOwns(gray, width, height, fragment, heads, shafts, gap, budget)
                    : !runRailOwner(gray, width, height, fragment, heads, shafts, staff, budget))
                return false;
        }
        return true;
    }

    /** The third hook has a short shoulder beyond a continuous staff rule at the real shaft. */
    static boolean staffJoinedBackwardHook(
            byte[] gray, int width, int height, PrintedBeamShafts.Shaft shaft, float gap) {
        int axis = (shaft.left() + shaft.right()) / 2, tip = shaft.endY();
        int remote = axis - Math.round(1.42f * gap), right = axis + Math.round(.48f * gap);
        int start = axis - Math.round(.65f * gap), radius = Math.round(.13f * gap);
        int maximumDepth = Math.round(1.9f * gap);
        if (shaft.direction() != 1 || remote < 0 || right >= width
                || tip - maximumDepth - Math.round(.5f * gap) - radius < 0 || tip >= height)
            return false;
        for (int rule = tip - maximumDepth; rule <= tip - Math.round(1.15f * gap); rule++) {
            int wide = 0;
            for (int x = remote; x <= right; x++)
                if ((gray[rule * width + x] & 255) < 155) wide++;
            if (wide < (right - remote + 1) * .95f) continue;
            for (int side : new int[] {-1, 1}) {
                for (int offset = Math.round(.20f * gap); offset <= Math.round(.38f * gap); offset++) {
                    int row = rule + side * offset;
                    if (columnInk(gray, width, remote, row, radius) >= 2
                            || columnInk(gray, width, right, row, radius) >= 2) continue;
                    int core = 0;
                    for (int x = start; x <= axis; x++)
                        if (columnInk(gray, width, x, row, radius) >= 2 * radius)
                            core++;
                    if (core >= (axis - start + 1) * .91f) return true;
                }
            }
        }
        return false;
    }

    private static boolean lowerRailRibbonOwns(
            byte[] gray, int width, int height, Head fragment, List<Head> heads,
            List<PrintedBeamShafts.Shaft> shafts, float gap, PrintedBeamShafts.Budget budget) {
        int x = Math.round(fragment.x), y = Math.round(fragment.y);
        int half = Math.round(gap * .53f), radius = Math.round(gap * .22f);
        if (x - half < 0 || x + half >= width || y - radius < 0 || y + radius >= height
                || fragment.x < shafts.get(12).right() + gap * .35f
                || fragment.x > shafts.get(shafts.size() - 1).left() - gap * .65f
                || fragment.y < heads.subList(12, 16).stream().mapToDouble(Head::y).max().orElseThrow()
                        + gap * 3.3f
                || !PrintedBeamShafts.at(gray, width, height, fragment.x, fragment.y, gap, budget).isEmpty()
                || budget.refused()) return false;
        for (int i = 12; i < 15; i++) {
            var a = shafts.get(i);
            var b = shafts.get(i + 1);
            float l = (a.left() + a.right()) / 2f, r = (b.left() + b.right()) / 2f;
            if (fragment.x < l || fragment.x > r) continue;
            float tip = a.endY() + (b.endY() - a.endY()) * (fragment.x - l) / (r - l);
            if (tip - fragment.y < gap * .20f || tip - fragment.y > gap * .72f) return false;
            int thick = 0;
            for (int col = x - half; col <= x + half; col++)
                if (columnInk(gray, width, col, y, radius) >= 2 * radius - 1) thick++;
            return thick >= (2 * half + 1) * .86f;
        }
        return false;
    }

    private static Head head(ScoreNoteEvent note, MeasureRegion bar, int width, int height) {
        return new Head((bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width,
                note.pageY() * height);
    }

    private static boolean attachedStaffDot(byte[] gray, int width, int height, Head owner, Head next,
            RawStaffLineDetector.StaffLines staff) {
        float gap = staff.gap();
        int line = staff.rows()[0];
        int left = Math.round(owner.x + gap * .72f), right = Math.round(next.x - gap * .7f);
        int top = Math.round(owner.y - gap * .80f), bottom = Math.round(owner.y + gap * .37f);
        if (left < 0 || top < 0 || right >= width || bottom >= height || right <= left
                || Math.abs(owner.y - line) > gap * .68f || right - left > gap * 1.55f) return false;
        int w = right - left + 1, h = bottom - top + 1;
        var ink = new byte[w * h];
        int erased = Math.round(gap * .12f);
        for (int y = 0; y < h; y++) {
            int at = (top + y) * width + left;
            if (Math.abs(top + y - line) <= erased) {
                java.util.Arrays.fill(ink, y * w, (y + 1) * w, (byte) 255);
            } else {
                System.arraycopy(gray, at, ink, y * w, w);
            }
        }
        var parts = RawNumeralComponents.capture(ink, w, h, 0, 0, w - 1, h - 1, 155, () -> {}, () -> {});
        int matches = 0;
        for (var part : parts.components()) {
            int ww = part.right - part.left + 1, hh = part.bottom - part.top + 1;
            if (part.left <= 0 || part.right >= w - 1 || part.bottom >= h - 1
                    || part.top + top != line + erased + 1
                    || ww < gap * .30f || ww > gap * .63f || hh < gap * .28f || hh > gap * .63f
                    || part.size() < ww * hh * .64f) continue;
            if (++matches > 1) return false;
        }
        return matches == 1;
    }

    private static boolean staffSeparates(byte[] gray, int width, int height,
            PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, RawStaffLineDetector.StaffLines staff) {
        float gap = staff.gap();
        int inset = Math.round(gap * .29f);
        int left = a.right() + inset, right = b.left() - inset;
        int top = Math.min(a.endY(), b.endY()) - Math.round(gap * .38f);
        int bottom = Math.max(a.endY(), b.endY()) + Math.round(gap * .30f);
        if (left < 0 || right >= width || top < 0 || bottom >= height || right - left < gap * .60f)
            return false;
        int clean = 0, longest = 0, rule = staff.bottom(), radius = Math.round(gap * .18f);
        if (Math.abs(a.endY() - rule) > gap * .63f || Math.abs(b.endY() - rule) > gap * .43f) return false;
        for (int x = left; x <= right; x++) {
            int residue = 0, onRule = 0;
            for (int y = top; y <= bottom; y++) {
                if ((gray[y * width + x] & 255) >= 155) continue;
                if (Math.abs(y - rule) <= radius) onRule++;
                else residue++;
            }
            clean = residue <= Math.round(gap * .11f) && onRule >= 2 && onRule <= Math.round(gap * .28f)
                    ? clean + 1 : 0;
            longest = Math.max(longest, clean);
        }
        return longest >= Math.round(gap * .45f);
    }

    private static boolean shortAboveStaffContact(byte[] gray, int width, int height,
            PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, RawStaffLineDetector.StaffLines staff) {
        float gap = staff.gap();
        int x = (b.left() + b.right()) / 2;
        int near = Math.round(gap * .30f), far = Math.round(gap * .64f), remote = Math.round(gap * 1.22f);
        if (x - remote < 0 || x + near >= width || a.endY() - b.endY() < gap * .85f) return false;
        for (int depth = Math.round(gap * .82f); depth <= Math.round(gap * 1.33f); depth++) {
            int y = b.endY() - depth;
            if (y < 2 || y >= height - 2) return false;
            int attached = 0;
            for (int dx = 0; dx <= far; dx++)
                if (columnInk(gray, width, x - dx, y, 2) >= 4) attached++;
            if (attached < (far + 1) * .90f) continue;
            if (columnInk(gray, width, x - remote, y, 2) <= 1
                    && columnInk(gray, width, x + near, y, 2) <= 1) return true;
        }
        return false;
    }

    private static int columnInk(byte[] gray, int width, int x, int y, int half) {
        int count = 0;
        for (int row = y - half; row <= y + half; row++)
            if ((gray[row * width + x] & 255) < 155) count++;
        return count;
    }

    private static boolean runRailOwner(byte[] gray, int width, int height, Head fragment, List<Head> heads,
            List<PrintedBeamShafts.Shaft> shafts, RawStaffLineDetector.StaffLines staff,
            PrintedBeamShafts.Budget budget) {
        float gap = staff.gap();
        if (fragment.x < gap * 2 || fragment.x >= width - gap
                || fragment.y < gap || fragment.y >= height - gap) return false;
        var reverse = PrintedBeamShafts.at(gray, width, height, fragment.x, fragment.y, gap, budget);
        if (reverse.size() != 1 || reverse.get(0).direction() != -1 || budget.refused()) return false;
        var witness = reverse.get(0);
        int owners = 0;
        for (int i = 1; i < heads.size(); i += 2) {
            var owner = shafts.get(i);
            int axis = (owner.left() + owner.right()) / 2;
            float depth = owner.endY() - fragment.y;
            if (Math.max(witness.left(), owner.left()) > Math.min(witness.right(), owner.right())
                    || Math.abs(witness.endY() - heads.get(i).y) > gap * .32f
                    || axis - fragment.x < gap * .28f || axis - fragment.x > gap * .82f
                    || depth < gap * .43f || depth > gap * 1.9f) continue;
            int y = Math.round(fragment.y), x = Math.round(fragment.x);
            int solid = 0;
            for (int col = x; col <= axis; col++)
                if (columnInk(gray, width, col, y, 2) >= 3) solid++;
            if (solid < (axis - x + 1) * .80f) continue;
            if (depth > gap * 1.12f) {
                int remote = axis - Math.round(gap * 1.1f);
                int right = axis + Math.round(gap * .47f);
                if (columnInk(gray, width, remote, y, 3) >= 5
                        || columnInk(gray, width, right, y, 3) >= 5) continue;
            } else {
                int broad = axis - Math.round(gap * 1.35f);
                if (columnInk(gray, width, broad, y, Math.round(gap * .44f)) < gap * .55f) continue;
            }
            if (++owners > 1) return false;
        }
        return owners == 1;
    }

    private static boolean proves(byte[] gray, int width, int height, List<MeasureRegion> regions, int index,
            List<Head> heads, Head guest, List<RawStaffLineDetector.StaffLines> staffs,
            boolean atShoulder) {
        var before = regions.get(index - 1);
        var bar = regions.get(index);
        var after = regions.get(index + 1);
        var staff = staffs.stream().min(Comparator.comparingDouble(s ->
                Math.abs((bar.top() + bar.bottom()) * height * .5f - s.center()))).orElse(null);
        if (staff == null) return false;
        float gap = staff.gap(), left = bar.left() * width, right = bar.right() * width;
        float leftPocket = left - before.right() * width, rightPocket = after.left() * width - right;
        if (bar.cadenceQuarterBeats() != 0 || gap < 10 || gap > 24 || right - left < 22 * gap
                || right - left > 30 * gap || leftPocket < gap * .55f || leftPocket > gap * 1.5f
                || rightPocket < gap * .55f || rightPocket > gap * 1.5f
                || Math.abs(before.bottom() - bar.bottom()) * height > gap * .35f
                || Math.abs(after.bottom() - bar.bottom()) * height > gap * .35f) return false;
        var rules = new PlayingTechniqueDetector.Staff(staff.top(), staff.bottom(), gap, 0, 1);
        if (PrintedCadenceDurations.boundaryColumn(gray, width, rules, left - leftPocket * .5f, true, false) < 0
                || PrintedCadenceDurations.boundaryColumn(gray, width, rules, right + rightPocket * .5f, true, false) < 0)
            return false;

        var half = heads.get(heads.size() - 1);
        if (right - half.x < 2.5f * gap || right - half.x > 4.5f * gap
                || !WholeHeadPocketEvidence.hasEnclosedOpening(gray, width, height,
                        Math.round(half.x - gap), Math.round(half.y - .73f * gap),
                        Math.round(half.x + .63f * gap), Math.round(half.y + .73f * gap),
                        half.x - gap * .12f, half.y, gap, Math.max(12, Math.round(gap * gap * .065f)), 3))
            return false;
        var budget = new PrintedBeamShafts.Budget(200_000);
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(9);
        for (var head : heads) {
            var choices = PrintedBeamShafts.at(gray, width, height, head.x, head.y, gap, budget);
            if (choices.size() != 1 || choices.get(0).direction() != 1 || budget.refused()) return false;
            shafts.add(choices.get(0));
        }
        for (int i = 1; i < 9; i++) {
            var previous = heads.get(i - 1);
            var head = heads.get(i);
            float space = head.x - previous.x;
            if (space < gap * 1.5f || space > gap * 4.1f
                    || !WrappedEighthTriplets.matchesBeamRails(gray, width, height,
                            shafts.get(i - 1), shafts.get(i), gap, i % 4 == 0 ? 0 : 2)) return false;
            if (i % 2 == 1 && (!LegacyTripletDotInk.singleDotCore(gray, width, height, previous.x, previous.y,
                            Math.min(head.x, previous.x + gap * 2.52f), gap)
                    || !OwnedTertiaryBeamlets.provesBackwardHookOnJoinedRails(
                            gray, width, height, head.x, head.y, previous.x, previous.y, gap))) return false;
        }
        // A second mask can sit directly beside the dotted note's shaft below the staff.
        if (atShoulder && (guest.y - staff.bottom() < gap * .28f
                || guest.y - staff.bottom() > gap * .68f
                || guest.y - heads.get(6).y < gap * 3.1f)) return false;
        return ownedByTwoRails(gray, width, height, guest, shafts.get(6), shafts.get(7), gap,
                atShoulder ? .10f : .25f, atShoulder ? .24f : .67f, budget);
    }

    private static boolean ownedByTwoRails(byte[] gray, int width, int height, Head guest,
            PrintedBeamShafts.Shaft left, PrintedBeamShafts.Shaft right, float gap,
            float minimumProgress, float maximumProgress, PrintedBeamShafts.Budget budget) {
        if (!PrintedBeamShafts.at(gray, width, height, guest.x, guest.y, gap, budget).isEmpty()
                || budget.refused()) return false;
        int a = (left.left() + left.right()) / 2, b = (right.left() + right.right()) / 2;
        float progress = (guest.x - a) / (b - a);
        float tip = left.endY() + progress * (right.endY() - left.endY());
        if (progress < minimumProgress || progress > maximumProgress
                || tip - guest.y < gap * .48f || tip - guest.y > gap * .90f)
            return false;
        int center = Math.round(guest.x), half = Math.round(gap * .54f);
        if (center - half < 0 || center + half >= width) return false;
        int thick = Math.round(gap * .24f);
        int matches = 0;
        for (int x = center - half; x <= center + half; x++) {
            int y = Math.round(tip + (right.endY() - left.endY()) * (x - guest.x) / (b - a));
            if (y >= height || y - Math.round(1.32f * gap) < 0) return false;
            int lower = 0, upper = 0;
            for (int k = y - Math.round(gap * .4f); k <= y; k++)
                if ((gray[k * width + x] & 255) < 155) lower++;
            for (int k = y - Math.round(gap * 1.32f); k <= y - Math.round(gap * .62f); k++)
                if ((gray[k * width + x] & 255) < 155) upper++;
            if (lower >= thick && upper >= thick) matches++;
        }
        return matches >= (2 * half + 1) * .80f;
    }

    private static ScoreNoteEvent withTertiary(ScoreNoteEvent n) {
        return rhythm(n, 3, 0);
    }

    static ScoreNoteEvent rhythm(ScoreNoteEvent n, int beams, int dots) {
        return new ScoreNoteEvent(n.measureIndex(), n.positionInMeasure(), n.staffStep(), n.staffIndex(),
                n.staffCount(), n.pageY(), n.tiedFromPrevious(), dots, beams, n.writtenAccidental(),
                n.unbeamedDurationBeats(), n.tupletDivisor(), n.followingRestBeats(), n.articulations(),
                n.clefBottomDiatonic(), n.crossStaffBeam(), n.leadingRestBeats(), n.compactOpening(),
                n.octaveShift(), n.boundaryTies(), n.tupletNormalNotes(), n.stemDirection(), n.kind(),
                n.visualSize(), n.harmonicSemitones(), n.openStringTouch());
    }
}
