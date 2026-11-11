// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Glissando and slide timing within the existing sounding note. */
public final class GlissandoPerformance {
    public record Step(double offset, double duration, int midi) {}

    private GlissandoPerformance() {}

    /** White-key attacks exclude the destination, which keeps its written attack. */
    public static List<Step> whiteKeys(int source, int target, double duration) {
        return whiteKeys(source, target, duration, 0);
    }

    /** Give each sounded key time to speak, even during a short, wide sweep. */
    public static List<Step> whiteKeys(
            int source, int target, double duration, double minimumStep) {
        if (source < 0 || source > 127 || target < 0 || target > 127
                || !Double.isFinite(duration) || duration <= 0
                || !Double.isFinite(minimumStep) || minimumStep < 0)
            throw new IllegalArgumentException("Invalid gliss endpoints or duration");
        var pitches = new ArrayList<Integer>();
        int direction = Integer.compare(target, source);
        for (int midi = source + direction; direction != 0 && midi != target; midi += direction) {
            if (switch (Math.floorMod(midi, 12)) {
                case 0, 2, 4, 5, 7, 9, 11 -> true;
                default -> false;
            }) pitches.add(midi);
        }
        double hold = duration * 2 / 3;
        int count = pitches.size();
        if (minimumStep > 0 && count > 0) {
            // A wide sweep can start sooner, but still leaves an audible source attack.
            double available = Math.min(duration * .8,
                    Math.max(duration - hold, count * minimumStep));
            count = Math.min(count, (int) Math.floor(available / minimumStep));
            hold = duration - available;
        }
        if (count == 0) return List.of(new Step(0, duration, source));

        double unit = (duration - hold) / count;
        var result = new ArrayList<Step>(count + 1);
        result.add(new Step(0, hold, source));
        for (int i = 0; i < count; i++) {
            double offset = hold + i * unit;
            int index = (i + 1) * pitches.size() / count - 1;
            result.add(new Step(offset, i == count - 1 ? duration - offset : unit,
                    pitches.get(index)));
        }
        return List.copyOf(result);
    }

    /** Pitch moves in semitones after the initial attack and reaches the destination at release. */
    public static double continuousRatio(double targetRatio, double age, double duration) {
        double start = duration * .25;
        if (age <= start) return 1;
        if (age >= duration) return targetRatio;
        return Math.pow(targetRatio, ease((age - start) / (duration - start)));
    }

    /** Wide connected slides need more time than a short ornamental approach. */
    public static double approachRatio(
            double initialRatio, double age, double duration, boolean connected) {
        if (age <= 0) return initialRatio;
        if (initialRatio == 1) return 1;
        double span = Math.max(initialRatio, 1 / initialRatio);
        double transition = connected
                ? Math.min(duration * .60, Math.min(.32, .07 + .17 * (span - 1)))
                : Math.max(.001, Math.min(.16, duration * .30));
        if (age >= transition) return 1;
        return Math.pow(initialRatio, 1 - ease(age / transition));
    }

    private static double ease(double progress) {
        return progress * progress * (3 - 2 * progress);
    }
}
