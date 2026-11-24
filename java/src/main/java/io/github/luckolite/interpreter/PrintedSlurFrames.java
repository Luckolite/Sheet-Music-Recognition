// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;

/** Builds page-local arc frames from independently proved raw staff tracks and final note slots. */
final class PrintedSlurFrames {
    record Result<T extends PrintedSlurOwners.Location>(
            List<PrintedSlurPageCapture.Frame<T>> frames,
            Set<Integer> unboundOwners,
            int unprovedStaffs,
            Set<Integer> unprovedStaffSlots) {
        Result(
                List<PrintedSlurPageCapture.Frame<T>> frames,
                Set<Integer> unboundOwners,
                int unprovedStaffs) {
            this(frames, unboundOwners, unprovedStaffs, Set.of());
        }

        Result {
            frames = List.copyOf(frames);
            unboundOwners = Set.copyOf(unboundOwners);
            unprovedStaffSlots = Set.copyOf(unprovedStaffSlots);
        }
    }

    private PrintedSlurFrames() {}

    static <T extends PrintedSlurOwners.Location> Result<T> prepare(
            byte[] gray,
            int width,
            int height,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<ScoreNoteEvent> notes,
            List<T> heads) {
        return prepareInternal(gray, width, height, staffs, notes, heads, null, List.of());
    }

    static <T extends PrintedSlurOwners.Location> Result<T> prepareSupplemental(
            byte[] gray,
            int width,
            int height,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<ScoreNoteEvent> notes,
            List<T> heads,
            Result<?> legacy) {
        return prepareSupplemental(gray, width, height, staffs, notes, heads, legacy, List.of());
    }

    static <T extends PrintedSlurOwners.Location> Result<T> prepareSupplemental(
            byte[] gray, int width, int height, List<PlayingTechniqueDetector.Staff> staffs,
            List<ScoreNoteEvent> notes, List<T> heads, Result<?> legacy, List<MeasureRegion> measures) {
        return prepareInternal(
                gray,
                width,
                height,
                staffs,
                notes,
                heads.stream().filter(h -> legacy.unboundOwners().contains(h.owner())).toList(),
                legacy.unprovedStaffSlots(), measures);
    }

    private static MeasureRegion lastMeasure(List<MeasureRegion> measures,
            PlayingTechniqueDetector.Staff staff, int height) {
        if (staff.count() != 1) return null;
        MeasureRegion last = null;
        for (var measure : measures) {
            if (measure.top() * height > staff.top() || measure.bottom() * height < staff.bottom())
                continue;
            if (last == null || measure.right() > last.right()) last = measure;
        }
        return last;
    }

    private static float[] provedTerminalHeadFrame(byte[] gray, int width, int height, float x,
            MeasureRegion terminal, float[] surrounding) {
        float gap = surrounding[1];
        if (gap < 10 || gap > 24) return null;
        float[] rules = PrintedDirectionStaff.printedRules(gray, width, height, x, surrounding[0], gap);
        if (rules == null) rules = TerminalDirectionRails.read(gray, width, height, x,
                surrounding[0], gap, terminal.left(), terminal.right());
        return rules != null && Math.abs(rules[0] - surrounding[0]) <= gap * .15f
                && Math.abs(rules[1] - gap) <= gap * .05f ? rules : null;
    }

    static <T extends PrintedSlurOwners.Location> Result<T> prepareInternal(
            byte[] gray,
            int width,
            int height,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<ScoreNoteEvent> notes,
            List<T> heads,
            Set<Integer> supplementalSlots, List<MeasureRegion> measures) {
        if (width <= 0 || height <= 0 || gray == null || gray.length != (long) width * height)
            throw new IllegalArgumentException("Invalid printed frame raster");
        Objects.requireNonNull(staffs);
        Objects.requireNonNull(notes);
        Objects.requireNonNull(heads);
        if (staffs.size() > 512)
            throw new IllegalArgumentException("Too many printed staff frames");
        var tracks = new ArrayList<StaffPitchTrack>();
        var groups = new ArrayList<List<T>>();
        int unproved = 0;
        var unprovedSlots = new HashSet<Integer>();
        var optionalProof =
                supplementalSlots == null
                        ? null
                        : new WholeStaffSlurRules.Page(gray, width, height);
        for (var staff : staffs) {
            if (staff.index() < 0 || staff.count() < 1 || staff.index() >= staff.count())
                throw new IllegalArgumentException("Invalid physical staff identity");
            var track =
                    supplementalSlots == null
                            ? StaffPitchTrack.forPrintedSlurs(
                                    gray, width, height, staff.top(), staff.bottom(), staff.gap())
                            : supplementalSlots.contains(tracks.size())
                                    ? optionalProof.prove(gray, width, height, staff)
                                    : null;
            tracks.add(track);
            groups.add(new ArrayList<>());
            if (track == null) {
                unproved++;
                unprovedSlots.add(tracks.size() - 1);
            }
        }
        // Revalidate the source only if there are supplemental tracks that could be retained.
        if (optionalProof != null && unproved < staffs.size() && !optionalProof.finish()) {
            Collections.fill(tracks, null);
            unproved = staffs.size();
            for (int i = 0; i < staffs.size(); i++) unprovedSlots.add(i);
        }
        var seen = new HashSet<Integer>();
        var unbound = new HashSet<Integer>();
        int terminalChecks = 0;
        for (var head : heads) {
            if (head.owner() >= notes.size()
                    || !seen.add(head.owner())
                    || head.x() >= width
                    || head.y() >= height)
                throw new IllegalArgumentException("Stale or duplicate printed head ownership");
            var note = notes.get(head.owner());
            if (note.staffIndex() != head.staffIndex() || note.staffCount() != head.staffCount())
                throw new IllegalArgumentException(
                        "Printed head differs from its retained staff identity");
            int owner = -1;
            for (int i = 0; i < staffs.size(); i++) {
                var staff = staffs.get(i);
                var track = tracks.get(i);
                if (track == null
                        || staff.index() != head.staffIndex()
                        || staff.count() != head.staffCount()) continue;
                float[] local = track.at(head.x());
                float predicted = local[0] - note.staffStep() * local[1] * .5f;
                float offset = head.y() - predicted;
                if (Math.abs(offset) > local[1] * .45f) {
                    if (supplementalSlots == null || staff.count() != 1
                            || offset < 0 || offset > local[1] * .80f) continue;
                    if (!track.verifiedAt(head.x())) {
                        if (terminalChecks >= 8 || note.staffStep() < 13
                                || note.measureIndex() < 0 || note.measureIndex() >= measures.size())
                            continue;
                        var last = lastMeasure(measures, staff, height);
                        if (last == null || last != measures.get(note.measureIndex())
                                || head.x() < last.left() * width || head.x() > last.right() * width)
                            continue;
                        terminalChecks++;
                        local = provedTerminalHeadFrame(gray, width, height, head.x(), last, local);
                        if (local == null) continue;
                    }
                    if (!OmrScoreInterpreter.compressedLedgerBeamPhase(gray, width, height,
                            head.x(), head.y(), local[0], local[1], note.staffStep())) continue;
                }
                if (owner >= 0) {
                    owner = -2;
                    break;
                }
                owner = i;
            }
            if (owner < 0) unbound.add(head.owner());
            else groups.get(owner).add(head);
        }
        var frames = new ArrayList<PrintedSlurPageCapture.Frame<T>>();
        for (int i = 0; i < staffs.size(); i++) {
            var group = groups.get(i);
            if (group.isEmpty()) continue;
            var staff = staffs.get(i);
            float top = staff.top() - staff.gap() * 6, bottom = staff.bottom() + staff.gap() * 6;
            for (var head : group) {
                float gap = tracks.get(i).at(head.x())[1];
                top = Math.min(top, head.y() - gap * 8);
                bottom = Math.max(bottom, head.y() + gap * 8);
            }
            int first = Math.max(0, (int) Math.floor(top)),
                    last = Math.min(height - 1, (int) Math.ceil(bottom));
            if (last <= first) throw new IllegalArgumentException("Empty printed staff scan frame");
            frames.add(
                    new PrintedSlurPageCapture.Frame<T>(
                            0, first, width - 1, last, tracks.get(i), group));
        }
        return new Result<>(frames, unbound, unproved, unprovedSlots);
    }
}
