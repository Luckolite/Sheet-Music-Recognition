// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Authenticated, source-matched local OCR transport. Never accepts file paths. */
public final class NativeOcrWire {
    public static final int PORT = 45925;
    private static final int MAGIC = 0x4f435231, MAX_PIXELS = 20_000_000, MAX_PACKET = 32_000_000;
    private static final int GZIP_BUFFER_BYTES = 32 * 1024;

    private NativeOcrWire() {}

    public record Raster(int width, int height, int[] pixels) {}

    public static OcrText exchange(
            String host, String token, String source, int[] pixels, int width, int height)
            throws IOException {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, PORT), 1000);
            socket.setSoTimeout(30_000);
            var out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            var in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out.writeInt(MAGIC);
            out.writeUTF(token);
            out.writeUTF(source);
            out.flush();
            if (in.readUnsignedByte() != 0) throw new IOException("OCR worker identity rejected");
            writeRaster(out, pixels, width, height);
            out.flush();
            return readText(in);
        }
    }

    public static boolean authenticate(
            DataInputStream in, DataOutputStream out, String token, String source)
            throws IOException {
        boolean valid = in.readInt() == MAGIC;
        String supplied = in.readUTF(), suppliedSource = in.readUTF();
        valid &= same(token, supplied) && same(source, suppliedSource);
        out.writeByte(valid ? 0 : 1);
        out.flush();
        return valid;
    }

    private static boolean same(String expected, String supplied) {
        return expected != null
                && expected.matches("[a-fA-F0-9]{64}")
                && MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.US_ASCII),
                        supplied.getBytes(StandardCharsets.US_ASCII));
    }

    public static void writeRaster(DataOutputStream out, int[] pixels, int width, int height)
            throws IOException {
        if (width < 1
                || height < 1
                || (long) width * height > MAX_PIXELS
                || pixels == null
                || pixels.length != (long) width * height)
            throw new IOException("Invalid OCR raster");
        writePacket(
                out,
                data -> {
                    data.writeInt(width);
                    data.writeInt(height);
                    var bytes = rasterChunk(pixels.length);
                    var integers = bytes.asIntBuffer();
                    for (int offset = 0; offset < pixels.length; ) {
                        int count = Math.min(integers.capacity(), pixels.length - offset);
                        integers.clear();
                        integers.put(pixels, offset, count);
                        data.write(bytes.array(), 0, count * Integer.BYTES);
                        offset += count;
                    }
                });
    }

    public static Raster readRaster(DataInputStream in) throws IOException {
        try (var data = packet(in, MAX_PIXELS * 4L + 8)) {
            int width = data.readInt(), height = data.readInt();
            if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS)
                throw new IOException("Invalid OCR dimensions");
            int[] pixels = new int[width * height];
            var bytes = rasterChunk(pixels.length);
            var integers = bytes.asIntBuffer();
            for (int offset = 0; offset < pixels.length; ) {
                int count = Math.min(integers.capacity(), pixels.length - offset);
                data.readFully(bytes.array(), 0, count * Integer.BYTES);
                integers.clear();
                integers.get(pixels, offset, count);
                offset += count;
            }
            if (data.read() != -1) throw new IOException("Trailing OCR raster data");
            return new Raster(width, height, pixels);
        }
    }

    private static ByteBuffer rasterChunk(int pixels) {
        return ByteBuffer.allocate(Math.min(4096, pixels) * Integer.BYTES);
    }

    public static void writeText(DataOutputStream out, OcrText text) throws IOException {
        writePacket(
                out,
                data -> {
                    var bounds = ByteBuffer.allocate(4 * Integer.BYTES);
                    data.writeUTF(text.text());
                    data.writeInt(text.blocks().size());
                    for (var block : text.blocks()) {
                        data.writeUTF(block.text());
                        writeBox(data, block.box(), bounds);
                        data.writeInt(block.lines().size());
                        for (var line : block.lines()) {
                            data.writeUTF(line.text());
                            writeBox(data, line.box(), bounds);
                            data.writeInt(line.elements().size());
                            for (var word : line.elements()) {
                                data.writeUTF(word.text());
                                writeBox(data, word.box(), bounds);
                                data.writeInt(word.symbols().size());
                                for (var symbol : word.symbols()) {
                                    data.writeUTF(symbol.text());
                                    writeBox(data, symbol.box(), bounds);
                                }
                            }
                        }
                    }
                });
    }

    public static OcrText readText(DataInputStream input) throws IOException {
        try (var in = packet(input, 8_000_000)) {
            int[] budget = {20_000};
            var bounds = ByteBuffer.allocate(4 * Integer.BYTES);
            String text = in.readUTF();
            int nb = count(in, budget);
            var blocks = new ArrayList<OcrText.Block>(nb);
            for (int b = 0; b < nb; b++) {
                String bt = in.readUTF();
                var bb = readBox(in, bounds);
                int nl = count(in, budget);
                var lines = new ArrayList<OcrText.Line>(nl);
                for (int l = 0; l < nl; l++) {
                    String lt = in.readUTF();
                    var lb = readBox(in, bounds);
                    int nw = count(in, budget);
                    var words = new ArrayList<OcrText.Element>(nw);
                    for (int w = 0; w < nw; w++) {
                        String wt = in.readUTF();
                        var wb = readBox(in, bounds);
                        int ns = count(in, budget);
                        var symbols = new ArrayList<OcrText.Symbol>(ns);
                        for (int s = 0; s < ns; s++)
                            symbols.add(new OcrText.Symbol(in.readUTF(), readBox(in, bounds)));
                        words.add(new OcrText.Element(wt, wb, symbols));
                    }
                    lines.add(new OcrText.Line(lt, lb, words));
                }
                blocks.add(new OcrText.Block(bt, bb, lines));
            }
            if (in.read() != -1) throw new IOException("Trailing OCR evidence");
            return new OcrText(text, blocks);
        }
    }

    private static int count(DataInputStream in, int[] budget) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > budget[0]) throw new IOException("Excessive OCR evidence");
        budget[0] -= count;
        return count;
    }

    private static void writeBox(DataOutputStream out, OcrText.Box box, ByteBuffer bounds)
            throws IOException {
        out.writeBoolean(box != null);
        if (box != null) {
            bounds.clear();
            bounds.putInt(box.left).putInt(box.top).putInt(box.right).putInt(box.bottom);
            out.write(bounds.array());
        }
    }

    private static OcrText.Box readBox(DataInputStream in, ByteBuffer bounds) throws IOException {
        if (!in.readBoolean()) return null;
        in.readFully(bounds.array());
        bounds.clear();
        int l = bounds.getInt(), t = bounds.getInt(), r = bounds.getInt(), b = bounds.getInt();
        if (l < 0 || t < 0 || r < l || b < t || r > MAX_PIXELS || b > MAX_PIXELS)
            throw new IOException("Invalid OCR bounds");
        return new OcrText.Box(l, t, r, b);
    }

    private interface Writer {
        void write(DataOutputStream data) throws IOException;
    }

    private static void writePacket(DataOutputStream out, Writer writer) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var gzip =
                        new GZIPOutputStream(
                                new FilterOutputStream(bytes) {
                                    private int written;

                                    public void write(int b) throws IOException {
                                        if (++written > MAX_PACKET)
                                            throw new IOException("Oversize OCR packet");
                                        this.out.write(b);
                                    }

                                    public void write(byte[] b, int off, int len)
                                            throws IOException {
                                        if (len > MAX_PACKET - written)
                                            throw new IOException("Oversize OCR packet");
                                        written += len;
                                        this.out.write(b, off, len);
                                    }
                                });
                var data = new DataOutputStream(new BufferedOutputStream(gzip))) {
            writer.write(data);
        }
        out.writeInt(bytes.size());
        bytes.writeTo(out);
    }

    private static DataInputStream packet(DataInputStream in, long maximum) throws IOException {
        int length = in.readInt();
        if (length < 1 || length > MAX_PACKET) throw new IOException("Invalid OCR packet length");
        byte[] compressed = new byte[length];
        in.readFully(compressed);
        var gzip =
                new GZIPInputStream(
                        new ByteArrayInputStream(compressed), Math.min(length, GZIP_BUFFER_BYTES));
        return new DataInputStream(
                new BufferedInputStream(
                        new FilterInputStream(gzip) {
                            private long remaining = maximum;

                            public int read() throws IOException {
                                int value = this.in.read();
                                if (value >= 0 && --remaining < 0)
                                    throw new IOException("OCR expansion limit");
                                return value;
                            }

                            public int read(byte[] b, int off, int len) throws IOException {
                                int n = this.in.read(b, off, (int) Math.min(len, remaining + 1));
                                if (n > 0 && (remaining -= n) < 0)
                                    throw new IOException("OCR expansion limit");
                                return n;
                            }
                        }));
    }
}
