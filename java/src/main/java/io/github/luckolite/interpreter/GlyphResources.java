// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Immutable, lazily loaded recognition templates bundled with the standalone JAR. */
final class GlyphResources {
    private GlyphResources() {}

    static BufferedImage image(String relative) throws IOException {
        try (var input = GlyphResources.class.getResourceAsStream("glyphs/" + relative)) {
            if (input == null) throw new IOException("Missing bundled glyph template: " + relative);
            // Bundled PNGs are small. Keep ImageIO seek operations in memory.
            var memory = new MemoryCacheImageInputStream(input);
            var image = ImageIO.read(memory);
            if (image == null) {
                memory.close(); // ImageIO closes the stream only when a reader accepts it.
                throw new IOException("Invalid bundled glyph template: " + relative);
            }
            return image;
        }
    }

    private static final class DynamicHolder {
        static final NativeDynamicGlyphs INSTANCE = new NativeDynamicGlyphs();
    }

    private static final class OrnamentHolder {
        static final NativeOrnamentGlyphs INSTANCE = create();

        private static NativeOrnamentGlyphs create() {
            try {
                return new NativeOrnamentGlyphs();
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot load ornament templates", failure);
            }
        }
    }

    static NativeDynamicGlyphs dynamics() {
        return DynamicHolder.INSTANCE;
    }

    private static final class OrnamentMatcher {
        static final PortableOrnamentGlyphs INSTANCE =
                new PortableOrnamentGlyphs(() -> OrnamentHolder.INSTANCE.portable());
    }

    static PortableOrnamentGlyphs ornaments() {
        return OrnamentMatcher.INSTANCE;
    }

    private static final class ExpressionHolder {
        static final PrintedExpressionGlyphs INSTANCE = create();

        private static PrintedExpressionGlyphs create() {
            try (var input =
                    GlyphResources.class.getResourceAsStream(
                            "glyphs/expression_templates/glyphs.bin")) {
                if (input == null) throw new IOException("Missing expression templates");
                return PrintedExpressionGlyphs.load(input);
            } catch (IOException error) {
                throw new IllegalStateException("Cannot load expression templates", error);
            }
        }
    }

    static PrintedExpressionGlyphs expressions() {
        return ExpressionHolder.INSTANCE;
    }

    private static final class PedalHolder {
        static final PrintedPedalGlyphs INSTANCE = create();

        private static PrintedPedalGlyphs create() {
            try (var input =
                    GlyphResources.class.getResourceAsStream("glyphs/pedal_templates/glyphs.bin")) {
                if (input == null) throw new IOException("Missing pedal templates");
                return PrintedPedalGlyphs.load(input);
            } catch (IOException error) {
                throw new IllegalStateException("Cannot load pedal templates", error);
            }
        }
    }

    static PrintedPedalGlyphs pedals() {
        return PedalHolder.INSTANCE;
    }
}
