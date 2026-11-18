// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Private, document-scoped pipe used by the Python PDF reader. */
public final class DocumentPageDecoder {
    private DocumentPageDecoder() {}

    public static void main(String[] args) throws Exception {
        try (var in = new DataInputStream(new BufferedInputStream(System.in))) {
            for (int count; (count = in.read()) != -1;) {
                if (count < 5 || count > 11 || (count & 1) == 0)
                    throw new IOException("Invalid document page arguments");
                String[] page = new String[count];
                for (int i = 0; i < count; i++) {
                    int length = in.readInt();
                    if (length < 0 || length > 1024 * 1024)
                        throw new IOException("Invalid document page argument length");
                    byte[] value = new byte[length];
                    in.readFully(value);
                    page[i] = new String(value, StandardCharsets.UTF_8);
                }
                Main.main(page);
                System.out.write(1);
                System.out.flush();
            }
        }
    }
}
