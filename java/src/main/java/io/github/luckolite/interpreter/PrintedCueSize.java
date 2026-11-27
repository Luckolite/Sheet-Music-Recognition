// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;

/** Optical cue prefixes require paired printed instructions and a proved head-size transition. */
public final class PrintedCueSize {
    public static final String SOURCE = "printed-cue-instruction";
    private static final String OPENING_RANGE = "printed-opening-cue:v1";
    private static final Set<String> INSTRUMENTS =
            Set.of(
                    "guitar",
                    "piano",
                    "violin",
                    "viola",
                    "cello",
                    "flute",
                    "oboe",
                    "clarinet",
                    "bassoon",
                    "trumpet",
                    "trombone",
                    "horn",
                    "bass",
                    "harp",
                    "organ",
                    "saxophone");

    public record Passage(
            List<Integer> notes,
            int playOwner,
            PlayingTechniqueDetector.Word cue,
            PlayingTechniqueDetector.Word play,
            boolean extendedOpening,
            ScoreRestEvent captionRest) {
        public Passage(List<Integer> notes, int playOwner, PlayingTechniqueDetector.Word cue,
                PlayingTechniqueDetector.Word play) {
            this(notes, playOwner, cue, play, false, null);
        }

        public Passage(List<Integer> notes, int playOwner, PlayingTechniqueDetector.Word cue,
                PlayingTechniqueDetector.Word play, boolean extendedOpening) {
            this(notes, playOwner, cue, play, extendedOpening, null);
        }

        public Passage {
            notes = List.copyOf(notes);
        }
    }

    private record Head(int index, ScoreNoteEvent note, float x) {}
    private record PlayInstruction(PlayingTechniqueDetector.Word word, PlayingTechniqueDetector.Staff staff) {}

    private PrintedCueSize() {}

    private static String token(String text) {
        return text == null ? "" : text.strip().toLowerCase(Locale.ROOT).replaceAll("[.:]+$", "");
    }

    /** Measured head core; spanning staff rails and narrow shafts cannot supply its width. */
    static OptionalDouble width(
            ScoreNoteEvent n, MeasureRegion region, float gap, byte[] gray, int width, int height) {
        if (!Float.isFinite(gap)
                || gap < 6
                || gap > Math.min(width, height) / 8f
                || !Float.isFinite(n.pageY())
                || n.pageY() <= 0
                || n.pageY() >= 1
                || !Float.isFinite(n.positionInMeasure())
                || n.positionInMeasure() < 0
                || n.positionInMeasure() > 1) return OptionalDouble.empty();
        int x =
                Math.round(
                        (region.left() + n.positionInMeasure() * (region.right() - region.left()))
                                * width);
        int y = Math.round(n.pageY() * height), radius = Math.round(gap * .4f);
        int cap = (int) Math.ceil(gap * 2.25f);
        if (x - cap < 0 || x + cap >= width || y - radius < 0 || y + radius >= height)
            return OptionalDouble.empty();
        var spans = new ArrayList<Integer>();
        for (int row = y - radius; row <= y + radius; row++) {
            if ((gray[row * width + x] & 255) >= 165) continue;
            int left = x, right = x;
            while (left > x - cap && (gray[row * width + left - 1] & 255) < 165) left--;
            while (right < x + cap && (gray[row * width + right + 1] & 255) < 165) right++;
            int span = right - left + 1;
            if (left == x - cap || right == x + cap || span < gap * .45f || span > gap * 2.1f)
                continue;
            spans.add(span);
        }
        if (spans.size() < Math.ceil(gap * .3f)) return OptionalDouble.empty();
        Collections.sort(spans);
        return OptionalDouble.of(spans.get((spans.size() - 1) * 3 / 4));
    }

    static List<Passage> detect(
            ScorePageInterpretation score,
            List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs,
            byte[] gray,
            int width,
            int height) {
        if (gray == null || width <= 0 || height <= 0 || gray.length != (long) width * height)
            return List.of();
        var candidates = new ArrayList<Passage>();
        for (var cue : new LinkedHashSet<>(words)) {
            if (!INSTRUMENTS.contains(token(cue.text()))) continue;
            var staff = directionStaff(score, staffs, gray, width, height, cue);
            if (staff == null) continue;
            var plays = new ArrayList<PlayInstruction>();
            for (var play : new LinkedHashSet<>(words)) {
                if (!token(play.text()).equals("play")
                        || play.left() <= cue.right()) continue;
                var owner = directionStaff(score, staffs, gray, width, height, play);
                if (owner != null
                        && staff.index() == owner.index()
                        && staff.count() == owner.count()
                        && Math.abs(staff.top() + staff.bottom() - owner.top() - owner.bottom())
                                <= Math.min(staff.gap(), owner.gap()) * 4
                        && Math.abs(staff.gap() - owner.gap()) <= staff.gap() * .15f)
                    plays.add(new PlayInstruction(play, owner));
            }
            if (plays.size() > 1 && plays.stream().anyMatch(p ->
                    (p.word().left() - cue.left()) * width <= staff.gap() * 24))
                plays.removeIf(p -> (p.word().left() - cue.left()) * width > staff.gap() * 24);
            if (plays.size() != 1) continue;
            var play = plays.get(0).word();
            var heads = new ArrayList<Head>();
            float center = (staff.top() + staff.bottom()) * .5f / height;
            for (int i = 0; i < score.notes().size(); i++) {
                var n = score.notes().get(i);
                if (n.staffIndex() != staff.index()
                        || n.staffCount() != staff.count()
                        || n.crossStaffBeam()
                        || n.measureIndex() < 0
                        || n.measureIndex() >= score.measures().size()) continue;
                var region = score.measures().get(n.measureIndex());
                if (center < region.top() - staff.gap() / height
                        || center > region.bottom() + staff.gap() / height) continue;
                float x =
                        (region.left() + n.positionInMeasure() * (region.right() - region.left()))
                                * width;
                heads.add(new Head(i, n, x));
            }
            heads.sort(Comparator.comparingDouble(Head::x));
            Head first = null, principal = null;
            for (var h : heads) {
                if (first == null && h.x() >= cue.left() * width - staff.gap() * .5f) first = h;
                if (principal == null && h.x() >= play.left() * width - staff.gap() * .5f)
                    principal = h;
            }
            boolean distant = (play.left() - cue.left()) * width > staff.gap() * 24;
            if (first == null
                    || principal == null
                    || first.x() >= principal.x()
                    || first.x() - cue.left() * width > staff.gap() * 5
                    || principal.x() - play.left() * width > staff.gap() * 5
                    || !ordinary(principal.note())
                    || distant && !openingCueBoundary(
                            score, first, principal, plays.get(0).staff(), gray, width, height)) continue;
            if (distant) {
                var leadingRests = restsBeforeEntrance(score, principal, staff);
                var captionRest = leadingRests.size() == 1
                        && playOwnsRest(leadingRests.get(0), score, play, plays.get(0).staff(), height)
                        ? leadingRests.get(0) : null;
                if (leadingRests.isEmpty() || captionRest != null) {
                    var direct = directOpeningHeads(score, heads, first, principal, staff.gap(),
                            gray, width, height, captionRest);
                    if (!direct.isEmpty())
                        candidates.add(new Passage(direct, principal.index(), cue, play, true, captionRest));
                    continue;
                }
            }
            var normal =
                    width(
                            principal.note(),
                            score.measures().get(principal.note().measureIndex()),
                            staff.gap(),
                            gray,
                            width,
                            height);
            if (normal.isEmpty()
                    || normal.getAsDouble() < staff.gap() * 1.1
                    || normal.getAsDouble() > staff.gap() * 1.8) continue;
            boolean valid = true;
            var small = new ArrayList<Integer>();
            int previousMeasure = -1;
            for (var h : heads) {
                if (h.x() < first.x() || h.x() >= principal.x()) continue;
                var measured =
                        width(
                                h.note(),
                                score.measures().get(h.note().measureIndex()),
                                staff.gap(),
                                gray,
                                width,
                                height);
                if (!ordinary(h.note())
                        || h.note().measureIndex() < previousMeasure
                        || measured.isEmpty()
                        || measured.getAsDouble() < staff.gap() * .5
                        || measured.getAsDouble() > staff.gap() * 1.1
                        || measured.getAsDouble() > normal.getAsDouble() * .8) {
                    valid = false;
                    break;
                }
                previousMeasure = h.note().measureIndex();
                small.add(h.index());
            }
            if (valid && !small.isEmpty())
                candidates.add(new Passage(small, principal.index(), cue, play, distant));
        }
        // Conflicting printed scopes cannot silently overwrite one another.
        var use = new HashMap<Integer, Integer>();
        for (var p : candidates) for (int i : p.notes()) use.merge(i, 1, Integer::sum);
        return candidates.stream()
                .filter(p -> p.notes().stream().allMatch(i -> use.get(i) == 1))
                .toList();
    }

    private static PlayingTechniqueDetector.Staff directionStaff(
            ScorePageInterpretation score, List<PlayingTechniqueDetector.Staff> staffs,
            byte[] gray, int width, int height, PlayingTechniqueDetector.Word word) {
        float x = word.left() * width, top = word.top() * height, bottom = word.bottom() * height;
        var owner = PrintedDirectionStaff.at(
                staffs, score.measures(), score.notes(), gray, width, height, x, top, bottom);
        if (owner != null) return owner;
        int left = Math.round(x), right = Math.round(word.right() * width);
        if (left < 0 || right >= width || right <= left || !(top >= 0 && bottom >= top && bottom < height))
            return null;
        for (var staff : staffs) {
            var local = PrintedDirectionStaff.local(staff, score.measures(), score.notes(),
                    gray, width, height, x);
            if (local == null || bottom < local.top() - local.gap() * .25f
                    || bottom > local.top() + local.gap() * .35f
                    || top >= local.top() - local.gap()
                    || right - left > local.gap() * 8f) continue;
            // Ignore rows of the upper rail caught by a loose OCR box. Isolated dark
            // pixels beside it still count as word ink and prevent the fallback.
            int firstInk = -1, lastInk = -1;
            for (int y = Math.round(top); y <= Math.min(height - 1, Math.round(bottom)); y++) {
                int ink = 0;
                for (int i = left; i <= right; i++)
                    if ((gray[y * width + i] & 255) < 160) ink++;
                if (ink < 2 || Math.abs(y - local.top()) <= local.gap() * .15f
                        && ink >= (right - left + 1) * .85f) continue;
                if (firstInk < 0) firstInk = y;
                lastInk = y;
            }
            if (firstInk < 0 || lastInk - firstInk < local.gap() * .7f
                    || local.top() - lastInk < local.gap() * .25f) continue;
            if (owner != null) return null;
            owner = local;
        }
        return owner;
    }

    private static List<ScoreRestEvent> restsBeforeEntrance(
            ScorePageInterpretation score, Head principal, PlayingTechniqueDetector.Staff staff) {
        return score.rests().stream().filter(r ->
                r.measureIndex() == principal.note().measureIndex()
                        && r.staffIndex() == staff.index() && r.staffCount() == staff.count()
                        && r.durationBeats() > 0
                        && r.positionInMeasure() < principal.note().positionInMeasure()).toList();
    }

    /** The literal caption owns a mistaken short rest only when it encloses the entire mark. */
    private static boolean playOwnsRest(ScoreRestEvent rest, ScorePageInterpretation score,
            PlayingTechniqueDetector.Word play, PlayingTechniqueDetector.Staff staff, int height) {
        if (rest.kind() != ScoreRestEvent.Kind.LITERAL || rest.durationBeats() != .5
                || !(rest.pageHeight() > 0)) return false;
        var region = score.measures().get(rest.measureIndex());
        float x = region.left() + rest.positionInMeasure() * (region.right() - region.left());
        float top = rest.pageY() - rest.pageHeight() * .5f;
        float bottom = rest.pageY() + rest.pageHeight() * .5f;
        return play.left() < x && x < play.right() && play.top() <= top
                && bottom <= play.bottom() && bottom * height <= staff.top() - staff.gap() * .5f;
    }

    /** Filled heads give comparable size evidence even when the entrance head is hollow. */
    private static List<Integer> directOpeningHeads(
            ScorePageInterpretation score, List<Head> heads, Head first, Head principal,
            float gap, byte[] gray, int width, int height, ScoreRestEvent captionRest) {
        int entrance = principal.note().measureIndex();
        float leadingRest = principal.note().leadingRestBeats();
        if (entrance < 3 || first != heads.get(0) || principal.note().positionInMeasure() > .25f
                || leadingRest != 0 && (captionRest == null || leadingRest != captionRest.durationBeats()))
            return List.of();

        double principalWidth = Double.POSITIVE_INFINITY;
        int principalFilled = 0;
        for (var h : heads) {
            if (h.note().measureIndex() != entrance || h.x() < principal.x()
                    || !ordinary(h.note()) || !filledHead(h.note())) continue;
            var measured = width(h.note(), score.measures().get(entrance), gap, gray, width, height);
            if (measured.isEmpty() || measured.getAsDouble() < gap * 1.1
                    || measured.getAsDouble() > gap * 1.8) return List.of();
            principalWidth = Math.min(principalWidth, measured.getAsDouble());
            principalFilled++;
        }
        if (principalFilled < 2) return List.of();

        var small = new ArrayList<Integer>();
        var measuresWithFilledHeads = new BitSet();
        int filled = 0, previousMeasure = -1;
        for (var h : heads) {
            if (h.x() >= principal.x()) break;
            int measure = h.note().measureIndex();
            if (!ordinary(h.note()) || measure < previousMeasure || measure >= entrance)
                return List.of();
            if (filledHead(h.note())) {
                var measured = width(h.note(), score.measures().get(measure), gap, gray, width, height);
                if (measured.isEmpty() || measured.getAsDouble() < gap * .5
                        || measured.getAsDouble() > gap * 1.15
                        || measured.getAsDouble() > principalWidth * .82) return List.of();
                measuresWithFilledHeads.set(measure);
                filled++;
            }
            previousMeasure = measure;
            small.add(h.index());
        }
        return filled >= 3 && measuresWithFilledHeads.cardinality() >= 2 ? small : List.of();
    }

    private static boolean filledHead(ScoreNoteEvent note) {
        return note.beamCount() > 0 || note.unbeamedDurationBeats() < 2;
    }

    /** An extended cue needs an opening prefix and a printed break before the principal part. */
    private static boolean openingCueBoundary(
            ScorePageInterpretation score, Head first, Head principal,
            PlayingTechniqueDetector.Staff staff, byte[] gray, int width, int height) {
        int entrance = principal.note().measureIndex();
        if (first.note().measureIndex() != 0 || entrance < 2 || principal.note().tiedFromPrevious())
            return false;
        var before = score.measures().get(entrance - 1);
        var after = score.measures().get(entrance);
        float gap = staff.gap();
        if (before.right() > after.left()
                || (after.left() - before.right()) * width > gap * 1.5f
                || Math.abs(after.top() - before.top()) * height > gap) return false;
        int boundary = Math.round((before.right() + after.left()) * .5f * width);
        var rules = PrintedDirectionStaff.printedRules(gray, width, height, boundary, staff.bottom(), gap);
        if (rules == null) return false;
        int radius = Math.round(gap * .9f);
        int top = Math.round(rules[0] - rules[1] * 4), bottom = Math.round(rules[0]);
        int previous = -1;
        boolean withinRule = false;
        for (int x = Math.max(0, boundary - radius); x <= Math.min(width - 1, boundary + radius); x++) {
            int thick = 0;
            for (int y = top; y <= bottom; y++)
                if ((gray[y * width + x] & 255) < 160) thick++;
            boolean line = thick >= (bottom - top + 1) * .78f;
            if (line && !withinRule && previous >= 0
                    && x - previous >= gap * .23f && x - previous <= gap * .68f) return true;
            if (line && !withinRule) previous = x;
            withinRule = line;
        }
        return false;
    }

    private static boolean ordinary(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED
                && (n.articulations() & NoteOrnament.GRACE) == 0
                && ScoreNoteTiming.writtenDurationBeats(n) > 0;
    }

    static ScorePageInterpretation applySizes(
            ScorePageInterpretation score, List<Passage> passages) {
        if (passages.isEmpty()) return score;
        var notes = new ArrayList<>(score.notes());
        var rests = new ArrayList<>(score.rests());
        for (var p : passages) {
            for (int i : p.notes())
                notes.set(i, notes.get(i).withVisualSize(ScoreNoteEvent.VisualSize.CUE));
            if (p.captionRest() != null) {
                rests.remove(p.captionRest());
                var entrance = notes.get(p.playOwner());
                notes.set(p.playOwner(), entrance.withLeadingRest(0));
            }
        }
        var edited = score.withNotes(
                notes, java.util.stream.IntStream.range(0, notes.size()).boxed().toList());
        return rests.size() != score.rests().size() ? edited.withRests(rests) : edited;
    }

    /** Retain printed words on exact source columns; musical clocks are resolved separately. */
    static ScorePageInterpretation apply(ScorePageInterpretation score, List<Passage> passages) {
        var sized = applySizes(score, passages);
        var events = new ArrayList<>(sized.expressiveEvents());
        for (var p : passages) {
            var first = score.notes().get(p.notes().get(0));
            var entrance = score.notes().get(p.playOwner());
            addCaption(events, first, p.cue());
            addCaption(events, entrance, p.play());
            if (p.extendedOpening()) addOpeningRange(events, first, entrance, p.cue(), p.play());
        }
        return sized.withExpressiveEvents(events);
    }

    /** Final guide assembly runs after analysis, so visual metadata never enters audio timing. */
    static ScorePageInterpretation apply(
            ScorePageInterpretation score,
            List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs,
            byte[] gray,
            int width,
            int height) {
        var passages = detect(score, words, staffs, gray, width, height);
        if (passages.isEmpty()) return score;
        return ScoreExpressionDetector.resolve(apply(score, passages), Float.NaN);
    }

    private static void addCaption(
            List<ScoreExpressiveEvent> events,
            ScoreNoteEvent n,
            PlayingTechniqueDetector.Word word) {
        String column =
                n.measureIndex()
                        + ":"
                        + n.staffIndex()
                        + ":"
                        + n.staffCount()
                        + ":"
                        + Float.floatToIntBits(
                                n.positionInMeasure() == 0 ? 0 : n.positionInMeasure());
        String id = "printed-cue:" + column + ":word:" + word.text().strip();
        if (events.stream().anyMatch(e -> e.eventId().equals(id))) return;
        events.add(
                new ScoreExpressiveEvent(
                        id,
                        ScoreExpressiveEvent.Kind.UNRESOLVED_DIRECTION,
                        Optional.empty(),
                        Optional.empty(),
                        ScoreExpressiveEvent.Scope.UNRESOLVED,
                        n.staffIndex(),
                        n.staffCount(),
                        Optional.of("expression-column:" + column),
                        ScoreExpressiveEvent.Strength.UNSPECIFIED,
                        "",
                        List.of(
                                new ScoreExpressiveEvent.Evidence(
                                        SOURCE,
                                        0,
                                        word.left(),
                                        n.staffIndex(),
                                        n.staffCount(),
                                        word.text().strip()))));
    }

    private static void addOpeningRange(
            List<ScoreExpressiveEvent> events, ScoreNoteEvent first, ScoreNoteEvent entrance,
            PlayingTechniqueDetector.Word cue, PlayingTechniqueDetector.Word play) {
        String id = OPENING_RANGE + ":" + first.staffIndex() + ":" + entrance.measureIndex();
        if (events.stream().anyMatch(e -> e.eventId().equals(id))) return;
        events.add(new ScoreExpressiveEvent(id, ScoreExpressiveEvent.Kind.UNRESOLVED_DIRECTION,
                Optional.of(new ScoreAnchor(first.measureIndex(), 0)),
                Optional.of(new ScoreAnchor(entrance.measureIndex(), 0)),
                ScoreExpressiveEvent.Scope.UNRESOLVED, first.staffIndex(), first.staffCount(),
                Optional.empty(), ScoreExpressiveEvent.Strength.UNSPECIFIED, "",
                List.of(rangeEvidence(first, cue), rangeEvidence(first, play))));
    }

    private static ScoreExpressiveEvent.Evidence rangeEvidence(
            ScoreNoteEvent note, PlayingTechniqueDetector.Word word) {
        return new ScoreExpressiveEvent.Evidence(OPENING_RANGE, 0, word.left(),
                note.staffIndex(), note.staffCount(), word.text().strip());
    }

    /** Resolve only fully cue-owned lanes in a proved printed opening span. */
    public static BitSet mutedOpeningNotes(
            List<ScoreNoteEvent> notes, List<ScoreExpressiveEvent> events, int measures) {
        var result = new BitSet();
        for (var event : events) {
            var evidence = event.evidence();
            if (event.kind() != ScoreExpressiveEvent.Kind.UNRESOLVED_DIRECTION
                    || !event.eventId().startsWith(OPENING_RANGE + ":")
                    || evidence.size() != 2
                    || evidence.stream().anyMatch(e -> !e.sourceId().equals(OPENING_RANGE)
                            || e.staffIndex() != event.staffIndex() || e.staffCount() != event.staffCount())
                    || !INSTRUMENTS.contains(token(evidence.get(0).printedText()))
                    || !token(evidence.get(1).printedText()).equals("play")
                    || evidence.get(0).visualX() >= evidence.get(1).visualX()
                    || event.start().isEmpty() || event.end().isEmpty()) continue;
            var start = event.start().get();
            var end = event.end().get();
            if (start.quarterBeatOffset() != 0 || end.quarterBeatOffset() != 0
                    || end.measureIndex() - start.measureIndex() < 2 || end.measureIndex() >= measures)
                continue;
            var owned = new BitSet();
            boolean entrance = false;
            boolean conflict = false;
            for (int i = 0; i < notes.size(); i++) {
                var note = notes.get(i);
                if (note.staffIndex() != event.staffIndex() || note.staffCount() != event.staffCount()
                        || note.measureIndex() < start.measureIndex()
                        || note.measureIndex() > end.measureIndex()) continue;
                if (note.measureIndex() == end.measureIndex()) {
                    entrance |= ordinary(note) && !note.tiedFromPrevious()
                            && note.visualSize() == ScoreNoteEvent.VisualSize.FULL;
                } else if (ordinary(note) && note.visualSize() == ScoreNoteEvent.VisualSize.CUE) {
                    owned.set(i);
                } else {
                    conflict = true;
                    break;
                }
            }
            if (entrance && !conflict) result.or(owned);
        }
        return result;
    }

    public static boolean owns(ScoreExpressiveEvent event) {
        if (event.kind() != ScoreExpressiveEvent.Kind.UNRESOLVED_DIRECTION
                || !event.qualifierText().isEmpty()
                || event.evidence().size() != 1
                || event.targetEventId().isEmpty()
                || !event.targetEventId().get().startsWith("expression-column:")) return false;
        var e = event.evidence().get(0);
        return e.sourceId().equals(SOURCE)
                && e.staffIndex() == event.staffIndex()
                && e.staffCount() == event.staffCount()
                && (INSTRUMENTS.contains(token(e.printedText()))
                        || token(e.printedText()).equals("play"));
    }
}
