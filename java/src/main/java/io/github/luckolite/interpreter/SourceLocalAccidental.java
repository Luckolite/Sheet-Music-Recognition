// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;

/** Actual picker lineage, separate from head admission and rhythmic position anchors. */
final class SourceLocalAccidental {
    private static final String SUPPORTED = "MODEL_SUPPORTED_ACTUAL_LOCAL_ACCIDENTAL";

    record Glyph(int index, int label, ExactComponentMembers.Stats stats, List<Integer> pixels) {
        Glyph {
            pixels = List.copyOf(pixels);
        }
    }

    record Head(
            int index,
            List<Integer> pixels,
            List<Integer> unresolvedParentResidual,
            String status) {
        Head {
            pixels = List.copyOf(pixels);
            unresolvedParentResidual = List.copyOf(unresolvedParentResidual);
        }
    }

    record Choice(
            int note,
            ScoreNoteEvent originalNote,
            int glyph,
            int head,
            int accidental,
            int glyphAlternatives,
            int headAlternatives,
            boolean ambiguous,
            String status,
            Boolean naturalPrefix) {
        Choice(int note, ScoreNoteEvent originalNote, int glyph, int head, int accidental,
                int glyphAlternatives, int headAlternatives, boolean ambiguous, String status) {
            this(note, originalNote, glyph, head, accidental, glyphAlternatives, headAlternatives,
                    ambiguous, status, null);
        }
    }

    record ProvedMark(ScoreNoteEvent note, int accidental, boolean naturalPrefix) {
        ProvedMark(ScoreNoteEvent note, boolean naturalPrefix) {
            this(note, note.writtenAccidental(), naturalPrefix);
        }
    }

    record PrintedMark(int noteIndex, int accidental, Boolean naturalPrefix) {
        PrintedMark(int noteIndex, int accidental) {
            this(noteIndex, accidental, null);
        }
    }

    /** Optically proved sloping crossbars. Each strip is checked against the exact parent pixels. */
    record AccidentalStrips(
            int kind,
            int left,
            int right,
            int rise,
            int topFirst,
            int topLast,
            int bottomFirst,
            int bottomLast) {
        int top() {
            return topFirst - edge();
        }

        int bottom() {
            return bottomLast + edge();
        }

        private int edge() {
            return (int) Math.ceil(rise * .05 * (right - left));
        }

        boolean represented(Set<Integer> pixels, int width) {
            return band(pixels, width, topFirst, topLast)
                    && band(pixels, width, bottomFirst, bottomLast);
        }

        private boolean band(Set<Integer> pixels, int width, int first, int last) {
            float center = (left + right) * .5f;
            int minimum = Math.max(3, (right - left + 2) / 2);
            int rows = 0;
            for (int y = first; y <= last; y++) {
                int run = 0;
                for (int x = left; x <= right; x++) {
                    int row = Math.round(y - rise * .1f * (x - center));
                    run = pixels.contains(row * width + x) ? run + 1 : 0;
                    if (run == minimum) {
                        rows++;
                        break;
                    }
                }
            }
            return rows >= 2;
        }
    }

    record SplitAccidental(
            Object parent,
            Object upper,
            Object lower,
            AccidentalStrips upperInk,
            AccidentalStrips lowerInk) {}

    private record NoteLocation(
            int measure, int staff, int staffCount, float position, float pageY, int staffStep) {
        NoteLocation(ScoreNoteEvent note) {
            this(note.measureIndex(), note.staffIndex(), note.staffCount(),
                    note.positionInMeasure(), note.pageY(), note.staffStep());
        }
    }

    static final class Role {
        private final List<Integer> pixels;

        private Role(List<Integer> pixels) {
            this.pixels = List.copyOf(pixels);
        }

        List<Integer> pixels() {
            return pixels;
        }
    }

    private static final Object ISSUER = new Object();
    private transient Object issuer;
    private final String rawSha, labelSha;
    private final int width, height, glyphCount, headCount;
    private final List<Glyph> glyphs;
    private final List<Choice> choices;
    private final Set<Integer> joinedNaturalOwners;
    // Absent when the producer has no authenticated double-sharp claims.
    private final Set<Integer> printedDoubleSharpOwners;
    private final Map<Integer, Integer> splitAccidentalOwners;
    private final List<Head> headAlternatives;
    private final List<Integer> unrepresentedGlyphMembers;
    private final boolean complete;
    private final boolean authenticatedFrame;

    private SourceLocalAccidental(
            String raw,
            String label,
            int width,
            int height,
            int glyphCount,
            int headCount,
            List<Glyph> glyphs,
            List<Head> headAlternatives,
            List<Choice> choices,
            Set<Integer> joinedNaturalOwners,
            Set<Integer> printedDoubleSharpOwners,
            Map<Integer, Integer> splitAccidentalOwners,
            List<Integer> unrepresentedGlyphMembers,
            boolean complete,
            boolean authenticatedFrame) {
        issuer = ISSUER;
        rawSha = raw;
        labelSha = label;
        this.width = width;
        this.height = height;
        this.glyphCount = glyphCount;
        this.headCount = headCount;
        this.glyphs = List.copyOf(glyphs);
        this.headAlternatives = List.copyOf(headAlternatives);
        this.choices = List.copyOf(choices);
        this.joinedNaturalOwners = Set.copyOf(joinedNaturalOwners);
        this.printedDoubleSharpOwners =
                printedDoubleSharpOwners.isEmpty() ? null : Set.copyOf(printedDoubleSharpOwners);
        this.splitAccidentalOwners = Map.copyOf(splitAccidentalOwners);
        this.unrepresentedGlyphMembers = List.copyOf(unrepresentedGlyphMembers);
        this.complete = complete;
        this.authenticatedFrame = authenticatedFrame;
    }

    @Override
    public boolean equals(Object value) {
        return value instanceof SourceLocalAccidental other
                && width == other.width
                && height == other.height
                && glyphCount == other.glyphCount
                && headCount == other.headCount
                && complete == other.complete
                && authenticatedFrame == other.authenticatedFrame
                && rawSha.equals(other.rawSha)
                && labelSha.equals(other.labelSha)
                && glyphs.equals(other.glyphs)
                && headAlternatives.equals(other.headAlternatives)
                && choices.equals(other.choices)
                && joinedNaturalOwners.equals(other.joinedNaturalOwners)
                && Objects.equals(printedDoubleSharpOwners, other.printedDoubleSharpOwners)
                && splitAccidentalOwners.equals(other.splitAccidentalOwners)
                && unrepresentedGlyphMembers.equals(other.unrepresentedGlyphMembers);
    }

    @Override
    public int hashCode() {
        int hash = Objects.hash(
                rawSha,
                labelSha,
                width,
                height,
                glyphCount,
                headCount,
                glyphs,
                headAlternatives,
                choices,
                joinedNaturalOwners,
                splitAccidentalOwners,
                unrepresentedGlyphMembers,
                complete,
                authenticatedFrame);
        return printedDoubleSharpOwners == null ? hash : 31 * hash + printedDoubleSharpOwners.hashCode();
    }

    static SourceLocalAccidental unavailable() {
        return new SourceLocalAccidental(
                "", "", 0, 0, 0, 0, List.of(), List.of(), List.of(), Set.of(), Set.of(), Map.of(),
                List.of(), false, false);
    }

    List<Choice> choices() {
        return choices;
    }

    List<Glyph> glyphs() {
        return glyphs;
    }

    List<Head> headAlternatives() {
        return headAlternatives;
    }

    List<Integer> unrepresentedGlyphMembers() {
        return unrepresentedGlyphMembers;
    }

    int headCount() {
        return headCount;
    }

    boolean complete() {
        return complete;
    }

    static SourceLocalAccidental capture(
            ExactComponentMembers.Collector symbols,
            ExactComponentMembers.Collector clefs,
            List<?> originalGlyphs,
            List<?> allHeads,
            ExactComponentMembers.Collector heads,
            Map<?, OmrScoreInterpreter.WrittenAccidentalChoice> actualChoices,
            List<?> finalHeads,
            List<ScoreNoteEvent> notes) {
        return capture(
                symbols,
                clefs,
                originalGlyphs,
                allHeads,
                heads,
                actualChoices,
                finalHeads,
                notes,
                Map.of(),
                Set.of());
    }

    static SourceLocalAccidental capture(
            ExactComponentMembers.Collector symbols,
            ExactComponentMembers.Collector clefs,
            List<?> originalGlyphs,
            List<?> allHeads,
            ExactComponentMembers.Collector heads,
            Map<?, OmrScoreInterpreter.WrittenAccidentalChoice> actualChoices,
            List<?> finalHeads,
            List<ScoreNoteEvent> notes,
            Map<Object, List<Object>> accidentalFragments,
            Set<Object> provedNaturalHeads) {
        return capture(
                symbols,
                clefs,
                originalGlyphs,
                allHeads,
                heads,
                actualChoices,
                finalHeads,
                notes,
                accidentalFragments,
                provedNaturalHeads,
                List.of());
    }

    static SourceLocalAccidental capture(
            ExactComponentMembers.Collector symbols,
            ExactComponentMembers.Collector clefs,
            List<?> originalGlyphs,
            List<?> allHeads,
            ExactComponentMembers.Collector heads,
            Map<?, OmrScoreInterpreter.WrittenAccidentalChoice> actualChoices,
            List<?> finalHeads,
            List<ScoreNoteEvent> notes,
            Map<Object, List<Object>> accidentalFragments,
            Set<Object> provedNaturalHeads,
            List<SplitAccidental> splitProofs) {
        return capture(
                symbols,
                clefs,
                originalGlyphs,
                allHeads,
                heads,
                actualChoices,
                finalHeads,
                notes,
                accidentalFragments,
                provedNaturalHeads,
                splitProofs,
                Set.of());
    }

    static SourceLocalAccidental capture(
            ExactComponentMembers.Collector symbols,
            ExactComponentMembers.Collector clefs,
            List<?> originalGlyphs,
            List<?> allHeads,
            ExactComponentMembers.Collector heads,
            Map<?, OmrScoreInterpreter.WrittenAccidentalChoice> actualChoices,
            List<?> finalHeads,
            List<ScoreNoteEvent> notes,
            Map<Object, List<Object>> accidentalFragments,
            Set<Object> provedNaturalHeads,
            List<SplitAccidental> splitProofs,
            Set<Object> provedDoubleSharpHeads) {
        return capture(symbols, clefs, originalGlyphs, allHeads, heads, actualChoices, finalHeads, notes,
                accidentalFragments, provedNaturalHeads, splitProofs, provedDoubleSharpHeads, Map.of(), Map.of());
    }

    static SourceLocalAccidental capture(
            ExactComponentMembers.Collector symbols,
            ExactComponentMembers.Collector clefs,
            List<?> originalGlyphs,
            List<?> allHeads,
            ExactComponentMembers.Collector heads,
            Map<?, OmrScoreInterpreter.WrittenAccidentalChoice> actualChoices,
            List<?> finalHeads,
            List<ScoreNoteEvent> notes,
            Map<Object, List<Object>> accidentalFragments,
            Set<Object> provedNaturalHeads,
            List<SplitAccidental> splitProofs,
            Set<Object> provedDoubleSharpHeads,
            Map<?, ?> recoveredNaturals, Map<?, ?> recoveredDyadAccidentals) {
        var budget = new ExactComponentMembers.Budget(64_000_000, 128L * 1024 * 1024);
        if (symbols == null || clefs == null || heads == null || finalHeads.size() != notes.size())
            return unavailable();
        var partitions = heads.producerPartitions(allHeads, budget);
        boolean authenticatedFrame =
                partitions != null
                        && partitions.sharesFrame(symbols)
                        && partitions.sharesFrame(clefs);
        var glyphIds = new IdentityHashMap<Object, Integer>();
        var entries = new ArrayList<Glyph>();
        boolean complete = authenticatedFrame;
        for (int ordinal = 0; ordinal < originalGlyphs.size(); ordinal++) {
            Object original = originalGlyphs.get(ordinal);
            if (glyphIds.containsKey(original)) complete = false;
            else glyphIds.put(original, ordinal);
            var token = symbols.acceptedGlyph(original);
            if (token == null) token = clefs.acceptedGlyph(original);
            if (token == null) {
                complete = false;
                continue;
            }
            int[] pixels = token.pixels(budget);
            if (pixels == null || pixels.length != token.stats().area()) {
                complete = false;
                continue;
            }
            var vector = new ArrayList<Integer>(pixels.length);
            for (int pixel : pixels) vector.add(pixel);
            entries.add(new Glyph(ordinal, token.label(), token.stats(), List.copyOf(vector)));
        }
        var unrepresented = new ArrayList<Integer>();
        int[] omittedSymbols = symbols.unrepresentedGlyphMembers((byte) 5, budget);
        int[] omittedClefs = clefs.unrepresentedGlyphMembers((byte) 3, budget);
        if (omittedSymbols == null || omittedClefs == null) complete = false;
        if (omittedSymbols != null) for (int pixel : omittedSymbols) unrepresented.add(pixel);
        if (omittedClefs != null) for (int pixel : omittedClefs) unrepresented.add(pixel);

        var headIds = new IdentityHashMap<Object, Integer>();
        var alternatives = new ArrayList<Head>();
        for (int id = 0; id < allHeads.size(); id++) {
            Object head = allHeads.get(id);
            boolean duplicate = headIds.containsKey(head);
            if (duplicate) complete = false;
            else headIds.put(head, id);
            var footprint = heads.producerFootprint(head, budget);
            var partition = partitions == null ? null : partitions.get(head);
            var pixels = new ArrayList<Integer>();
            String status = "UNKNOWN_MISSING_GENUINE_HEAD_MEMBERS";
            if (footprint != null) {
                for (int pixel : footprint.pixels()) pixels.add(pixel);
                status =
                        duplicate
                                ? "UNKNOWN_DUPLICATE_GENUINE_HEAD_IDENTITY"
                                : partition == null
                                        ? "GENUINE_HEAD_MEMBERS_UNKNOWN_PARENT_PARTITION"
                                        : partition.residual().isEmpty()
                                                ? "GENUINE_HEAD_TRAVERSAL_NOT_MUSICAL_COMPLETENESS"
                                                : "GENUINE_HEAD_TRAVERSAL_WITH_UNRESOLVED_PARENT_MEMBERS";
            } else complete = false;
            alternatives.add(
                    new Head(
                            id,
                            List.copyOf(pixels),
                            partition == null ? List.of() : partition.residual(),
                            status));
        }
        var joinedNaturalOwners = new HashSet<Integer>();
        var dyadOwners = new HashMap<Integer, Integer>();
        var printedDoubleSharpOwners = new HashSet<Integer>();
        var fragmentClaims = new IdentityHashMap<Object, Integer>();
        if (!provedNaturalHeads.isEmpty()) {
            for (var head : finalHeads) {
                var choice = actualChoices.get(head);
                if (choice == null || choice.glyph() == null) continue;
                for (var parent :
                        accidentalFragments.getOrDefault(choice.glyph(), List.of(choice.glyph())))
                    fragmentClaims.merge(parent, 1, Integer::sum);
            }
        }
        var exactGlyphs = new HashMap<Integer, Glyph>();
        for (var glyph : entries) exactGlyphs.put(glyph.index(), glyph);
        var rows = new ArrayList<Choice>();
        for (int n = 0; n < notes.size(); n++) {
            Object head = finalHeads.get(n);
            var choice = actualChoices.get(head);
            if (choice == null) continue;
            Integer glyph = glyphIds.get(choice.glyph()), headId = headIds.get(head);
            var note = notes.get(n);
            var headToken = heads.acceptedProbeHead(head, note.measureIndex(), note.staffIndex());
            if (authenticatedFrame
                    && glyph == null
                    && headId != null
                    && headToken != null
                    && choice.head() == head
                    && !choice.ambiguous()
                    && provedNaturalHeads.contains(head)
                    && choice.accidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL
                    && choice.accidental() == note.writtenAccidental()
                    && exclusivelyOwnedNaturalUnion(
                            accidentalFragments.get(choice.glyph()),
                            glyphIds,
                            exactGlyphs,
                            fragmentClaims)) {
                int[] members = headToken.pixels(budget);
                var partition = partitions.get(head);
                if (members != null
                        && members.length == headToken.stats().area()
                        && partition != null
                        && partition.residual().isEmpty()) joinedNaturalOwners.add(n);
            }
            if (authenticatedFrame
                    && headId != null
                    && headToken != null
                    && choice.head() == head
                    && provedDoubleSharpHeads.contains(head)
                    && (!choice.ambiguous()
                            || choice.accidental() == ScoreNoteEvent.ACCIDENTAL_DOUBLE_SHARP)
                    && note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_DOUBLE_SHARP) {
                int[] members = headToken.pixels(budget);
                var partition = partitions.get(head);
                if (members != null
                        && members.length == headToken.stats().area()
                        && partition != null
                        && partition.residual().isEmpty()) printedDoubleSharpOwners.add(n);
            }
            Integer naturalGlyph = glyphIds.get(recoveredNaturals.get(head));
            if (authenticatedFrame && naturalGlyph != null && exactGlyphs.containsKey(naturalGlyph)
                    && headId != null && headToken != null
                    && note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL) {
                var partition = partitions.get(head);
                if (partition != null && partition.residual().isEmpty()) {
                    joinedNaturalOwners.add(n);
                    rows.add(new Choice(n, note, naturalGlyph, headId, ScoreNoteEvent.ACCIDENTAL_NATURAL,
                            choice.glyphAlternatives(), choice.headAlternatives(), false, SUPPORTED));
                    continue;
                }
            }
            Integer dyadGlyph = glyphIds.get(recoveredDyadAccidentals.get(head));
            if (authenticatedFrame && dyadGlyph != null && exactGlyphs.containsKey(dyadGlyph)
                    && headId != null && headToken != null
                    && (note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FLAT
                            || note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_SHARP)) {
                var partition = partitions.get(head);
                if (partition != null && partition.residual().isEmpty()) {
                    dyadOwners.put(n, note.writtenAccidental());
                    rows.add(new Choice(n, note, dyadGlyph, headId, note.writtenAccidental(),
                            choice.glyphAlternatives(), choice.headAlternatives(), false, SUPPORTED,
                            choice.naturalPrefix() ? Boolean.TRUE : null));
                    continue;
                }
            }
            String status = "UNKNOWN_UNBOUND_OR_CHANGED_PICKER_ROLE";
            if (choice.head() == head
                    && glyph != null
                    && headId != null
                    && headToken != null
                    && choice.accidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                    && choice.accidental() == note.writtenAccidental()
                    && !choice.ambiguous()) {
                int[] members = headToken.pixels(budget);
                var partition = partitions == null ? null : partitions.get(head);
                if (members != null
                        && members.length == headToken.stats().area()
                        && partition != null
                        && partition.residual().isEmpty())
                    status = SUPPORTED;
            }
            rows.add(
                    new Choice(
                            n,
                            note,
                            glyph == null ? -1 : glyph,
                            headId == null ? -1 : headId,
                            choice.accidental(),
                            choice.glyphAlternatives(),
                            choice.headAlternatives(),
                            choice.ambiguous(),
                            status));
        }
        var splitAccidentalOwners =
                authenticatedFrame
                        ? splitAccidentalOwners(
                                splitProofs,
                                glyphIds,
                                exactGlyphs,
                                partitions,
                                finalHeads,
                                notes,
                                actualChoices,
                                accidentalFragments,
                                symbols.width)
                        : Map.<Integer, Integer>of();
        dyadOwners.putAll(splitAccidentalOwners);
        return new SourceLocalAccidental(
                symbols.rawSha,
                symbols.labelSha,
                symbols.width,
                symbols.height,
                originalGlyphs.size(),
                allHeads.size(),
                entries,
                alternatives,
                rows,
                joinedNaturalOwners,
                printedDoubleSharpOwners,
                dyadOwners,
                unrepresented,
                complete,
                authenticatedFrame);
    }

    private static Map<Integer, Integer> splitAccidentalOwners(
            List<SplitAccidental> proofs,
            Map<Object, Integer> glyphIds,
            Map<Integer, Glyph> glyphs,
            ExactComponentMembers.ProducerPartitionBatch partitions,
            List<?> finalHeads,
            List<ScoreNoteEvent> notes,
            Map<?, OmrScoreInterpreter.WrittenAccidentalChoice> choices,
            Map<Object, List<Object>> fragments,
            int width) {
        var owners = new HashMap<Integer, Integer>();
        if (proofs.isEmpty()) return owners;

        var site = new IdentityHashMap<Object, Integer>();
        for (int index = 0; index < finalHeads.size(); index++)
            site.merge(finalHeads.get(index), index, (first, second) -> -1);
        var byParent = new IdentityHashMap<Object, SplitAccidental>();
        var conflicts = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        for (SplitAccidental proof : proofs) {
            SplitAccidental prior = byParent.putIfAbsent(proof.parent(), proof);
            if (prior != null
                    && (prior.upper() != proof.upper()
                            || prior.lower() != proof.lower()
                            || !prior.upperInk().equals(proof.upperInk())
                            || !prior.lowerInk().equals(proof.lowerInk())))
                conflicts.add(proof.parent());
        }

        var candidates = new ArrayList<AccidentalPair>();
        var ownerClaims = new HashMap<Integer, Integer>();
        for (SplitAccidental proof : byParent.values()) {
            if (conflicts.contains(proof.parent())) continue;
            Integer id = glyphIds.get(proof.parent());
            Glyph glyph = id == null ? null : glyphs.get(id);
            int upperKind = proof.upperInk().kind(), lowerKind = proof.lowerInk().kind();
            if (glyph == null
                    || glyph.label() != OmrMeasurePostProcessor.CLEF_OR_KEY
                    || proof.upperInk().bottom() >= proof.lowerInk().top()
                    || !splitKind(upperKind)
                    || !splitKind(lowerKind)
                    || (upperKind != ScoreNoteEvent.ACCIDENTAL_SHARP
                            && lowerKind != ScoreNoteEvent.ACCIDENTAL_SHARP)) continue;
            int upper = site.getOrDefault(proof.upper(), -1);
            int lower = site.getOrDefault(proof.lower(), -1);
            if (upper < 0
                    || lower < 0
                    || upper == lower
                    || !retainedAccidental(
                            choices, partitions, proof.upper(), notes.get(upper), upperKind)
                    || !retainedAccidental(
                            choices, partitions, proof.lower(), notes.get(lower), lowerKind))
                continue;
            var a = notes.get(upper);
            var b = notes.get(lower);
            if (a.measureIndex() != b.measureIndex()
                    || a.staffIndex() != b.staffIndex()
                    || a.staffCount() != b.staffCount()
                    || a.pageY() >= b.pageY()) continue;
            var pixels = new HashSet<>(glyph.pixels());
            if (!proof.upperInk().represented(pixels, width)
                    || !proof.lowerInk().represented(pixels, width)) continue;
            boolean borrowed = false;
            for (var head : finalHeads) {
                if (head == proof.upper() || head == proof.lower()) continue;
                var claim = choices.get(head);
                if (claim == null || claim.glyph() == null) continue;
                for (var parent : fragments.getOrDefault(claim.glyph(), List.of(claim.glyph())))
                    if (parent == proof.parent()) borrowed = true;
            }
            if (borrowed) continue;
            ownerClaims.merge(upper, 1, Integer::sum);
            ownerClaims.merge(lower, 1, Integer::sum);
            candidates.add(new AccidentalPair(upper, lower, upperKind, lowerKind));
        }
        for (var pair : candidates)
            if (ownerClaims.get(pair.upper()) == 1 && ownerClaims.get(pair.lower()) == 1) {
                owners.put(pair.upper(), pair.upperKind());
                owners.put(pair.lower(), pair.lowerKind());
            }
        return owners;
    }

    private record AccidentalPair(int upper, int lower, int upperKind, int lowerKind) {}

    private static boolean splitKind(int kind) {
        return kind == ScoreNoteEvent.ACCIDENTAL_SHARP || kind == ScoreNoteEvent.ACCIDENTAL_NATURAL;
    }

    private static boolean retainedAccidental(
            Map<?, OmrScoreInterpreter.WrittenAccidentalChoice> choices,
            ExactComponentMembers.ProducerPartitionBatch partitions,
            Object head,
            ScoreNoteEvent note,
            int kind) {
        var choice = choices.get(head);
        var partition = partitions.get(head);
        return choice != null
                && choice.head() == head
                && !choice.ambiguous()
                && partition != null
                && partition.residual().isEmpty()
                && note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.writtenAccidental() == kind;
    }

    private static boolean exclusivelyOwnedNaturalUnion(
            List<Object> parents,
            Map<Object, Integer> glyphIds,
            Map<Integer, Glyph> exactGlyphs,
            Map<Object, Integer> fragmentClaims) {
        if (parents == null || parents.size() < 2 || parents.size() > 3) return false;
        int clefMembers = 0, symbolMembers = 0, clefComponents = 0;
        for (var parent : parents) {
            var glyph = exactGlyphs.get(glyphIds.get(parent));
            if (glyph == null || fragmentClaims.getOrDefault(parent, 0) != 1) return false;
            if (glyph.label() == OmrMeasurePostProcessor.CLEF_OR_KEY) {
                clefMembers += glyph.pixels().size();
                clefComponents++;
            } else if (glyph.label() == OmrMeasurePostProcessor.SYMBOL) {
                symbolMembers += glyph.pixels().size();
            } else return false;
        }
        return clefComponents == 1 && symbolMembers > 0 && clefMembers >= symbolMembers * 4;
    }

    List<PrintedMark> printedMarks(List<ScoreNoteEvent> original, List<ScoreNoteEvent> retained,
            List<MeasureRegion> sourceMeasures) {
        if (issuer != ISSUER || !authenticatedFrame || choices.isEmpty()) return List.of();
        var representedGlyphs = new HashMap<Integer, Glyph>();
        var glyphsByRow = new TreeMap<Integer, List<Glyph>>();
        for (var glyph : glyphs) {
            representedGlyphs.put(glyph.index(), glyph);
            glyphsByRow.computeIfAbsent((int) glyph.stats().centerY(), ignored -> new ArrayList<>())
                    .add(glyph);
        }
        var glyphOwners = new HashMap<Integer, Integer>();
        for (var choice : choices)
            if (choice.glyph() >= 0) glyphOwners.merge(choice.glyph(), 1, Integer::sum);
        var originalLocations = new HashMap<NoteLocation, Integer>();
        var notesByMeasure = new HashMap<Integer, List<ScoreNoteEvent>>();
        for (var note : original) {
            originalLocations.merge(new NoteLocation(note), 1, Integer::sum);
            if (note.kind() == ScoreNoteEvent.Kind.PITCHED)
                notesByMeasure.computeIfAbsent(note.measureIndex(), ignored -> new ArrayList<>())
                        .add(note);
        }
        var retainedIndexes = new HashMap<NoteLocation, Integer>();
        for (int index = 0; index < retained.size(); index++)
            retainedIndexes.merge(new NoteLocation(retained.get(index)), index, (a, b) -> -1);
        var marks = new ArrayList<PrintedMark>();
        for (var choice : choices) {
            boolean originalGlyph =
                    choice.status().equals(SUPPORTED)
                            && glyphOwners.getOrDefault(choice.glyph(), 0) == 1
                            && representedGlyphs.containsKey(choice.glyph());
            Integer ownedKind = splitAccidentalOwners.get(choice.note());
            boolean ownedDoubleSharp =
                    !originalGlyph
                            && ownedKind == null
                            && printedDoubleSharpOwners != null
                            && printedDoubleSharpOwners.contains(choice.note());
            if ((!originalGlyph
                            && !joinedNaturalOwners.contains(choice.note())
                            && ownedKind == null
                            && !ownedDoubleSharp)
                    || choice.note() < 0
                    || choice.note() >= original.size()) continue;
            var originalNote = original.get(choice.note());
            if (originalNote.kind() != ScoreNoteEvent.Kind.PITCHED
                    || !choice.originalNote().equals(originalNote)) continue;
            var location = new NoteLocation(originalNote);
            int index = retainedIndexes.getOrDefault(location, -1);
            if (index < 0 || originalLocations.get(location) != 1) continue;
            var note = retained.get(index);
            if (ownedDoubleSharp
                    && (note.kind() != ScoreNoteEvent.Kind.PITCHED
                            || choice.originalNote() != originalNote
                            || note.diatonicPitchIdentity() != originalNote.diatonicPitchIdentity()
                            || note.octaveShift() != originalNote.octaveShift()
                            || note.harmonicSemitones() != originalNote.harmonicSemitones()))
                continue;
            int accidental =
                    ownedDoubleSharp
                            ? ScoreNoteEvent.ACCIDENTAL_DOUBLE_SHARP
                            : ownedKind == null ? choice.accidental() : ownedKind;
            if (note.kind() == ScoreNoteEvent.Kind.PITCHED
                    && note.writtenAccidental() == accidental
                    && (!originalGlyph || ownedKind != null || joinedNaturalOwners.contains(choice.note())
                            || !ambiguousVerticalOwner(choice, originalNote, sourceMeasures,
                                    notesByMeasure, representedGlyphs, glyphsByRow)))
                marks.add(new PrintedMark(index, accidental,
                        accidental == ScoreNoteEvent.ACCIDENTAL_SHARP ? choice.naturalPrefix() : null));
        }
        marks.sort(Comparator.comparingInt(PrintedMark::noteIndex));
        return List.copyOf(marks);
    }

    static List<PrintedMark> includeClosedTripletSharps(
            List<PrintedMark> marks, List<ScoreNoteEvent> proved, List<ScoreNoteEvent> retained) {
        return includeProvedLocalMarks(marks, proved, retained, ScoreNoteEvent.ACCIDENTAL_SHARP);
    }

    static List<PrintedMark> includeProvedLocalMarks(
            List<PrintedMark> marks, List<ScoreNoteEvent> proved, List<ScoreNoteEvent> retained, int accidental) {
        if (proved.isEmpty()) return marks;
        return includeProvedLocalMarks(marks, proved.stream()
                .map(note -> new ProvedMark(note, accidental, false)).toList(), retained);
    }

    static List<PrintedMark> includeProvedLocalMarks(
            List<PrintedMark> marks, List<ProvedMark> proved, List<ScoreNoteEvent> retained) {
        if (proved.isEmpty()) return marks;
        var owners = new HashMap<NoteLocation, Integer>();
        for (int i = 0; i < retained.size(); i++)
            if (retained.get(i).kind() == ScoreNoteEvent.Kind.PITCHED)
                owners.merge(new NoteLocation(retained.get(i)), i, (a, b) -> -1);
        var combined = new TreeMap<Integer, PrintedMark>();
        for (var mark : marks) combined.put(mark.noteIndex(), mark);
        for (var proof : proved) {
            var note = proof.note();
            int index = owners.getOrDefault(new NoteLocation(note), -1);
            int accidental = proof.accidental();
            if (index < 0 || retained.get(index).writtenAccidental() != accidental
                    || retained.get(index).clefBottomDiatonic() != note.clefBottomDiatonic()) continue;
            var existing = combined.get(index);
            boolean prefix = proof.naturalPrefix() && accidental == ScoreNoteEvent.ACCIDENTAL_SHARP;
            if (existing == null || prefix && existing.accidental() == accidental)
                combined.put(index, new PrintedMark(index, accidental, prefix ? Boolean.TRUE : null));
        }
        return List.copyOf(combined.values());
    }

    /** A proved natural and a new printed sharp can share the same written pitch in a bar. */
    static List<PrintedMark> retainBeamedSharpRestatements(
            List<PrintedMark> known, List<ScoreNoteEvent> notes, List<MeasureRegion> measures,
            byte[] source, byte[] normalized, int width, int height) {
        if (source == null || normalized == null || source.length != (long) width * height
                || source.length != normalized.length
                || known.stream().noneMatch(mark -> mark.accidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL))
            return known;
        var marks = new TreeMap<Integer, PrintedMark>();
        for (var mark : known) marks.put(mark.noteIndex(), mark);
        var naturals = new java.util.HashSet<Integer>();
        int bar = -1;
        float preceding = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            if (note.measureIndex() != bar) {
                bar = note.measureIndex();
                naturals.clear();
                preceding = Float.NEGATIVE_INFINITY;
            }
            if (bar < 0 || bar >= measures.size() || note.staffCount() != 1
                    || note.kind() != ScoreNoteEvent.Kind.PITCHED) continue;
            var box = measures.get(bar);
            float x = (box.left() + note.positionInMeasure() * (box.right() - box.left())) * width;
            float y = note.pageY() * height;
            float gap = (box.bottom() - box.top()) * height / 8.2f;
            int pitch = note.diatonicPitchIdentity();
            var knownMark = marks.get(i);
            if (knownMark != null && knownMark.accidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL)
                naturals.add(pitch);
            if (note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_SHARP && naturals.contains(pitch)) {
                if (knownMark != null || note.beamCount() == 1 && note.stemDirection() != 0
                        && !note.tiedFromPrevious() && x - preceding >= gap * 2f
                        && OmrScoreInterpreter.printedStoppedHarmonicSharp(source, width, height, x, y, gap)
                        && OmrScoreInterpreter.printedStoppedHarmonicSharp(
                                normalized, width, height, x, y, gap)) {
                    marks.putIfAbsent(i, new PrintedMark(i, ScoreNoteEvent.ACCIDENTAL_SHARP));
                    naturals.remove(pitch);
                }
            }
            preceding = x;
        }
        return marks.size() == known.size() ? known : List.copyOf(marks.values());
    }

    /** Keep a printed flat even when the effective pitch already agrees with the key. */
    static List<PrintedMark> retainSingleStaffTripletFlats(List<PrintedMark> known, List<ScoreNoteEvent> notes,
            List<MeasureRegion> measures, byte[] labels, byte[] raw, byte[] gray, int width, int height) {
        if (labels == null || raw == null || gray == null || labels.length != (long) width * height
                || raw.length != labels.length || gray.length != labels.length) return known;
        var marks = new TreeMap<Integer, PrintedMark>();
        for (var mark : known) marks.put(mark.noteIndex(), mark);
        for (int i = 0; i < notes.size(); i++) {
            var n = notes.get(i);
            int m = n.measureIndex();
            if (marks.containsKey(i) || m < 0 || m >= measures.size()
                    || n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffCount() != 1
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL || n.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT
                    || n.beamCount() != 1 || Math.abs(n.stemDirection()) != 1
                    || n.tupletDivisor() != 3 || n.tupletNormalNotes() != 2
                    || n.tiedFromPrevious() || n.boundaryTies() != 0 || n.crossStaffBeam()
                    || n.harmonicSemitones() != 0 || n.openStringTouch() != null) continue;
            var box = measures.get(m);
            float gap = (box.bottom() - box.top()) * height / 8.2f;
            float span = (box.right() - box.left()) * width;
            float x = box.left() * width + n.positionInMeasure() * span, y = n.pageY() * height;
            if (i > 0 && notes.get(i - 1).measureIndex() == m
                    && (n.positionInMeasure() - notes.get(i - 1).positionInMeasure()) * span < gap * 2.15f)
                continue;
            int left = Math.round(x - 1.85f * gap), right = Math.round(x - .72f * gap);
            int top = Math.round(y - 1.80f * gap), bottom = Math.round(y + .85f * gap);
            var lower = i + 1 < notes.size() ? notes.get(i + 1) : null;
            float barLeft = box.left() * width;
            boolean paired = lower != null && lower.measureIndex() == m && lower.staffCount() == n.staffCount()
                    && lower.staffIndex() == n.staffIndex() && lower.stemDirection() == -1
                    && n.stemDirection() == -1 && lower.beamCount() == 1 && lower.tupletDivisor() == 3
                    && lower.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FLAT
                    && n.staffStep() - lower.staffStep() == 7
                    && Math.abs(n.positionInMeasure() - lower.positionInMeasure()) * span <= .22f * gap
                    && ClosedOctaveTripletBars.provedFarFlat(raw, gray, width, height, barLeft, span, gap, n)
                    && ClosedOctaveTripletBars.provedFarFlat(raw, gray, width, height, barLeft, span, gap, lower);
            if (paired || PrintedFlatGlyph.matchesOwnedInk(labels, raw, width, height, x, y, gap)
                    && PrintedFlatGlyph.matchesOwnedInk(labels, gray, width, height, x, y, gap)
                    && PrintedFlatGlyph.matches(raw, width, height, left, top, right, bottom, gap)
                    && PrintedFlatGlyph.matches(gray, width, height, left, top, right, bottom, gap))
                marks.put(i, new PrintedMark(i, ScoreNoteEvent.ACCIDENTAL_FLAT));
        }
        return marks.size() == known.size() ? known : List.copyOf(marks.values());
    }

    /** Keep separately printed flat and natural signs on both notes of a beamed octave. */
    static List<PrintedMark> retainBeamedOctaveAccidentals(List<PrintedMark> known, List<ScoreNoteEvent> notes,
            List<MeasureRegion> measures, byte[] labels, byte[] raw, byte[] gray, int width, int height) {
        if (labels == null || raw == null || gray == null || labels.length != (long) width * height
                || raw.length != labels.length || gray.length != labels.length) return known;
        var marks = new TreeMap<Integer, PrintedMark>();
        for (var mark : known) marks.put(mark.noteIndex(), mark);
        for (int i = 0; i + 1 < notes.size(); i++) {
            var a = notes.get(i);
            var b = notes.get(i + 1);
            int bar = a.measureIndex();
            int sign = a.writtenAccidental();
            if (sign != ScoreNoteEvent.ACCIDENTAL_FLAT && sign != ScoreNoteEvent.ACCIDENTAL_NATURAL
                    || b.writtenAccidental() != sign || !beamedOctaveHead(a) || !beamedOctaveHead(b)
                    || b.measureIndex() != bar
                    || bar < 0 || bar >= measures.size() || a.clefBottomDiatonic() != b.clefBottomDiatonic()
                    || Math.abs(a.staffStep() - b.staffStep()) != 7
                    || marks.containsKey(i) && marks.containsKey(i + 1)) continue;
            var box = measures.get(bar);
            float gap = (box.bottom() - box.top()) * height / 8.2f;
            float left = box.left() * width, span = (box.right() - box.left()) * width;
            float ax = left + a.positionInMeasure() * span, bx = left + b.positionInMeasure() * span;
            float ay = a.pageY() * height, by = b.pageY() * height;
            var prior = i > 0 ? notes.get(i - 1) : null;
            if (gap < 12 || gap > 24 || Math.abs(ax - bx) > .27f * gap
                    || Math.abs(Math.abs(ay - by) - 3.5f * gap) > .30f * gap
                    || prior != null && prior.measureIndex() == bar
                        && Math.min(ax, bx) - (left + prior.positionInMeasure() * span) < 2.32f * gap)
                continue;
            if (Math.abs(ax - bx) > .23f * gap
                    && (!sharedOctaveAccidentalShaft(raw, width, height, ax, ay, bx, by, gap)
                        || !sharedOctaveAccidentalShaft(gray, width, height, ax, ay, bx, by, gap)))
                continue;
            int firstLine = Math.round(box.bottom() * height - 6.1f * gap);
            if (!ownsOctaveAccidental(sign, labels, raw, width, height, ax, ay, gap, firstLine)
                    || !ownsOctaveAccidental(sign, labels, gray, width, height, ax, ay, gap, firstLine)
                    || !ownsOctaveAccidental(sign, labels, raw, width, height, bx, by, gap, firstLine)
                    || !ownsOctaveAccidental(sign, labels, gray, width, height, bx, by, gap, firstLine)) continue;
            marks.putIfAbsent(i, new PrintedMark(i, sign));
            marks.putIfAbsent(i + 1, new PrintedMark(i + 1, sign));
            i++;
        }
        return marks.size() == known.size() ? known : List.copyOf(marks.values());
    }

    /** Retain a detached natural on the upper head of a high beamed sixth with a separately owned sharp. */
    static List<PrintedMark> retainLedgerSixthNaturals(List<PrintedMark> known, List<ScoreNoteEvent> notes,
            List<MeasureRegion> measures, byte[] raw, byte[] gray, int width, int height) {
        if (raw == null || gray == null || raw.length != (long) width * height || gray.length != raw.length)
            return known;
        var marks = new TreeMap<Integer, PrintedMark>();
        for (var mark : known) marks.put(mark.noteIndex(), mark);
        for (var sign : known) {
            int lower = sign.noteIndex();
            if (sign.accidental() != ScoreNoteEvent.ACCIDENTAL_SHARP || lower < 1 || lower >= notes.size())
                continue;
            int upper = lower - 1;
            var low = notes.get(lower);
            var high = notes.get(upper);
            int bar = high.measureIndex();
            if (marks.containsKey(upper) || high.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_NATURAL
                    || low.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_SHARP
                    || high.staffStep() < 15 || high.staffStep() - low.staffStep() != 5
                    || low.measureIndex() != bar || bar < 0 || bar >= measures.size()
                    || high.clefBottomDiatonic() != low.clefBottomDiatonic()
                    || !beamedOctaveHead(high) || !beamedOctaveHead(low)) continue;
            var region = measures.get(bar);
            float span = (region.right() - region.left()) * width;
            float hx = region.left() * width + high.positionInMeasure() * span;
            float lx = region.left() * width + low.positionInMeasure() * span;
            float hy = high.pageY() * height, ly = low.pageY() * height;
            float gap = (region.bottom() - region.top()) * height / 8.2f;
            if (gap < 12 || gap > 24 || Math.abs(hx - lx) > .20f * gap
                    || Math.abs(ly - hy - 2.5f * gap) > .25f * gap) continue;
            int left = Math.round(hx - 2.06f * gap), right = Math.round(hx - .86f * gap);
            if (!OmrScoreInterpreter.provesBoundedNaturalInk(raw, width, height, hx, hy, gap, left, right)
                    || !OmrScoreInterpreter.provesBoundedNaturalInk(gray, width, height, hx, hy, gap, left, right)
                    || !sharedOctaveAccidentalShaft(raw, width, height, hx, hy, lx, ly, gap)
                    || !sharedOctaveAccidentalShaft(gray, width, height, hx, hy, lx, ly, gap)) continue;
            marks.put(upper, new PrintedMark(upper, ScoreNoteEvent.ACCIDENTAL_NATURAL));
        }
        return marks.size() == known.size() ? known : List.copyOf(marks.values());
    }

    private static boolean beamedOctaveHead(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED && note.staffIndex() == 0 && note.staffCount() == 1
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL && note.stemDirection() == -1
                && note.beamCount() == 2 && note.unbeamedDurationBeats() == 0 && note.tupletDivisor() == 1
                && !note.tiedFromPrevious() && note.boundaryTies() == 0 && !note.crossStaffBeam()
                && note.harmonicSemitones() == 0 && note.openStringTouch() == null;
    }

    private static boolean sharedOctaveAccidentalShaft(byte[] gray, int width, int height,
            float ax, float ay, float bx, float by, float gap) {
        var budget = new PrintedBeamShafts.Budget(60_000);
        var first = PrintedBeamShafts.at(gray, width, height, ax, ay, gap, budget);
        var second = PrintedBeamShafts.at(gray, width, height, bx, by, gap, budget);
        if (budget.refused() || first.size() != 1 || second.size() != 1) return false;
        var a = first.get(0);
        var b = second.get(0);
        return a.direction() == 1 && b.direction() == 1 && a.left() <= b.right() && b.left() <= a.right()
                && Math.abs(a.endY() - b.endY()) <= .28f * gap;
    }


    private static boolean ownsOctaveAccidental(int sign, byte[] labels, byte[] gray, int width, int height,
            float x, float y, float gap, int firstLine) {
        if (sign == ScoreNoteEvent.ACCIDENTAL_FLAT)
            return PrintedFlatGlyph.matchesOwnedInk(labels, gray, width, height, x, y, gap);
        int l = Math.round(x - 2.03f * gap), r = Math.round(x - .72f * gap);
        int top = Math.round(y - 2.1f * gap), bottom = Math.round(y + 2.1f * gap);
        int left = l - Math.round(.19f * gap), right = r + Math.round(.125f * gap);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return false;
        int w = right - left + 1, h = bottom - top + 1;
        byte[] glyph = new byte[w * h];
        java.util.Arrays.fill(glyph, (byte) 255);
        for (int row = 0; row < h; row++)
            for (int col = 0; col < w; col++) {
                int at = (row + top) * width + left + col;
                if (labels[at] == OmrMeasurePostProcessor.CLEF_OR_KEY) glyph[row * w + col] = gray[at];
            }
        for (int a = 0; a <= l - left + Math.round(.375f * gap); a++)
            for (int b = r - left - Math.round(.31f * gap); b < w; b++)
                if (OmrScoreInterpreter.provesNaturalMeterInk(
                        glyph, w, h, a, b, y - top, gap, firstLine - top)) return true;
        return false;
    }

    private boolean ambiguousVerticalOwner(
            Choice choice, ScoreNoteEvent note, List<MeasureRegion> measures,
            Map<Integer, List<ScoreNoteEvent>> notesByMeasure,
            Map<Integer, Glyph> originals, NavigableMap<Integer, List<Glyph>> byRow) {
        int measure = note.measureIndex(), head = choice.head();
        if (note.staffCount() != 1 || measure < 0 || measure >= measures.size()
                || head < 0 || head >= headAlternatives.size()) return false;
        var region = measures.get(measure);
        float gap = (region.bottom() - region.top()) * height / 7.4f;
        if (gap < 6 || gap > 36) return false;
        var source = headAlternatives.get(head);
        if (source.pixels().isEmpty() || !source.unresolvedParentResidual().isEmpty()) return false;
        // Split children have their own compact footprint. Only a tall unresolved selected head
        // can straddle both accidental pitch lanes.
        int left = width, right = -1, top = height, bottom = -1;
        for (int pixel : source.pixels()) {
            left = Math.min(left, pixel % width);
            right = Math.max(right, pixel % width);
            top = Math.min(top, pixel / width);
            bottom = Math.max(bottom, pixel / width);
        }
        float span = bottom - top + 1f;
        float headY = note.pageY() * height;
        if (span < 1.9f * gap || span > 2.45f * gap
                || headY - top < .4f * span || headY - top > .6f * span) return false;
        for (var other : notesByMeasure.getOrDefault(measure, List.of())) {
            if (other == note) continue;
            float x = (region.left() + other.positionInMeasure() * (region.right() - region.left()))
                    * width;
            float y = other.pageY() * height;
            if (x >= left && x <= right && y >= top && y <= bottom) return false;
        }
        float center = (top + bottom) / 2f;
        var selected = originals.get(choice.glyph());
        if (!offsetGlyphLane(selected, left, center, gap)) return false;
        int minRow = (int) Math.floor(center - .7f * gap);
        int maxRow = (int) Math.ceil(center + .7f * gap);
        int localBudget = 256;
        for (var row : byRow.subMap(minRow, true, maxRow, true).values())
            for (var glyph : row) {
                if (--localBudget < 0) return false;
                if (otherVerticalGlyphLane(selected, glyph, left, center, gap)) return true;
            }
        return false;
    }

    static boolean otherVerticalGlyphLane(Glyph selected, Glyph other, int left, float center, float gap) {
        if (other.index() == selected.index() || !offsetGlyphLane(other, left, center, gap))
            return false;
        var a = selected.stats();
        var b = other.stats();
        float dy = Math.abs(a.centerY() - b.centerY());
        float dx = Math.abs(a.centerX() - b.centerX());
        return (a.centerY() < center) != (b.centerY() < center)
                && dy >= .8f * gap && dy <= 1.2f * gap
                && dx >= .6f * gap && dx <= 1.3f * gap
                && (a.maxX() < b.minX() || b.maxX() < a.minX());
    }

    private static boolean offsetGlyphLane(Glyph glyph, int left, float center, float gap) {
        if (glyph.label() != 3 && glyph.label() != 5) return false;
        var stats = glyph.stats();
        float dx = left - stats.maxX(), dy = Math.abs(stats.centerY() - center);
        float w = stats.maxX() - stats.minX() + 1f, h = stats.maxY() - stats.minY() + 1f;
        return dx >= 0 && dx <= 1.3f * gap
                && dy >= .3f * gap && dy <= .7f * gap
                && w >= .65f * gap && w <= 1.25f * gap
                && h >= 2.2f * gap && h <= 3.2f * gap
                && stats.area() >= .65f * gap * gap && stats.area() <= 1.5f * gap * gap;
    }

    Role role(
            AcceptedMeterHeaderBounds.Source source,
            MeterChangeDetector.Crop crop,
            int measure,
            List<ScoreNoteEvent> notes) {
        if (issuer != ISSUER
                || !complete
                || source == null
                || crop == null
                || !source.sameSnapshot()
                || source.width != width
                || source.height != height
                || !source.rawSha.equals(rawSha)
                || !source.labelSha.equals(labelSha)
                || crop.left() < 0
                || crop.right() >= width
                || crop.top() < 0
                || crop.bottom() >= height) return null;
        var covered = new BitSet(width * height);
        var used = new ArrayList<Integer>();
        boolean found = false;
        for (var glyph : glyphs) {
            var s = glyph.stats();
            if (s.maxX() < crop.left()
                    || s.minX() > crop.right()
                    || s.maxY() < crop.top()
                    || s.minY() > crop.bottom()) continue;
            int matchingOwners = 0;
            for (var choice : choices) {
                if (choice.glyph() != glyph.index()
                        || choice.ambiguous()
                        || !choice.status().equals(SUPPORTED)
                        || choice.note() < 0
                        || choice.note() >= notes.size()) continue;
                var note = notes.get(choice.note());
                if (!note.equals(choice.originalNote())
                        || note.measureIndex() != measure
                        || note.writtenAccidental() != choice.accidental()) continue;
                if (notes.stream().filter(candidate -> choice.originalNote().equals(candidate)).count() != 1) return null;
                matchingOwners++;
            }
            if (matchingOwners != 1) return null;
            found = true;
            for (int pixel : glyph.pixels()) {
                covered.set(pixel);
                used.add(pixel);
            }
        }
        if (!found) return null;
        // Even discarded one/two-pixel class3/5 islands remain contrary/unexplained evidence.
        for (int y = crop.top(); y <= crop.bottom(); y++)
            for (int x = crop.left(); x <= crop.right(); x++) {
                int at = y * width + x, label = source.labels[at] & 255;
                if ((label == 3 || label == 5) && !covered.get(at)) return null;
            }
        return new Role(used);
    }
}
