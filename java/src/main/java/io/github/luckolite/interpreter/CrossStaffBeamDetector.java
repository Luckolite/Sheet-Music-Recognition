package io.github.luckolite.interpreter;

/** Proves an inter-staff beam from raw ink and both attached stems, not x spacing. */
final class CrossStaffBeamDetector {
    private CrossStaffBeamDetector() {}

    record Head(float x, float y, float gap) {}

    /** Physical beam ownership is independent of rests printed in another voice. */
    static java.util.List<ScoreNoteEvent> mark(
            java.util.List<ScoreNoteEvent> notes,
            java.util.List<Head> heads,
            byte[] gray,
            int width,
            int height) {
        if (notes.size() != heads.size()) throw new IllegalArgumentException("Head/event mismatch");
        var result = new java.util.ArrayList<>(notes);
        for (int i = 1; i < notes.size(); i++) {
            int prior = i - 1;
            while (prior >= 0 && hasProvedUnbeamedValue(notes.get(prior))) prior--;
            if (prior < 0) continue;
            var a = notes.get(prior);
            var b = notes.get(i);
            if (a.measureIndex() != b.measureIndex()
                    || a.staffCount() != 2
                    || b.staffCount() != 2
                    || a.staffIndex() == b.staffIndex()
                    || hasProvedUnbeamedValue(b)) continue;
            var first = heads.get(prior);
            var second = heads.get(i);
            if (connected(
                    gray,
                    width,
                    height,
                    first.x,
                    first.y,
                    second.x,
                    second.y,
                    (first.gap + second.gap) / 2)) {
                result.set(prior, result.get(prior).withCrossStaffBeam());
                result.set(i, result.get(i).withCrossStaffBeam());
            }
        }
        return markOpposed(result, heads, gray, width, height);
    }

    /** Additive opposed-pair proof requires the original heads' printed stem metadata. */
    static java.util.List<ScoreNoteEvent> markOpposed(
            java.util.List<ScoreNoteEvent> notes,
            java.util.List<Head> heads,
            byte[] gray,
            int width,
            int height) {
        if (notes.size() != heads.size()) throw new IllegalArgumentException("Head/event mismatch");
        var result = new java.util.ArrayList<>(notes);
        // Other voices may interleave between the two literal owners of one beam.
        // Require unique opposed-stem partners rather than treating list adjacency as a voice.
        int[] partner = new int[notes.size()];
        java.util.Arrays.fill(partner, -1);
        boolean[] ambiguous = new boolean[notes.size()];
        var byMeasure = new java.util.LinkedHashMap<Integer, java.util.List<Integer>>();
        for (int i = 0; i < notes.size(); i++)
            if (opposedSingleBeamValue(notes.get(i)))
                byMeasure
                        .computeIfAbsent(
                                notes.get(i).measureIndex(), ignored -> new java.util.ArrayList<>())
                        .add(i);
        int remainingCalls = 4096; // Optional proof refuses excessive work; legacy results survive.
        for (var owners : byMeasure.values())
            for (int left = 0; left < owners.size(); left++) {
                int i = owners.get(left);
                for (int right = left + 1; right < owners.size(); right++) {
                    int j = owners.get(right);
                    var a = notes.get(i);
                    var b = notes.get(j);
                    if (!opposedSingleBeamPair(a, b)) continue;
                    var first = heads.get(i);
                    var second = heads.get(j);
                    float gap = (first.gap + second.gap) / 2;
                    if (!Float.isFinite(gap + first.x + first.y + second.x + second.y)
                            || gap < 2
                            || second.x <= first.x
                            || second.x - first.x < gap * 1.5f
                            || second.x - first.x > gap * 9
                            || Math.abs(second.y - first.y) < gap * 4) continue;
                    int direction = first.y > second.y ? 1 : -1;
                    if (a.stemDirection() != direction || b.stemDirection() != -direction) continue;
                    if (--remainingCalls < 0) return result;
                    if (!connected(gray, width, height, first.x, first.y, second.x, second.y, gap))
                        continue;
                    if (partner[i] >= 0) ambiguous[i] = true;
                    if (partner[j] >= 0) ambiguous[j] = true;
                    partner[i] = j;
                    partner[j] = i;
                }
            }
        for (int i = 0; i < notes.size(); i++) {
            int j = partner[i];
            if (j >= 0 && !ambiguous[i] && !ambiguous[j] && partner[j] == i)
                result.set(i, result.get(i).withCrossStaffBeam());
        }
        return result;
    }

    /** Supported literal opposed-stem values; this does not infer a connection. */
    static boolean opposedSingleBeamPair(ScoreNoteEvent a, ScoreNoteEvent b) {
        return opposedSingleBeamValue(a)
                && opposedSingleBeamValue(b)
                && a.measureIndex() == b.measureIndex()
                && a.staffCount() == 2
                && b.staffCount() == 2
                && a.staffIndex() != b.staffIndex()
                && a.kind() == ScoreNoteEvent.Kind.PITCHED
                && b.kind() == ScoreNoteEvent.Kind.PITCHED
                && a.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && b.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && a.beamCount() == 1
                && b.beamCount() == 1
                && a.unbeamedDurationBeats() == 0
                && b.unbeamedDurationBeats() == 0
                && a.augmentationDots() >= 0
                && a.augmentationDots() <= 2
                && a.augmentationDots() == b.augmentationDots()
                && a.tupletDivisor() == b.tupletDivisor()
                && a.tupletNormalNotes() == b.tupletNormalNotes()
                && a.stemDirection() != 0
                && a.stemDirection() == -b.stemDirection();
    }

    static boolean opposedSingleBeamValue(ScoreNoteEvent note) {
        return note != null
                && note.measureIndex() >= 0
                && note.staffCount() == 2
                && (note.articulations() & NoteOrnament.GRACE) == 0
                && note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && note.beamCount() == 1
                && note.unbeamedDurationBeats() == 0
                && note.augmentationDots() >= 0
                && note.augmentationDots() <= 2
                && note.stemDirection() != 0;
    }

    private static boolean hasProvedUnbeamedValue(ScoreNoteEvent note) {
        return note.beamCount() == 0 && note.unbeamedDurationBeats() >= 1;
    }

    static boolean connected(
            byte[] gray, int width, int height, float x1, float y1, float x2, float y2, float gap) {
        if (gray == null
                || gap < 2
                || x2 <= x1
                || Math.abs(y2 - y1) < gap * 4
                || x2 - x1 < gap * 1.5
                || x2 - x1 > gap * 9) return false;
        // Up-stems attach to the right of a head; down-stems to the left.
        boolean firstUp = y1 > y2;
        int stem1 = Math.round(x1 + (firstUp ? .6f : -.6f) * gap);
        int stem2 = Math.round(x2 + (firstUp ? -.6f : .6f) * gap);
        if (stem2 - stem1 < gap) return false;
        int top = Math.max(1, Math.round(Math.min(y1, y2) + gap * 2));
        int bottom = Math.min(height - 2, Math.round(Math.max(y1, y2) - gap * 2));
        if (connectedStems(gray, width, height, stem1, y1, stem2, y2, top, bottom, gap))
            return true;
        // A hand can cross into the neighbouring staff while both shafts remain
        // above their heads (or below both heads). Its beam is outside the head
        // interval, so the opposed-stem search cannot establish that connection.
        return sameDirection(gray, width, height, x1, y1, x2, y2, gap, true)
                || sameDirection(gray, width, height, x1, y1, x2, y2, gap, false);
    }

    private static boolean sameDirection(
            byte[] gray,
            int width,
            int height,
            float x1,
            float y1,
            float x2,
            float y2,
            float gap,
            boolean up) {
        if (Math.abs(y2 - y1) > gap * 24) return false;
        int stem1 = Math.round(x1 + (up ? .6f : -.6f) * gap);
        int stem2 = Math.round(x2 + (up ? .6f : -.6f) * gap);
        int top =
                Math.max(
                        1,
                        Math.round(
                                up ? Math.min(y1, y2) - gap * 6 : Math.max(y1, y2) + gap * 1.5f));
        int bottom =
                Math.min(
                        height - 2,
                        Math.round(
                                up ? Math.min(y1, y2) - gap * 1.5f : Math.max(y1, y2) + gap * 6));
        return connectedStems(gray, width, height, stem1, y1, stem2, y2, top, bottom, gap);
    }

    private static boolean connectedStems(
            byte[] gray, int width, int height, int stem1, float y1, int stem2, float y2,
            int top, int bottom, float gap) {
        int total = stem2 - stem1 - 5;
        if (top > bottom || total <= 0) return false;
        int verticalTolerance = (int) (gap * 1.8);
        boolean[] firstStem = shaftEnds(gray, width, height, stem1, y1, top, bottom, gap);
        boolean[] secondStem = null;
        for (int a = top; a <= bottom; a++) {
            if (!firstStem[a - top]) continue;
            if (secondStem == null)
                secondStem = shaftEnds(gray, width, height, stem2, y2, top, bottom, gap);
            for (int b = Math.max(top, a - verticalTolerance);
                    b <= Math.min(bottom, a + verticalTolerance);
                    b++) {
                if (!secondStem[b - top]) continue;
                if (hasBeamInk(gray, width, height, stem1, a, stem2, b, total)
                        && BeamInkConnectivity.connected(
                                gray, width, height, stem1, a, stem2, b, gap)) return true;
            }
        }
        return false;
    }

    private static boolean hasBeamInk(
            byte[] gray, int width, int height, int stem1, int a, int stem2, int b, int total) {
        double minimumHits = total * .93, minimumThick = total * .45;
        int hits = 0, thick = 0;
        for (int x = stem1 + 3; x < stem2 - 2; x++) {
            int y = Math.round(a + (b - a) * (x - stem1) / (float) (stem2 - stem1));
            if (dark(gray, width, height, x, y, 1)) hits++;
            if (dark(gray, width, height, x, y - 1, 0)
                    && dark(gray, width, height, x, y + 1, 0)) thick++;
            if (hits >= minimumHits && thick >= minimumThick) return true;
            int remaining = stem2 - 3 - x;
            if (hits + remaining < minimumHits || thick + remaining < minimumThick) return false;
        }
        return false;
    }

    private static boolean[] shaftEnds(
            byte[] gray, int w, int h, int x, float headY, int top, int bottom, float gap) {
        boolean[] ends = new boolean[bottom - top + 1];
        int blankLimit = Math.max(1, Math.round(gap * .16f));
        double minimumLength = gap * 1.4;
        int left = Math.max(0, x - 2), right = Math.min(w - 1, x + 2);
        if (left > right) return ends;

        // Every possible endpoint in one direction shares the same shaft prefix.
        for (int direction = -1; direction <= 1; direction += 2) {
            int start = Math.round(headY + direction * gap * .6f);
            int limit = direction < 0 ? top : bottom;
            if ((limit > headY) != (direction > 0)) continue;
            int hits = 0, blanks = 0, length = 0;
            for (int y = start; direction < 0 ? y >= limit : y <= limit; y += direction) {
                if (y >= top && y <= bottom && (y > headY) == (direction > 0))
                    ends[y - top] = length >= minimumLength && hits >= length * .92;
                if (y == limit) break;
                length++;
                if (darkRow(gray, w, h, left, right, y)) {
                    hits++;
                    blanks = 0;
                } else if (++blanks > blankLimit) break;
            }
        }
        return ends;
    }

    private static boolean dark(byte[] gray, int w, int h, int x, int y, int radius) {
        return darkRow(gray, w, h, Math.max(0, x - radius), Math.min(w - 1, x + radius), y);
    }

    private static boolean darkRow(byte[] gray, int w, int h, int left, int right, int y) {
        if (y < 0 || y >= h) return false;
        int row = y * w;
        for (int k = left; k <= right; k++)
            if ((gray[row + k] & 255) < 150) return true;
        return false;
    }
}
