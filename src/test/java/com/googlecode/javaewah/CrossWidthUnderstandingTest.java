package com.googlecode.javaewah;

import com.googlecode.javaewah32.EWAHCompressedBitmap32;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Minimal cross-implementation invariant required by ANALYSIS.md.
 *
 * The same logical 7-word bitmap (long zero run, two literals and a
 * partial tail) is built in both the 64-bit ({@code EWAHCompressedBitmap})
 * and 32-bit ({@code EWAHCompressedBitmap32}) packages and combined with
 * OR. The test asserts, for both widths:
 * <ul>
 *   <li>cardinality (must be identical: 32),</li>
 *   <li>the concrete bit positions (scaled by the word width, not merely
 *       the count), and</li>
 *   <li>a full serialize/deserialize + ByteBuffer round trip whose
 *       positions and cardinality are preserved.</li>
 * </ul>
 * Class name ends with "Understanding" so {@code mvn -Dtest='*Understanding*'}
 * selects it.
 */
public final class CrossWidthUnderstandingTest {

    static EWAHCompressedBitmap a64() {
        final EWAHCompressedBitmap b = new EWAHCompressedBitmap();
        b.addStreamOfEmptyWords(false, 4);
        b.addLiteralWord(1L << 5);
        b.addLiteralWord((1L << 10) - 1);
        b.addLiteralWord(1L);
        b.setSizeInBitsWithinLastWord(6 * 64 + 10);
        return b;
    }

    static EWAHCompressedBitmap b64() {
        final EWAHCompressedBitmap b = new EWAHCompressedBitmap();
        b.addStreamOfEmptyWords(false, 2);
        b.addLiteralWord(1L);
        b.addStreamOfEmptyWords(false, 3);
        b.addLiteralWord((1L << 20) - 1);
        b.setSizeInBitsWithinLastWord(6 * 64 + 20);
        return b;
    }

    static EWAHCompressedBitmap32 a32() {
        final EWAHCompressedBitmap32 b = new EWAHCompressedBitmap32();
        b.addStreamOfEmptyWords(false, 4);
        b.addLiteralWord(1 << 5);
        b.addLiteralWord((1 << 10) - 1);
        b.addLiteralWord(1);
        b.setSizeInBitsWithinLastWord(6 * 32 + 10);
        return b;
    }

    static EWAHCompressedBitmap32 b32() {
        final EWAHCompressedBitmap32 b = new EWAHCompressedBitmap32();
        b.addStreamOfEmptyWords(false, 2);
        b.addLiteralWord(1);
        b.addStreamOfEmptyWords(false, 3);
        b.addLiteralWord((1 << 20) - 1);
        b.setSizeInBitsWithinLastWord(6 * 32 + 20);
        return b;
    }

    static byte[] ser64(final EWAHCompressedBitmap b) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.serialize(new DataOutputStream(bos));
        return bos.toByteArray();
    }

    static byte[] ser32(final EWAHCompressedBitmap32 b) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.serialize(new DataOutputStream(bos));
        return bos.toByteArray();
    }

    static List<Integer> pos64(final EWAHCompressedBitmap b) {
        return new ArrayList<Integer>(b.toList());
    }

    static List<Integer> pos32(final EWAHCompressedBitmap32 b) {
        return new ArrayList<Integer>(b.toList());
    }

    /**
     * The shared invariant: same logical bits modulo the word width,
     * same cardinality, and both wire formats round trip exactly.
     */
    @Test
    public void orPositionsCardinalityAndRoundTripAcrossWidths()
            throws IOException {
        final EWAHCompressedBitmap or64 = a64().or(b64());
        final EWAHCompressedBitmap32 or32 = a32().or(b32());

        final List<Integer> expected64 = pos64(or64);
        final List<Integer> expected32 = pos32(or32);

        // cardinality is width-independent for this construction
        assertEquals(32, or64.cardinality());
        assertEquals(or64.cardinality(), or32.cardinality());
        assertEquals(expected64.size(), expected32.size());

        // concrete positions: each 64-bit word index maps to two 32-bit
        // words, so the numeric positions differ but stay width-aligned
        assertEquals(Integer.valueOf(128), expected64.get(0));
        assertEquals(Integer.valueOf(64), expected32.get(0));
        assertEquals(Integer.valueOf(403),
                expected64.get(expected64.size() - 1));
        assertEquals(Integer.valueOf(211),
                expected32.get(expected32.size() - 1));

        // serialized size formulas: 64-bit = 12 + 8*words + 4,
        // 32-bit = 8 + 4*words + 4 (two ints header, words, one int tail)
        final byte[] w64 = ser64(or64);
        final byte[] w32 = ser32(or32);
        assertEquals(60, w64.length);
        assertEquals(36, w32.length);
        assertEquals(or64.serializedSizeInBytes(), w64.length);
        assertEquals(or32.serializedSizeInBytes(), w32.length);

        // deserialize round trip: positions and cardinality preserved
        final EWAHCompressedBitmap r64 = new EWAHCompressedBitmap();
        r64.deserialize(new DataInputStream(new ByteArrayInputStream(w64)));
        assertEquals(or64, r64);
        assertEquals(expected64, pos64(r64));
        assertEquals(32, r64.cardinality());

        final EWAHCompressedBitmap32 r32 = new EWAHCompressedBitmap32();
        r32.deserialize(new DataInputStream(new ByteArrayInputStream(w32)));
        assertEquals(or32, r32);
        assertEquals(expected32, pos32(r32));
        assertEquals(32, r32.cardinality());

        // memory-mapped view of the same bytes behaves identically
        assertEquals(or64, new EWAHCompressedBitmap(ByteBuffer.wrap(w64)));
        assertEquals(expected32,
                pos32(new EWAHCompressedBitmap32(ByteBuffer.wrap(w32))));
    }
}
