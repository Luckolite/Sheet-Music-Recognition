// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;

/** Attach isolated ornaments to one written note, respecting staff and measure geometry. */
final class PortableNoteOrnaments {
    /** Right and bottom are exclusive, matching Android crop bounds. */
    static final class Bounds {
        int left, top, right, bottom;
        int trillWaveRight;

        Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        Bounds(Bounds other) {
            this(other.left, other.top, other.right, other.bottom);
            trillWaveRight = other.trillWaveRight;
        }

        int width() {
            return right - left;
        }

        int height() {
            return bottom - top;
        }

        float exactCenterX() {
            return (left + right) * .5f;
        }

        float exactCenterY() {
            return (top + bottom) * .5f;
        }

        void union(Bounds other) {
            left = Math.min(left, other.left);
            top = Math.min(top, other.top);
            right = Math.max(right, other.right);
            bottom = Math.max(bottom, other.bottom);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Bounds b
                    && left == b.left
                    && top == b.top
                    && right == b.right
                    && bottom == b.bottom;
        }

        @Override
        public int hashCode() {
            return Objects.hash(left, top, right, bottom);
        }
    }

    record Anchor(float x, float y, float gap, int staff, int measure, int stemDirection, int staffStep) {
        Anchor(float x, float y, float gap, int staff, int measure) {
            this(x, y, gap, staff, measure, 0, Integer.MIN_VALUE);
        }
    }

    record Found(Bounds bounds, int marks, int noteIndex) {}

    private PortableNoteOrnaments() {}

    static List<ScoreNoteEvent> apply(
            PortableOrnamentGlyphs recognizer,
            byte[] labels,
            byte[] gray,
            int w,
            int h,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            List<PlayingTechniqueDetector.Word> trills) {
        var staffs =
                ScoreDynamicsDetector.alignStaffs(
                        OmrScoreInterpreter.techniqueStaffs(labels, gray, w, h, measures),
                        notes,
                        h);
        return applyWithAlignedStaffs(
                recognizer, labels, gray, w, h, measures, notes, trills, staffs);
    }

    /** Reuse only geometry computed for these same rasters, measures and unmodified notes. */
    static List<ScoreNoteEvent> applyWithAlignedStaffs(
            PortableOrnamentGlyphs recognizer,
            byte[] gray,
            int w,
            int h,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            List<PlayingTechniqueDetector.Word> trills,
            List<PlayingTechniqueDetector.Staff> staffs) {
        return applyWithAlignedStaffs(
                recognizer, null, gray, w, h, measures, notes, trills, staffs);
    }

    /** Retained labels permit only a physically verified local staff frame. */
    static List<ScoreNoteEvent> applyWithAlignedStaffs(
            PortableOrnamentGlyphs recognizer,
            byte[] labels,
            byte[] gray,
            int w,
            int h,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            List<PlayingTechniqueDetector.Word> trills,
            List<PlayingTechniqueDetector.Staff> staffs) {
        List<Anchor> anchors = new ArrayList<>();
        for (var note : notes) {
            var region = measures.get(note.measureIndex());
            float x =
                    (region.left() + note.positionInMeasure() * (region.right() - region.left()))
                            * w;
            int best = -1;
            float distance = Float.MAX_VALUE;
            for (int i = 0; i < staffs.size(); i++) {
                var staff = staffs.get(i);
                if (staff.index() != note.staffIndex()) continue;
                float d = Math.abs(note.pageY() * h - (staff.top() + staff.bottom()) * .5f);
                if (d < distance) {
                    distance = d;
                    best = i;
                }
            }
            anchors.add(
                    new Anchor(
                            x,
                            note.pageY() * h,
                            best < 0 ? 12 : staffs.get(best).gap(),
                            best,
                            note.measureIndex(),
                            note.stemDirection(),
                            note.staffStep()));
        }
        int[] marks = new int[notes.size()];
        for (var found : detect(recognizer, labels, gray, w, h, staffs, anchors, trills))
            if (notes.get(found.noteIndex()).kind() == ScoreNoteEvent.Kind.PITCHED)
                marks[found.noteIndex()] |= found.marks();
        var slideStaffs =
                staffs.stream()
                        .map(s -> new NoteSlideDetector.Staff(s.top(), s.bottom(), s.gap()))
                        .toList();
        var slideHeads =
                anchors.stream()
                        .map(n -> new NoteSlideDetector.Head(n.x, n.y, n.gap, n.staff, n.measure))
                        .toList();
        // The wave scan reads the prepared raster before the slide scan removes ledger lines.
        byte[] sharedClean =
                gray != null && gray.length == (long) w * h && slideHeads.size() >= 2
                        ? NoteSlideDetector.removeStaffLines(gray, w, h, slideStaffs)
                        : null;
        for (var gliss :
                sharedClean == null
                        ? WaveGlissDetector.detect(gray, w, h, slideStaffs, slideHeads)
                        : WaveGlissDetector.detectWithRemovedStaffLines(
                                w, h, slideHeads, sharedClean))
            if (notes.get(gliss.sourceIndex()).kind() == ScoreNoteEvent.Kind.PITCHED
                    && notes.get(gliss.targetIndex()).kind() == ScoreNoteEvent.Kind.PITCHED
                    && marks[gliss.sourceIndex()] == 0
                    && NoteOrnament.type(notes.get(gliss.sourceIndex()).articulations()) == 0)
                marks[gliss.sourceIndex()] = NoteOrnament.GLISSANDO
                        | (notes.get(gliss.targetIndex()).measureIndex()
                                        > notes.get(gliss.sourceIndex()).measureIndex()
                                ? NoteOrnament.GLISSANDO_NEXT_BAR : 0);
        for (var slide :
                sharedClean == null
                        ? NoteSlideDetector.detect(gray, w, h, slideStaffs, slideHeads)
                        : NoteSlideDetector.detectWithRemovedStaffLines(
                                gray, w, h, slideStaffs, slideHeads, sharedClean))
            if (notes.get(slide.noteIndex()).kind() == ScoreNoteEvent.Kind.PITCHED
                    && (!slide.connected()
                            || pitchedPreviousAnchor(anchors, notes, slide.noteIndex()))
                    && marks[slide.noteIndex()] == 0) marks[slide.noteIndex()] = slideMarks(slide);
        boolean[] textHeads = new boolean[notes.size()];
        applySameStaffPortamentoWords(trills, gray, w, h, anchors, notes, marks, textHeads);
        for (var found : CrossRowPortamento.find(gray, w, h, staffs, measures, notes, trills)) {
            int target = found.targetIndex();
            if (marks[target] == 0
                    && NoteOrnament.type(notes.get(target).articulations()) == NoteOrnament.NONE)
                marks[target] =
                        NoteOrnament.SLIDE
                                | NoteOrnament.FROM_PREVIOUS
                                | (found.fromAbove() ? NoteOrnament.FROM_ABOVE : 0);
        }
        List<ScoreNoteEvent> result = new ArrayList<>(notes.size());
        for (int i = 0; i < notes.size(); i++) {
            if (textHeads[i]) continue;
            var note = notes.get(i);
            int retained = note.articulations();
            // The raw detector already proved this isolated curve and its destination.
            int retainedShape = retained & (NoteOrnament.TYPE_MASK | NoteOrnament.STRAIGHT_APPROACH
                    | NoteOrnament.FROM_ABOVE | NoteOrnament.FROM_PREVIOUS);
            int added = retainedShape == NoteOrnament.SLIDE ? marks[i] & ~NoteOrnament.ALL : marks[i];
            result.add(note.withArticulations(retained | added));
        }
        return List.copyOf(result);
    }

    static void applySameStaffPortamentoWords(
            List<PlayingTechniqueDetector.Word> words, byte[] gray, int w, int h,
            List<Anchor> anchors, List<ScoreNoteEvent> notes, int[] marks, boolean[] textHeads) {
        for (var word : words)
            if (word.text().equals("port") && ScoreDynamicsDetector.containsInk(word, gray, w, h)) {
                float left = word.left() * w,
                        right = word.right() * w,
                        cy = (word.top() + word.bottom()) * .5f * h;
                int owner = -1;
                double best = Double.MAX_VALUE;
                for (int i = 0; i < anchors.size(); i++) {
                    var n = anchors.get(i);
                    if (n.staff < 0
                            || n.x < right - n.gap * .2f
                            || n.x - right > n.gap * 6
                            || Math.abs(n.y - cy) > n.gap * 3) continue;
                    double distance = Math.abs(n.x - right) + Math.abs(n.y - cy);
                    if (distance < best) {
                        best = distance;
                        owner = i;
                    }
                }
                if (owner < 0 || notes.get(owner).kind() != ScoreNoteEvent.Kind.PITCHED) continue;
                var n = anchors.get(owner);
                Anchor prior = null;
                for (var a : anchors)
                    if (a.staff == n.staff
                            && a.measure == n.measure
                            && a.x < left + n.gap * .3f
                            && a.x < n.x - n.gap
                            && (prior == null || a.x > prior.x)) prior = a;
                if (prior == null
                        || notes.get(anchors.indexOf(prior)).kind() != ScoreNoteEvent.Kind.PITCHED
                        || Math.abs(prior.y - n.y) <= n.gap * .25f) continue;
                int portamento = NoteOrnament.SLIDE | NoteOrnament.FROM_PREVIOUS
                        | (prior.y < n.y ? NoteOrnament.FROM_ABOVE : 0);
                int existing = marks[owner];
                if (existing != 0 && existing != portamento
                        || !NoteSlideDetector.connection(
                                gray, w, h,
                                new NoteSlideDetector.Head(
                                        prior.x, prior.y, prior.gap, prior.staff, prior.measure),
                                new NoteSlideDetector.Head(n.x, n.y, n.gap, n.staff, n.measure)))
                    continue;
                marks[owner] = portamento;
                // The line and the printed word can independently prove the same slide.
                // A prior slide mark still allows the word to own its stemless letter alias.
                for (int i = 0; i < anchors.size(); i++) {
                    var a = anchors.get(i);
                    var note = notes.get(i);
                    if (note.kind() != ScoreNoteEvent.Kind.PITCHED || i == owner
                            || a.staff != n.staff || a.measure != n.measure
                            || a.x <= left || a.x >= right
                            || a.y <= word.top() * h || a.y >= word.bottom() * h) continue;
                    if (note.stemDirection() == 0 && note.beamCount() == 0
                            && note.unbeamedDurationBeats() == 4 && !note.tiedFromPrevious()
                            && !note.crossStaffBeam()) textHeads[i] = true;
                }
            }
    }

    /** Preserve the detector's proved shape; isolated and connected are ownership roles. */
    static int slideMarks(NoteSlideDetector.Stroke slide) {
        return NoteOrnament.SLIDE
                | (slide.direction() < 0 ? NoteOrnament.FROM_ABOVE : 0)
                | (slide.connected() ? NoteOrnament.FROM_PREVIOUS : 0)
                | (!slide.connected() && !slide.curved() ? NoteOrnament.STRAIGHT_APPROACH : 0);
    }

    private static boolean pitchedPreviousAnchor(
            List<Anchor> anchors, List<ScoreNoteEvent> notes, int targetIndex) {
        Anchor target = anchors.get(targetIndex);
        float latest = -Float.MAX_VALUE;
        for (var head : anchors)
            if (head.staff == target.staff
                    && head.measure == target.measure
                    && head.x < target.x - target.gap * .35f) latest = Math.max(latest, head.x);
        int source = -1;
        for (int i = 0; i < anchors.size(); i++) {
            var head = anchors.get(i);
            if (head.staff == target.staff
                    && head.measure == target.measure
                    && head.x < target.x - target.gap * .35f
                    && latest - head.x <= target.gap * .35f) {
                if (source >= 0) return false;
                source = i;
            }
        }
        if (source >= 0) return notes.get(source).kind() == ScoreNoteEvent.Kind.PITCHED;
        var heads =
                anchors.stream()
                        .map(a -> new NoteSlideDetector.Head(a.x, a.y, a.gap, a.staff, a.measure))
                        .toList();
        source = FiniteConnectedSlidePorts.previousAcrossBar(heads, targetIndex);
        return source >= 0
                && notes.get(source).kind() == ScoreNoteEvent.Kind.PITCHED
                && notes.get(source).followingRestBeats() == 0
                && notes.get(targetIndex).leadingRestBeats() == 0;
    }

    static List<Found> detect(
            PortableOrnamentGlyphs recognizer,
            byte[] gray,
            int width,
            int height,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<Anchor> notes) {
        return detect(recognizer, gray, width, height, staffs, notes, List.of());
    }

    static List<Found> detect(
            PortableOrnamentGlyphs recognizer,
            byte[] gray,
            int width,
            int height,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<Anchor> notes,
            List<PlayingTechniqueDetector.Word> trills) {
        return detect(recognizer, null, gray, width, height, staffs, notes, trills);
    }

    static List<Found> detect(
            PortableOrnamentGlyphs recognizer,
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<Anchor> notes,
            List<PlayingTechniqueDetector.Word> trills) {
        var tracks = ornamentTracks(labels, gray, width, height, staffs);
        if (!staffs.isEmpty())
            gray =
                    RestPaperTone.normalizeOrdinaryRestInk(
                            gray, width, height, staffs.get(0).gap(), 220f);
        var boxes = boxes(gray, width, height, staffs, tracks, recognizer);
        List<Found> found = new ArrayList<>();
        var repeatedTrills = new PageTrillEvidence(recognizer, gray, width, height, boxes, trills);
        Set<Bounds> textTrills = new HashSet<>();
        for (var word : trills)
            if (word.text().equals("tr")
                    && ScoreDynamicsDetector.containsInk(word, gray, width, height)) {
                Bounds r =
                        new Bounds(
                                Math.max(0, Math.round(word.left() * width)),
                                Math.max(0, Math.round(word.top() * height)),
                                Math.min(width, Math.round(word.right() * width)),
                                Math.min(height, Math.round(word.bottom() * height)));
                if (r.width() > 0 && r.height() > 0) {
                    boxes.add(r);
                    textTrills.add(r);
                }
            }
        var query = repeatedTrills.canRecognize() ? new PortableOrnamentGlyphs.Query() : null;
        for (Bounds box : boxes) {
            // Ledger lines through an open head can resemble a compressed mordent.
            // A component already containing a written head cannot ornament another note.
            boolean noteInk = false;
            for (var note : notes)
                if (note.x >= box.left
                        && note.x < box.right
                        && note.y >= box.top
                        && note.y < box.bottom) {
                    noteInk = true;
                    break;
                }
            if (noteInk) continue;
            var match =
                    textTrills.contains(box) || repeatedTrills.confirmed(box)
                            ? new PortableOrnamentGlyphs.Match(NoteOrnament.TRILL, 1, 1)
                            : recognizer.match(gray, width, box, query);
            if (!match.accepted() && repeatedTrills.recognizes(gray, width, box, match, query))
                match = new PortableOrnamentGlyphs.Match(NoteOrnament.TRILL, 1, 1);
            if (!match.accepted() && match.kind() == NoteOrnament.TRILL && match.score() > .25f) {
                // In small italic print the r's terminal and the period can be tiny detached
                // islands. Recheck the complete word without relaxing the glyph threshold.
                Bounds joined =
                        inkBounds(
                                gray,
                                width,
                                new Bounds(
                                        Math.max(0, box.left - 1),
                                        Math.max(0, box.top - 1),
                                        Math.min(width, box.right + Math.round(box.height() * .7f)),
                                        Math.min(height, box.bottom + 2)));
                if (joined != null) {
                    var full = recognizer.match(gray, width, joined);
                    if (full.kind() == NoteOrnament.TRILL && full.accepted()) {
                        box = joined;
                        match = full;
                    }
                }
            }
            if (!match.accepted()) continue;
            int owner = -1;
            double best = Double.MAX_VALUE;
            boolean delayed = false;
            for (int i = 0; i < notes.size(); i++) {
                var n = notes.get(i);
                if (n.staff < 0) continue;
                var s = staffs.get(n.staff);
                // Ornament symbols belong above the staff, not in lyrics/dynamics below it.
                float top = ornamentTop(s, tracks.get(n.staff), box.exactCenterX());
                if (box.bottom > top + s.gap() * .1 || box.bottom < top - s.gap() * 5.5) continue;
                float dx = (box.exactCenterX() - n.x) / n.gap,
                        dy = (n.y - box.exactCenterY()) / n.gap;
                boolean turn =
                        match.kind() == NoteOrnament.TURN
                                || match.kind() == NoteOrnament.INVERTED_TURN;
                boolean between = turn && dx > 1 && dx < 4;
                if (Math.abs(dx) > 1.25 && !between || dy < .65 || dy > 10) continue;
                if (between) {
                    boolean later = false;
                    for (var other : notes)
                        if (other.staff == n.staff
                                && other.measure == n.measure
                                && other.x > n.x + n.gap * .5
                                && other.x < box.exactCenterX() + n.gap * .6) later = true;
                    if (later) continue;
                }
                double distance = Math.abs(dx) * 3 + Math.abs(dy) * .3;
                if (distance < best) {
                    best = distance;
                    owner = i;
                    delayed = between;
                }
            }
            if (owner < 0) continue;
            if (box.trillWaveRight > 0 && match.kind() == NoteOrnament.TRILL)
                owner = upperTrillUnison(notes, owner, box);
            var anchor = notes.get(owner);
            int marks = match.kind() | (delayed ? NoteOrnament.DELAYED : 0);
            // Accidentals beside/above/below an ornament modify its auxiliary pitch only.
            for (Bounds other : boxes) {
                if (other == box
                        || Math.abs(other.exactCenterX() - box.exactCenterX()) > anchor.gap * .9)
                    continue;
                boolean upper = other.bottom <= box.top;
                boolean lower = other.top >= box.bottom;
                if (!upper && !lower) continue;
                float distance = upper ? box.top - other.bottom : other.top - box.bottom;
                if (distance > anchor.gap * 1.5 || other.height() > anchor.gap * 2.8) continue;
                var accidental = recognizer.accidental(gray, width, other);
                if (accidental.accepted())
                    marks = NoteOrnament.withAccidental(marks, upper, accidental.kind() - 2);
            }
            // At small print sizes a sharp's stems/crossbars can rasterize as
            // separate components, each too narrow to be a standalone symbol.
            // Verify their combined shape in the bounded auxiliary-sign slot.
            for (int side = 0; side < 2; side++) {
                boolean upper = side == 0;
                if (NoteOrnament.accidental(marks, upper) == ScoreNoteEvent.ACCIDENTAL_FROM_KEY) {
                    float gap = anchor.gap;
                    var staff = staffs.get(anchor.staff);
                    Bounds slot =
                            new Bounds(
                                    Math.max(0, Math.round(box.exactCenterX() - gap * .65f)),
                                    Math.max(
                                            0,
                                            Math.round(
                                                    upper ? box.top - gap * 2.5f : box.bottom + 1)),
                                    Math.min(width, Math.round(box.exactCenterX() + gap * .65f)),
                                    Math.min(
                                            height,
                                            Math.round(
                                                    upper
                                                            ? box.top - 1
                                                            : Math.min(
                                                                    ornamentTop(
                                                                                    staff,
                                                                                    tracks.get(
                                                                                            anchor.staff),
                                                                                    box
                                                                                            .exactCenterX())
                                                                            - gap * .15f,
                                                                    box.bottom + gap * 2.5f))));
                    Bounds ink = inkBounds(gray, width, slot);
                    if (ink == null || ink.height() < gap * .5f || ink.height() > gap * 2.2f)
                        continue;
                    var accidental = recognizer.accidental(gray, width, ink);
                    if (accidental.accepted())
                        marks = NoteOrnament.withAccidental(marks, upper, accidental.kind() - 2);
                }
            }
            boolean duplicate = false;
            for (var prior : found) if (prior.noteIndex() == owner) duplicate = true;
            if (!duplicate) found.add(new Found(box, marks, owner));
        }
        extendTrillUpperVoice(found, notes);
        return List.copyOf(found);
    }

    private static Bounds inkBounds(byte[] gray, int width, Bounds area) {
        int left = area.right, right = area.left, top = area.bottom, bottom = area.top;
        for (int y = area.top; y < area.bottom; y++)
            for (int x = area.left; x < area.right; x++)
                if ((gray[y * width + x] & 255) < 150) {
                    left = Math.min(left, x);
                    right = Math.max(right, x + 1);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y + 1);
                }
        return right <= left ? null : new Bounds(left, top, right, bottom);
    }

    private static List<StaffPitchTrack> ornamentTracks(
            byte[] labels, byte[] gray, int w, int h, List<PlayingTechniqueDetector.Staff> staffs) {
        if (staffs.isEmpty()) return List.of();
        List<StaffPitchTrack> tracks = new ArrayList<>(staffs.size());
        var seeds = RegionalStaffSeeds.detect(labels, gray, w, h);
        for (var staff : staffs) {
            RegionalStaffSeeds.Seed selected = null;
            float best = Float.POSITIVE_INFINITY;
            for (var seed : seeds) {
                if (seed.gap() < staff.gap() * .75f || seed.gap() > staff.gap() * 1.25f) continue;
                float distance =
                        Math.abs((seed.top() + seed.bottom() - staff.top() - staff.bottom()) * .5f);
                if (distance <= staff.gap() * 2 && distance < best) {
                    selected = seed;
                    best = distance;
                }
            }
            StaffPitchTrack track =
                    selected == null
                            ? null
                            : RegionalStaffSeeds.track(labels, gray, w, h, selected);
            if (track == null)
                track =
                        StaffPitchTrack.detectForMeasures(
                                gray, w, h, staff.top(), staff.bottom(), staff.gap());
            tracks.add(track);
        }
        return tracks;
    }

    private static float ornamentTop(
            PlayingTechniqueDetector.Staff staff, StaffPitchTrack track, float x) {
        if (track == null) return staff.top();
        float[] local = track.at(x);
        return local[0] - local[1] * 4;
    }

    static List<Bounds> boxes(
            byte[] gray, int w, int h, List<PlayingTechniqueDetector.Staff> staffs) {
        if (!staffs.isEmpty())
            gray = RestPaperTone.normalizeOrdinaryRestInk(gray, w, h, staffs.get(0).gap(), 220f);
        var tracks = ornamentTracks(null, gray, w, h, staffs);
        return boxes(gray, w, h, staffs, tracks, null);
    }

    private static List<Bounds> boxes(
            byte[] gray,
            int w,
            int h,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<StaffPitchTrack> tracks,
            PortableOrnamentGlyphs recognizer) {
        if (staffs.isEmpty()) return new ArrayList<>();
        List<Bounds> parts = new ArrayList<>();
        new PrintedInkComponents().scan(gray, w, 0, 0, w, h, 149, (l, t, endX, endY, count) -> {
            if (count < 5) return true;
            int r = endX - 1, b = endY - 1;
            int horizontalSpan = r - l, verticalSpan = b - t;
            for (int staffIndex = 0; staffIndex < staffs.size(); staffIndex++) {
                var s = staffs.get(staffIndex);
                float gap = s.gap();
                boolean ordinary = horizontalSpan <= gap * 4.8
                        && verticalSpan <= gap * 2.8
                        && verticalSpan >= gap * .3 && horizontalSpan >= gap * .15;
                if (ordinary) {
                    float top = ornamentTop(s, tracks.get(staffIndex), (l + r) * .5f);
                    if (b <= top + gap * .1 && b >= top - gap * 7) {
                        parts.add(new Bounds(l, t, r + 1, b + 1));
                        break;
                    }
                    continue;
                }
                if (recognizer == null || horizontalSpan < s.gap() * 6
                        || verticalSpan < s.gap() * .8f || verticalSpan > s.gap() * 2.3f) continue;
                float prefixTop = ornamentTop(s, tracks.get(staffIndex), l);
                if (b > prefixTop + s.gap() * .1 || b < prefixTop - s.gap() * 5.5f) continue;
                var head = connectedTrillHead(gray, w, new Bounds(l, t, endX, endY),
                        s.gap(), recognizer);
                if (head != null) {
                    parts.add(head);
                    break;
                }
            }
            return true;
        });
        // The letters in tr may be separate components. Merge only letters on the same
        // baseline; dots and vertically placed auxiliary accidentals remain separate.
        parts.sort(Comparator.comparingInt(r -> r.left));
        Set<Bounds> completeMordents = new HashSet<>();
        if (recognizer != null)
            for (var part : parts)
                if (MordentContour.matches(gray, w, part)) {
                    var match = recognizer.matchWithMordentContour(gray, w, part);
                    if (match.kind() == NoteOrnament.MORDENT && match.accepted())
                        completeMordents.add(part);
                }
        List<Bounds> joined = new ArrayList<>();
        for (var b : parts) {
            Bounds prior = null;
            for (var a : joined)
                if (a.trillWaveRight == 0 && b.trillWaveRight == 0
                        && !completeMordents.contains(a)
                        && !completeMordents.contains(b)
                        && b.left >= a.left
                        && b.left - a.right < Math.min(a.height(), b.height()) * .32
                        && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top)
                                > Math.min(a.height(), b.height()) * .55) prior = a;
            if (prior == null) joined.add(new Bounds(b));
            else {
                prior.trillWaveRight = trillExtensionAfter(gray, w, prior, b, recognizer);
                if (prior.trillWaveRight == 0) prior.union(b);
            }
        }
        return joined;
    }

    private static int upperTrillUnison(List<Anchor> notes, int owner, Bounds mark) {
        var lower = notes.get(owner);
        if (lower.stemDirection >= 0) return owner;
        int upper = -1;
        for (int i = 0; i < notes.size(); i++) {
            var other = notes.get(i);
            if (other.staff == lower.staff && other.measure == lower.measure
                    && other.stemDirection > 0 && Math.abs(other.y - lower.y) <= lower.gap * .35f
                    && Math.abs(other.x - lower.x) <= lower.gap * 1.5f
                    && Math.abs(other.x - mark.exactCenterX()) <= lower.gap * 1.5f) {
                if (upper >= 0) return owner;
                upper = i;
            }
        }
        return upper < 0 ? owner : upper;
    }

    private static void extendTrillUpperVoice(List<Found> found, List<Anchor> notes) {
        if (found.stream().noneMatch(mark -> mark.bounds().trillWaveRight > 0)) return;
        var owned = new BitSet(notes.size());
        for (var mark : found) owned.set(mark.noteIndex());
        int printed = found.size();
        for (int i = 0; i < printed; i++) {
            var mark = found.get(i);
            var lead = notes.get(mark.noteIndex());
            if (mark.bounds().trillWaveRight == 0 || NoteOrnament.type(mark.marks()) != NoteOrnament.TRILL
                    || lead.stemDirection <= 0 || lead.staffStep == Integer.MIN_VALUE) continue;
            float end = mark.bounds().trillWaveRight;
            for (int j = 0; j < notes.size(); j++) {
                var note = notes.get(j);
                if (note.staff == lead.staff && note.stemDirection > 0
                        && note.x > lead.x + lead.gap * .8f && note.x <= end
                        && (owned.get(j) || note.staffStep != lead.staffStep)) end = note.x;
            }
            for (int j = 0; j < notes.size(); j++) {
                var note = notes.get(j);
                if (note.staff == lead.staff && note.stemDirection > 0 && note.staffStep == lead.staffStep
                        && note.x > lead.x + lead.gap * .8f && note.x < end && !owned.get(j)) {
                    found.add(new Found(mark.bounds(), mark.marks(), j));
                    owned.set(j);
                }
            }
        }
    }

    /** Split one connected trill component only when its thin tail really oscillates. */
    private static Bounds connectedTrillHead(byte[] gray, int width, Bounds component, float gap,
            PortableOrnamentGlyphs recognizer) {
        int left = component.left;
        int tailStart = left + Math.round(gap * 3);
        int tailProbeEnd = left + Math.round(gap * 5.6f);
        if (tailProbeEnd >= component.right) return null;
        var tail = inkBounds(gray, width,
                new Bounds(tailStart, component.top, tailProbeEnd, component.bottom));
        if (tail == null || tail.height() < 3 || tail.height() * 2 > component.height()) return null;
        int waveRight = traceTrillWave(gray, width,
                new Bounds(tailStart, tail.top, component.right, tail.bottom), component.right);
        if (waveRight == 0 || waveRight - tailStart < gap * 3) return null;

        Bounds best = null;
        float score = 0;
        int last = left + Math.round(gap * 2.6f);
        for (int cut = left + Math.max(2, Math.round(gap * .95f)); cut <= last;
                cut += Math.max(1, Math.round(gap * .08f))) {
            var head = inkBounds(gray, width, new Bounds(left, component.top, cut, component.bottom));
            if (head == null || tail.height() * 2 > head.height()) continue;
            var match = recognizer.templateMatch(gray, width, head);
            if (match.kind() == NoteOrnament.TRILL && match.accepted() && match.score() > score) {
                best = head;
                score = match.score();
            }
        }
        if (best != null) best.trillWaveRight = waveRight;
        return best;
    }

    /** Keep a proved trill head intact and trace only the directly supported printed wave. */
    private static int trillExtensionAfter(
            byte[] gray, int width, Bounds head, Bounds firstWave,
            PortableOrnamentGlyphs recognizer) {
        int waveHeight = firstWave.height();
        if (recognizer == null || waveHeight < 3 || waveHeight * 2 > head.height()
                || firstWave.width() < waveHeight || head.width() > head.height() * 2.6f
                || firstWave.left < head.right - 1) return 0;
        var match = recognizer.templateMatch(gray, width, head);
        if (match.kind() != NoteOrnament.TRILL || !match.accepted()) return 0;
        return traceTrillWave(gray, width, firstWave, width);
    }

    private static int traceTrillWave(byte[] gray, int width, Bounds firstWave, int rightLimit) {
        int waveHeight = firstWave.height();
        float minimumTravel = Math.max(1.5f, waveHeight * .22f);
        float extremum = Float.NaN;
        int direction = 0, reversals = 0, blank = 0;
        int lastTurn = firstWave.left, right = firstWave.left;
        for (int x = firstWave.left; x < rightLimit && x - lastTurn <= waveHeight * 7; x++) {
            int sum = 0, count = 0;
            for (int y = firstWave.top; y < firstWave.bottom; y++) {
                if ((gray[y * width + x] & 255) < 149) {
                    sum += y;
                    count++;
                }
            }
            if (count == 0) {
                if (++blank > Math.max(1, waveHeight / 2)) break;
                continue;
            }
            blank = 0;
            right = x + 1;
            float center = sum / (float) count;
            if (!Float.isFinite(extremum)) extremum = center;
            float travel = center - extremum;
            if (direction * travel > 0) {
                extremum = center;
            } else if (Math.abs(travel) >= minimumTravel) {
                if (direction != 0) {
                    reversals++;
                    lastTurn = x;
                }
                direction = travel > 0 ? 1 : -1;
                extremum = center;
            }
        }
        return reversals >= 5 ? Math.min(right, lastTurn + waveHeight * 2) : 0;
    }
}
