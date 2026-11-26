// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Anchors explicit navigation words and already-verified glyphs to printed bar boundaries. */
final class ScoreNavigationDetector {
    private static final Pattern NAVIGATION_SEPARATORS = Pattern.compile("[\\s.,:;]");
    private static final Pattern TRAILING_CODA_ARTIFACT = Pattern.compile("\\s*(?:[1-9][0-9]*\\.|[-–—φΦ𝄌])$");

    record Glyph(ScorePlaybackDirection.Kind kind, float centerX, float top, float bottom) {}

    private record Phrase(
            ScorePlaybackDirection.Kind kind, float left, float right, float top, float bottom) {
        Glyph glyph() {
            return new Glyph(kind, (left + right) * .5f, top, bottom);
        }
    }

    private ScoreNavigationDetector() {}

    static ScorePlaybackDirection.Kind kind(String text) {
        if (text == null) return null;
        if (text.trim().equals("𝄋")) return ScorePlaybackDirection.Kind.SEGNO;
        if (text.trim().equals("𝄌")) return ScorePlaybackDirection.Kind.CODA;
        String clean = NAVIGATION_SEPARATORS.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
        return switch (clean) {
            case "dsalcoda", "dalsegnoalcoda" -> ScorePlaybackDirection.Kind.DAL_SEGNO_AL_CODA;
            case "dc", "dacapo" -> ScorePlaybackDirection.Kind.DA_CAPO;
            case "ds", "dalsegno" -> ScorePlaybackDirection.Kind.DAL_SEGNO;
            case "dcalfine", "dacapoalfine" -> ScorePlaybackDirection.Kind.DA_CAPO_AL_FINE;
            case "dsalfine", "dalsegnoalfine" -> ScorePlaybackDirection.Kind.DAL_SEGNO_AL_FINE;
            case "dcalcoda", "dacapoalcoda" -> ScorePlaybackDirection.Kind.DA_CAPO_AL_CODA;
            case "fine" -> ScorePlaybackDirection.Kind.FINE;
            case "tocoda" -> ScorePlaybackDirection.Kind.TO_CODA;
            case "coda" -> ScorePlaybackDirection.Kind.CODA;
            case "segno" -> ScorePlaybackDirection.Kind.SEGNO;
            default -> null;
        };
    }

    private static boolean attachedCodaSymbol(
            Glyph mark, Phrase phrase, List<MeasureRegion> measures, int width, int height) {
        if (phrase.kind() != ScorePlaybackDirection.Kind.CODA
                && phrase.kind() != ScorePlaybackDirection.Kind.TO_CODA
                && phrase.kind() != ScorePlaybackDirection.Kind.DAL_SEGNO_AL_CODA
                && phrase.kind() != ScorePlaybackDirection.Kind.DA_CAPO_AL_CODA) return false;
        float lineHeight =
                Math.max(phrase.bottom() - phrase.top(), mark.bottom() - mark.top()) * height;
        if (lineHeight <= 0) return false;
        float vertical =
                Math.abs((phrase.top() + phrase.bottom() - mark.top() - mark.bottom()) * .5f)
                        * height;
        float following = (mark.centerX() - phrase.right()) * width;
        if (phrase.kind() == ScorePlaybackDirection.Kind.CODA)
            return codaGlyphBefore(mark, phrase, width, height);
        // Closely aligned text and symbol form one instruction even when the
        // symbol sits on the outgoing side of a visually nearby boundary.
        if (phrase.kind() == ScorePlaybackDirection.Kind.TO_CODA
                && vertical <= .25f * lineHeight
                && following >= 0
                && following <= .8f * lineHeight) return true;
        // A coda centred on the following barline has independent destination ownership.
        for (int i = 1; i < measures.size(); i++) {
            var before = measures.get(i - 1);
            var after = measures.get(i);
            if (before.top() >= after.bottom() || after.top() >= before.bottom()) continue;
            if (mark.bottom() + lineHeight / height >= after.top()
                    && mark.bottom() < after.bottom()
                    && phrase.right() <= before.right()
                    && phrase.left() >= before.left() - lineHeight / width
                    && before.right() < mark.centerX()
                    && after.left() >= before.right()
                    && Math.abs(after.left() - mark.centerX()) * width <= lineHeight * .25f)
                return false;
        }
        return vertical <= lineHeight * .6f
                && following >= -lineHeight * .5f
                && following <= lineHeight * 1.3f;
    }

    private static boolean codaGlyphBefore(Glyph mark, Phrase phrase, int width, int height) {
        float lineHeight = Math.max(phrase.bottom() - phrase.top(), mark.bottom() - mark.top()) * height;
        float vertical = Math.abs((phrase.top() + phrase.bottom() - mark.top() - mark.bottom()) * .5f)
                * height;
        float before = (phrase.left() - mark.centerX()) * width;
        return mark.kind() == ScorePlaybackDirection.Kind.CODA
                && lineHeight > 0 && vertical <= .25f * lineHeight
                && before >= 0 && before <= .7f * lineHeight;
    }

    private static Phrase mergedCodaTrigger(PlayingTechniqueDetector.Word word, List<Glyph> glyphs) {
        String text = word.text().strip();
        var suffix = TRAILING_CODA_ARTIFACT.matcher(text);
        if (!suffix.find() || kind(text.substring(0, suffix.start())) != ScorePlaybackDirection.Kind.TO_CODA)
            return null;
        for (Glyph glyph : glyphs) {
            if (glyph.kind() == ScorePlaybackDirection.Kind.CODA
                    && glyph.centerX() >= (word.left() + word.right()) * .5f
                    && glyph.centerX() < word.right()
                    && glyph.top() >= word.top() && glyph.bottom() <= word.bottom())
                return new Phrase(ScorePlaybackDirection.Kind.TO_CODA, word.left(), glyph.centerX(),
                        word.top(), word.bottom());
        }
        return null;
    }

    static List<PlayingTechniqueDetector.Word> withPdfDestinations(
            List<PlayingTechniqueDetector.Word> words, List<TablatureDecoder.Word> nativeWords) {
        var result = new ArrayList<>(words);
        for (var word : nativeWords) {
            if (word.text().equals("𝄋") || word.text().equals("𝄌"))
                result.add(new PlayingTechniqueDetector.Word(
                        word.text(), word.left(), word.top(), word.right(), word.bottom()));
        }
        return result;
    }

    static List<ScorePlaybackDirection> detect(
            List<PlayingTechniqueDetector.Word> input,
            List<Glyph> glyphs,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures,
            int width,
            int height) {
        if (width <= 0 || height <= 0 || staffs == null || measures == null) return List.of();
        var marks = new ArrayList<Glyph>();
        if (glyphs != null) marks.addAll(glyphs);
        var phrases = new ArrayList<Phrase>();
        var words = new ArrayList<PlayingTechniqueDetector.Word>();
        if (input != null) words.addAll(input);
        words.sort(
                Comparator.comparingDouble(PlayingTechniqueDetector.Word::top)
                        .thenComparingDouble(PlayingTechniqueDetector.Word::left));
        for (var word : words) {
            var value = kind(word.text());
            Phrase longest =
                    value == null
                            ? mergedCodaTrigger(word, marks)
                            : new Phrase(
                                    value, word.left(), word.right(), word.top(), word.bottom());
            // OCR can merge a complete jump and the following tempo/style text
            // into one box, with the intervening symbol read as a wider space.
            int separator = word.text().indexOf("  ");
            if (longest == null && separator > 0) {
                var prefix = kind(word.text().substring(0, separator));
                if (prefix == ScorePlaybackDirection.Kind.DAL_SEGNO_AL_CODA
                        || prefix == ScorePlaybackDirection.Kind.DA_CAPO_AL_CODA) {
                    float edge = word.left()
                            + (word.right() - word.left()) * separator / word.text().length();
                    longest = new Phrase(prefix, word.left(), edge, word.top(), word.bottom());
                }
            }
            // OCR may split D.C./D.S., al, and Fine/Coda. Prefer the complete
            // phrase over its plain-jump prefix; join immediate same-line words only.
            var line = new ArrayList<PlayingTechniqueDetector.Word>();
            line.add(word);
            for (var next : words)
                if (next != word
                        && next.left() >= word.right()
                        && Math.abs((next.top() + next.bottom() - word.top() - word.bottom()) * .5f)
                                <= Math.max(next.bottom() - next.top(), word.bottom() - word.top())
                                        * .5f) line.add(next);
            line.sort(Comparator.comparingDouble(PlayingTechniqueDetector.Word::left));
            String text = word.text();
            float right = word.right(), bottom = word.bottom();
            for (int i = 1; i < Math.min(5, line.size()); i++) {
                var next = line.get(i);
                float h = Math.max(bottom - word.top(), next.bottom() - next.top());
                if ((next.left() - right) * width > h * height * 1.3f) break;
                text += " " + next.text();
                right = next.right();
                bottom = Math.max(bottom, next.bottom());
                value = kind(text);
                if (value != null)
                    longest = new Phrase(value, word.left(), right, word.top(), bottom);
            }
            if (longest != null) phrases.add(longest);
        }
        // Use the complete printed phrase to assign any adjacent Coda symbol.
        var completePhrases = phrases.stream()
                .filter(phrase -> phrases.stream().noneMatch(other ->
                        other != phrase
                                && other.left() <= phrase.left()
                                && other.right() >= phrase.right()
                                && (other.left() < phrase.left() || other.right() > phrase.right())
                                && Math.abs(other.bottom() - phrase.bottom()) <= .5f * Math.max(
                                        other.bottom() - other.top(), phrase.bottom() - phrase.top())))
                .toList();
        var codaGlyphsByPhrase = new java.util.HashMap<Phrase, Glyph>();
        for (var phrase : completePhrases) {
            if (phrase.kind() != ScorePlaybackDirection.Kind.TO_CODA
                    && phrase.kind() != ScorePlaybackDirection.Kind.CODA) continue;
            var matches = marks.stream().filter(mark -> codaGlyphBefore(mark, phrase, width, height)).toList();
            if (matches.size() == 1) codaGlyphsByPhrase.put(phrase, matches.get(0));
        }
        marks.removeIf(mark -> mark.kind() == ScorePlaybackDirection.Kind.CODA
                && (codaGlyphsByPhrase.containsValue(mark) || completePhrases.stream().anyMatch(phrase ->
                        attachedCodaSymbol(mark, phrase, measures, width, height))));
        var leadingTriggers = new java.util.HashSet<Glyph>();
        var labelledDestinations = new java.util.HashSet<Glyph>();
        for (var phrase : completePhrases) {
            var symbol = codaGlyphsByPhrase.get(phrase);
            boolean leadingTrigger = symbol != null && phrase.kind() == ScorePlaybackDirection.Kind.TO_CODA;
            var mark = leadingTrigger
                    ? new Glyph(phrase.kind(), symbol.centerX(), phrase.top(), phrase.bottom())
                    : phrase.glyph();
            marks.add(mark);
            if (leadingTrigger) leadingTriggers.add(mark);
            else if (symbol != null) labelledDestinations.add(mark);
        }
        var result = new ArrayList<ScorePlaybackDirection>();
        for (var mark : marks) {
            PlayingTechniqueDetector.Staff owner = null;
            float best = Float.MAX_VALUE;
            boolean labelledDestination = labelledDestinations.contains(mark);
            for (var staff : staffs) {
                float distance = (staff.top() - mark.bottom() * height) / staff.gap();
                float limit =
                        mark.kind() == ScorePlaybackDirection.Kind.SEGNO
                                ? NavigationSegnoGlyphs.MAX_STAFF_DISTANCE
                                : labelledDestination ? NavigationCodaGlyphs.MAX_LABELLED_STAFF_DISTANCE : 6f;
                // Glyph boxes and staff rules round independently on the raster.
                if (distance < -1.2f || distance > limit + 1f / staff.gap()) continue;
                if (Math.abs(distance) < best) {
                    best = Math.abs(distance);
                    owner = staff;
                }
            }
            if (owner == null) continue;
            int picked = -1, firstOnStaff = -1, previousOnStaff = -1;
            boolean segno = mark.kind() == ScorePlaybackDirection.Kind.SEGNO;
            boolean leadingTrigger = leadingTriggers.contains(mark);
            float nearest = Float.MAX_VALUE;
            float center = (owner.top() + owner.bottom()) * .5f / height;
            for (int m = 0; m < measures.size(); m++) {
                var bar = measures.get(m);
                if (center < bar.top() - owner.gap() / height
                        || center > bar.bottom() + owner.gap() / height) continue;
                if (firstOnStaff < 0) firstOnStaff = m;
                if ((segno || leadingTrigger) && previousOnStaff == m - 1 && previousOnStaff >= 0) {
                    var previous = measures.get(previousOnStaff);
                    // The gap between regions is the shared barline. Segno opens its next measure.
                    // A Coda trigger placed in that gap closes its previous measure.
                    float gap = bar.left() - previous.right();
                    // A proved raster Segno may overhang the final edge of the preceding bar.
                    float leftSlack = segno && glyphs != null && glyphs.contains(mark)
                            ? .35f * owner.gap() / width : 0;
                    if (gap >= 0 && gap * width <= owner.gap() * 2
                            && mark.centerX() >= previous.right() - leftSlack
                            && mark.centerX() <= bar.left()) {
                        picked = leadingTrigger ? previousOnStaff : m;
                        nearest = 0;
                        break;
                    }
                }
                previousOnStaff = m;
                float distance =
                        mark.centerX() < bar.left()
                                ? bar.left() - mark.centerX()
                                : mark.centerX() > bar.right() ? mark.centerX() - bar.right() : 0;
                if (distance < nearest) {
                    nearest = distance;
                    picked = m;
                }
            }
            boolean outgoing = mark.kind().outgoing();
            // A destination may precede the clef and a full seven-accidental signature.
            float headerAllowance = !outgoing && picked == firstOnStaff ? 16 : 8;
            if (picked < 0 || nearest * width > owner.gap() * headerAllowance) continue;
            if (labelledDestination && best > 6 + 1f / owner.gap() && picked != firstOnStaff) continue;
            // Expanded multi-rests share a printed rectangle. Outgoing directions
            // occur after its final logical bar, not after its first repetition.
            if (outgoing)
                while (picked + 1 < measures.size()
                        && measures.get(picked).equals(measures.get(picked + 1))) picked++;
            var direction = new ScorePlaybackDirection(picked + (outgoing ? 1 : 0), mark.kind());
            if (!result.contains(direction)) result.add(direction);
        }
        // An unlabelled coda at a To Coda trigger is the sign in that instruction.
        // A coda at the D.S./D.C. jump boundary can still be its separate destination.
        result.removeIf(direction -> direction.kind() == ScorePlaybackDirection.Kind.CODA
                && result.contains(new ScorePlaybackDirection(
                        direction.measureBoundary(), ScorePlaybackDirection.Kind.TO_CODA)));
        result.sort(
                Comparator.comparingInt(ScorePlaybackDirection::measureBoundary)
                        .thenComparingInt(d -> d.kind().wireId()));
        return List.copyOf(result);
    }
}
