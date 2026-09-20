package com.googlecode.javaewah32;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * 32-bit counterpart of {@link com.googlecode.javaewah.Understanding64Test}.
 * The class name ends with "Understanding", so it is selected by
 * {@code mvn -q -Dtest='*Understanding*' test}.
 */
public final class Understanding32Test {

    private static final int W = EWAHCompressedBitmap32.WORD_IN_BITS; // 32

    static EWAHCompressedBitmap32 buildA() {
        final EWAHCompressedBitmap32 b = new EWAHCompressedBitmap32();
        b.addStreamOfEmptyWords(false, 4);
        b.addLiteralWord(1 << 5);
        b.addLiteralWord((1 << 10) - 1);
        b.addLiteralWord(1);
        b.setSizeInBitsWithinLastWord(6 * W + 10);
        return b;
    }

    static EWAHCompressedBitmap32 buildB() {
        final EWAHCompressedBitmap32 b = new EWAHCompressedBitmap32();
        b.addStreamOfEmptyWords(false, 2);
        b.addLiteralWord(1);
        b.addStreamOfEmptyWords(false, 3);
        b.addLiteralWord((1 << 20) - 1);
        b.setSizeInBitsWithinLastWord(6 * W + 20);
        return b;
    }

    static byte[] serialize(final EWAHCompressedBitmap32 b) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.serialize(new DataOutputStream(bos));
        return bos.toByteArray();
    }

    static EWAHCompressedBitmap32 deserialize(final byte[] bytes)
            throws IOException {
        final EWAHCompressedBitmap32 b = new EWAHCompressedBitmap32();
        b.deserialize(new DataInputStream(new ByteArrayInputStream(bytes)));
        return b;
    }

    static final class RlwSnapshot {
        final int position;
        final boolean runningBit;
        final int runningLength;
        final int literalCount;
        final int[] literals;

        RlwSnapshot(final int position, final boolean runningBit,
                    final int runningLength, final int literalCount,
                    final int[] literals) {
            this.position = position;
            this.runningBit = runningBit;
            this.runningLength = runningLength;
            this.literalCount = literalCount;
            this.literals = literals;
        }

        int representedWords() {
            return this.runningLength + this.literalCount;
        }
    }

    static List<RlwSnapshot> snapshot(final EWAHCompressedBitmap32 b) {
        final List<RlwSnapshot> out = new ArrayList<RlwSnapshot>();
        final EWAHIterator32 it = b.getEWAHIterator();
        while (it.hasNext()) {
            final RunningLengthWord32 r = it.next();
            final int[] literals = new int[r.getNumberOfLiteralWords()];
            for (int k = 0; k < literals.length; ++k) {
                literals[k] = it.buffer().getWord(it.literalWords() + k);
            }
            out.add(new RlwSnapshot(r.position, r.getRunningBit(),
                    r.getRunningLength(), r.getNumberOfLiteralWords(),
                    literals));
        }
        return out;
    }

    static int representedWordTotal(final List<RlwSnapshot> rlws) {
        int total = 0;
        for (final RlwSnapshot s : rlws) {
            total += s.representedWords();
        }
        return total;
    }

    static List<Integer> reverseBits(final EWAHCompressedBitmap32 b) {
        final List<Integer> out = new ArrayList<Integer>();
        for (final com.googlecode.javaewah.IntIterator it =
                     b.reverseIntIterator(); it.hasNext();) {
            out.add(it.next());
        }
        return out;
    }

    @Test
    public void testStateTableAndInvariants32() {
        final EWAHCompressedBitmap32 a = buildA();
        final EWAHCompressedBitmap32 b = buildB();
        final EWAHCompressedBitmap32 or = a.or(b);
        final EWAHCompressedBitmap32 and = a.and(b);

        final List<RlwSnapshot> sa = snapshot(a);
        assertEquals(1, sa.size());
        assertFalse(sa.get(0).runningBit);
        assertEquals(4, sa.get(0).runningLength);
        assertEquals(3, sa.get(0).literalCount);
        assertArrayEquals32(new int[]{1 << 5, (1 << 10) - 1, 1},
                sa.get(0).literals);
        assertEquals(7, representedWordTotal(sa));
        assertEquals((a.sizeInBits() + W - 1) / W, representedWordTotal(sa));
        assertEquals(6 * W + 10, a.sizeInBits());
        assertEquals(28, a.serializedSizeInBytes());

        final List<RlwSnapshot> so = snapshot(or);
        assertEquals(2, so.size());
        assertEquals(2, so.get(0).runningLength);
        assertEquals(1, so.get(0).literalCount);
        assertEquals(1, so.get(1).runningLength);
        assertEquals(3, so.get(1).literalCount);
        assertArrayEquals32(new int[]{1 << 5, (1 << 10) - 1,
                (1 << 20) - 1}, so.get(1).literals);
        assertEquals(7, representedWordTotal(so));
        assertEquals(6 * W + 20, or.sizeInBits());
        assertEquals(36, or.serializedSizeInBytes());

        final List<RlwSnapshot> san = snapshot(and);
        assertEquals(1, san.size());
        assertEquals(6, san.get(0).runningLength);
        assertEquals(1, san.get(0).literalCount);
        assertArrayEquals32(new int[]{1}, san.get(0).literals);
        assertEquals(6 * W + 20, and.sizeInBits());
        assertEquals(20, and.serializedSizeInBytes());

        // positions and cardinality, not just cardinality
        assertEquals(32, or.cardinality());
        assertEquals(Integer.valueOf(64), or.toList().get(0));
        assertEquals(Integer.valueOf(211),
                or.toList().get(or.toList().size() - 1));
        assertEquals(Arrays.asList(192), and.toList());
    }

    @Test
    public void testLastWordMasking32() {
        final int mask10 = (~0) >>> (W - 10);
        final int mask20 = (~0) >>> (W - 20);
        assertEquals(1, snapshot(buildA()).get(0).literals[2]);
        assertEquals(1, snapshot(buildA()).get(0).literals[2] & mask10);
        assertEquals(mask20, snapshot(buildB()).get(1).literals[0]);
        assertEquals(mask20,
                snapshot(buildA().or(buildB())).get(1).literals[2]);
    }

    private static void assertArrayEquals32(final int[] expected,
                                            final int[] actual) {
        assertEquals(expected.length, actual.length);
        for (int k = 0; k < expected.length; ++k) {
            assertEquals(expected[k], actual[k]);
        }
    }

    /* ===================== Risk 1: aggregated input aliasing ===================== */

    @Test
    public void testRisk1AggregationInputAliasing32() throws Exception {
        final EWAHCompressedBitmap32 a = buildA();
        final EWAHCompressedBitmap32 b = buildB();
        // aliasing the destination with an input source corrupts the result
        a.orToContainer(b, a);
        assertNotEquals(buildA().or(buildB()), a);
        assertEquals(Arrays.asList(64, 192, 193, 194, 195, 196, 197, 198, 199,
                200, 201, 202, 203, 204, 205, 206, 207, 208, 209, 210, 211),
                a.toList());
    }

    /* ===================== Risk 2: truncated serialization ===================== */

    @Test
    public void testRisk2TruncatedSerializationThrows32() throws IOException {
        final byte[] wire = serialize(buildA());
        assertEquals(28, wire.length);
        final byte[] missingTail = Arrays.copyOf(wire, wire.length - 4);
        try {
            deserialize(missingTail);
            org.junit.Assert.fail("expected EOFException");
        } catch (final EOFException expected) {
            // observable failure mode
        }
    }

    /* ===================== Risk 3: non-zero ByteBuffer position ===================== */

    @Test
    public void testRisk3ByteBufferMustHaveZeroPosition32() throws IOException {
        final EWAHCompressedBitmap32 a =
                EWAHCompressedBitmap32.bitmapOf(0, 32, 300);
        final byte[] wire = serialize(a);
        final ByteBuffer wrongPosition = ByteBuffer.wrap(wire);
        wrongPosition.position(8);
        final EWAHCompressedBitmap32 misread =
                new EWAHCompressedBitmap32(wrongPosition);
        assertNotEquals(a.sizeInBits(), misread.sizeInBits());
        assertFalse(misread.equals(a));

        final ByteBuffer correct = ByteBuffer.wrap(wire);
        assertTrue(new EWAHCompressedBitmap32(correct).equals(a));
    }

    /* ===================== Risk 4: reverse iteration contract ===================== */

    @Test
    public void testRisk4UnmaskedTailDivergesForwardFromReverse32() {
        // addWord(int, bitsThatMatter) stores the literal verbatim. The
        // raw intIterator trusts the stored word and reports the
        // out-of-range bit 63; both the reverse iterator and the
        // toList() tail filter are bounded by sizeInBits (38).
        final EWAHCompressedBitmap32 bad = new EWAHCompressedBitmap32();
        bad.addStreamOfEmptyWords(false, 1);
        bad.addWord((1 << 5) | (1 << 31), 6);

        final List<Integer> rawForward = new ArrayList<Integer>();
        for (final com.googlecode.javaewah.IntIterator it = bad.intIterator();
             it.hasNext();) {
            rawForward.add(it.next());
        }
        assertEquals(Arrays.asList(37, 63), rawForward);
        assertEquals(Arrays.asList(37), bad.toList());
        assertEquals(Arrays.asList(37), reverseBits(bad));
        assertEquals(2, bad.cardinality());

        final EWAHCompressedBitmap32 good = new EWAHCompressedBitmap32();
        good.addStreamOfEmptyWords(false, 1);
        good.addWord(1 << 5, 6);
        assertEquals(Arrays.asList(37), good.toList());
        assertEquals(Arrays.asList(37), reverseBits(good));
    }

    /* ===================== Risk 5: empty bitmap ===================== */

    @Test
    public void testRisk5EmptyBitmapInvariants32() throws IOException {
        final EWAHCompressedBitmap32 empty = new EWAHCompressedBitmap32();
        assertEquals(0, empty.sizeInBits());
        assertEquals(0, empty.cardinality());
        assertEquals(4, empty.sizeInBytes());
        assertEquals(16, empty.serializedSizeInBytes());
        assertTrue(empty.toList().isEmpty());

        final List<RlwSnapshot> snaps = snapshot(empty);
        assertEquals(1, snaps.size());
        assertEquals(0, snaps.get(0).representedWords());

        final byte[] wire = serialize(empty);
        assertEquals(16, wire.length);
        final EWAHCompressedBitmap32 roundTripped = deserialize(wire);
        assertEquals(0, roundTripped.sizeInBits());
        assertEquals(empty, roundTripped);

        assertEquals(buildA(), empty.or(buildA()));
        assertEquals(0, empty.and(buildA()).cardinality());
        assertEquals(buildA().sizeInBits(),
                empty.and(buildA()).sizeInBits());
    }
}
