package com.googlecode.javaewah;

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
 * Acceptance test backing the claims of ANALYSIS.md (64-bit side).
 *
 * The class name ends with "Understanding", so it is selected by
 * {@code mvn -q -Dtest='*Understanding*' test}.
 */
public final class Understanding64Test {

    private static final int W = EWAHCompressedBitmap.WORD_IN_BITS; // 64

    static EWAHCompressedBitmap buildA() {
        final EWAHCompressedBitmap b = new EWAHCompressedBitmap();
        b.addStreamOfEmptyWords(false, 4);
        b.addLiteralWord(1L << 5);
        b.addLiteralWord((1L << 10) - 1);
        b.addLiteralWord(1L);
        b.setSizeInBitsWithinLastWord(6 * W + 10);
        return b;
    }

    static EWAHCompressedBitmap buildB() {
        final EWAHCompressedBitmap b = new EWAHCompressedBitmap();
        b.addStreamOfEmptyWords(false, 2);
        b.addLiteralWord(1L);
        b.addStreamOfEmptyWords(false, 3);
        b.addLiteralWord((1L << 20) - 1);
        b.setSizeInBitsWithinLastWord(6 * W + 20);
        return b;
    }

    static byte[] serialize(final EWAHCompressedBitmap b) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.serialize(new DataOutputStream(bos));
        return bos.toByteArray();
    }

    static EWAHCompressedBitmap deserialize(final byte[] bytes)
            throws IOException {
        final EWAHCompressedBitmap b = new EWAHCompressedBitmap();
        b.deserialize(new DataInputStream(new ByteArrayInputStream(bytes)));
        return b;
    }

    static List<Integer> forwardBits(final EWAHCompressedBitmap b) {
        final List<Integer> out = new ArrayList<Integer>();
        for (final IntIterator it = b.intIterator(); it.hasNext();) {
            out.add(it.next());
        }
        return out;
    }

    static List<Integer> reverseBits(final EWAHCompressedBitmap b) {
        final List<Integer> out = new ArrayList<Integer>();
        for (final IntIterator it = b.reverseIntIterator(); it.hasNext();) {
            out.add(it.next());
        }
        return out;
    }

    static final class RlwSnapshot {
        final int position;
        final boolean runningBit;
        final long runningLength;
        final int literalCount;
        final long[] literals;

        RlwSnapshot(final int position, final boolean runningBit,
                    final long runningLength, final int literalCount,
                    final long[] literals) {
            this.position = position;
            this.runningBit = runningBit;
            this.runningLength = runningLength;
            this.literalCount = literalCount;
            this.literals = literals;
        }

        long representedWords() {
            return this.runningLength + this.literalCount;
        }
    }

    static List<RlwSnapshot> snapshot(final EWAHCompressedBitmap b) {
        final List<RlwSnapshot> out = new ArrayList<RlwSnapshot>();
        final EWAHIterator it = b.getEWAHIterator();
        while (it.hasNext()) {
            final RunningLengthWord r = it.next();
            final long[] literals = new long[r.getNumberOfLiteralWords()];
            for (int k = 0; k < literals.length; ++k) {
                literals[k] = it.buffer().getWord(it.literalWords() + k);
            }
            out.add(new RlwSnapshot(r.position, r.getRunningBit(),
                    r.getRunningLength(), r.getNumberOfLiteralWords(),
                    literals));
        }
        return out;
    }

    static long representedWordTotal(final List<RlwSnapshot> rlws) {
        long total = 0;
        for (final RlwSnapshot s : rlws) {
            total += s.representedWords();
        }
        return total;
    }

    /**
     * Cross-implementation invariant (shared with the 32-bit test):
     * OR of A and B must agree on bit positions, cardinality and a
     * serialize/deserialize round trip. Position lists, not cardinality
     * alone, are asserted.
     */
    @Test
    public void testOrInvariantPositionsAndRoundTrip() throws IOException {
        final EWAHCompressedBitmap a = buildA();
        final EWAHCompressedBitmap b = buildB();
        final EWAHCompressedBitmap or = a.or(b);

        final List<Integer> expected = Arrays.asList(128, 261,
                320, 321, 322, 323, 324, 325, 326, 327, 328, 329,
                384, 385, 386, 387, 388, 389, 390, 391, 392, 393, 394,
                395, 396, 397, 398, 399, 400, 401, 402, 403);
        assertEquals(expected, or.toList());
        assertEquals(expected, forwardBits(or));
        assertEquals(expected.size(), or.cardinality());

        final byte[] wire = serialize(or);
        assertEquals(or.serializedSizeInBytes(), wire.length);
        final EWAHCompressedBitmap roundTripped = deserialize(wire);
        assertEquals(or, roundTripped);
        assertEquals(expected, roundTripped.toList());

        final EWAHCompressedBitmap mapped =
                new EWAHCompressedBitmap(ByteBuffer.wrap(wire));
        assertEquals(or, mapped);
        assertEquals(expected, mapped.toList());
    }

    /**
     * AND counterpart of the invariant: only the overlapping tail bit 384
     * survives; positions and serialization round trip are both asserted.
     */
    @Test
    public void testAndInvariantPositionsAndRoundTrip() throws IOException {
        final EWAHCompressedBitmap a = buildA();
        final EWAHCompressedBitmap b = buildB();
        final EWAHCompressedBitmap and = a.and(b);

        assertEquals(Arrays.asList(384), and.toList());
        assertEquals(1, and.cardinality());

        final byte[] wire = serialize(and);
        assertEquals(and.serializedSizeInBytes(), wire.length);
        final EWAHCompressedBitmap roundTripped = deserialize(wire);
        assertEquals(and, roundTripped);
        assertEquals(Arrays.asList(384), roundTripped.toList());
    }

    /**
     * Hand state table (inputs). Each cell below corresponds to a line of
     * {@code RlwTraceDump64} output: the sum of every RLW's
     * runningLength + literalCount must equal ceil(sizeInBits / 64), and
     * the literal words plus sizeInBits fully describe the bitmap.
     */
    @Test
    public void testInputRlwLayoutMatchesStateTable() {
        final EWAHCompressedBitmap a = buildA();
        final EWAHCompressedBitmap b = buildB();

        final List<RlwSnapshot> sa = snapshot(a);
        assertEquals(1, sa.size());
        final RlwSnapshot ra = sa.get(0);
        assertEquals(0, ra.position);
        assertFalse(ra.runningBit);
        assertEquals(4L, ra.runningLength);
        assertEquals(3, ra.literalCount);
        assertArrayEquals64(new long[]{1L << 5, (1L << 10) - 1, 1L},
                ra.literals);
        assertEquals(7L, representedWordTotal(sa));
        assertEquals((a.sizeInBits() + W - 1) / W, representedWordTotal(sa));
        assertEquals(6 * W + 10, a.sizeInBits());
        assertEquals(44, a.serializedSizeInBytes());

        final List<RlwSnapshot> sb = snapshot(b);
        assertEquals(2, sb.size());
        assertEquals(0, sb.get(0).position);
        assertEquals(2, sb.get(1).position);
        assertEquals(2L, sb.get(0).runningLength);
        assertEquals(1, sb.get(0).literalCount);
        assertEquals(3L, sb.get(1).runningLength);
        assertEquals(1, sb.get(1).literalCount);
        assertArrayEquals64(new long[]{1L}, sb.get(0).literals);
        assertArrayEquals64(new long[]{(1L << 20) - 1}, sb.get(1).literals);
        assertEquals(7L, representedWordTotal(sb));
        assertEquals(6 * W + 20, b.sizeInBits());
        assertEquals(44, b.serializedSizeInBytes());
    }

    /**
     * Hand state table (OR/AND results). OR ends with two RLWs and a
     * masked 20-bit tail; AND compresses everything into one 6-word zero
     * run plus the single shared literal.
     */
    @Test
    public void testResultRlwLayoutMatchesStateTable() {
        final EWAHCompressedBitmap or = buildA().or(buildB());
        final EWAHCompressedBitmap and = buildA().and(buildB());

        final List<RlwSnapshot> so = snapshot(or);
        assertEquals(2, so.size());
        assertEquals(2L, so.get(0).runningLength);
        assertEquals(1, so.get(0).literalCount);
        assertEquals(1L, so.get(1).runningLength);
        assertEquals(3, so.get(1).literalCount);
        assertArrayEquals64(new long[]{1L}, so.get(0).literals);
        assertArrayEquals64(new long[]{1L << 5, (1L << 10) - 1,
                (1L << 20) - 1}, so.get(1).literals);
        assertEquals(7L, representedWordTotal(so));
        assertEquals(6 * W + 20, or.sizeInBits());
        assertEquals(60, or.serializedSizeInBytes());

        final List<RlwSnapshot> san = snapshot(and);
        assertEquals(1, san.size());
        assertFalse(san.get(0).runningBit);
        assertEquals(6L, san.get(0).runningLength);
        assertEquals(1, san.get(0).literalCount);
        assertArrayEquals64(new long[]{1L}, san.get(0).literals);
        assertEquals(7L, representedWordTotal(san));
        assertEquals(6 * W + 20, and.sizeInBits());
        assertEquals(28, and.serializedSizeInBytes());
    }

    /**
     * The last literal word is masked down to sizeInBits % 64 by
     * setSizeInBitsWithinLastWord; both OR tail widths are observable.
     */
    @Test
    public void testLastWordMaskingInvariant() {
        final EWAHCompressedBitmap a = buildA();
        final EWAHCompressedBitmap b = buildB();
        final long mask10 = (~0L) >>> (W - 10);
        final long mask20 = (~0L) >>> (W - 20);
        // A's tail literal is 0x1, invariant under the 10-bit mask
        assertEquals(1L, snapshot(a).get(0).literals[2]);
        assertEquals(1L, snapshot(a).get(0).literals[2] & mask10);
        assertEquals(mask20, snapshot(b).get(1).literals[0]);
        assertEquals(mask20,
                snapshot(a.or(b)).get(1).literals[2]);
        // no result iterator may report a position >= sizeInBits
        final EWAHCompressedBitmap or = a.or(b);
        for (final int p : or.toList()) {
            assertTrue(p < or.sizeInBits());
        }
    }

    private static void assertArrayEquals64(final long[] expected,
                                            final long[] actual) {
        assertEquals(expected.length, actual.length);
        for (int k = 0; k < expected.length; ++k) {
            assertEquals(expected[k], actual[k]);
        }
    }

    /* ===================== Risk 1: aggregated input aliasing ===================== */

    /**
     * orToContainer(a, container) clears the container. Reusing one of the
     * inputs as the container aliases the input and destroys it.
     */
    @Test
    public void testRisk1AggregationInputAliasing() throws Exception {
        final EWAHCompressedBitmap a = buildA();
        final EWAHCompressedBitmap b = buildB();
        // Passing an input bitmap itself as the container aliases the
        // destination with a source: clear() wipes the source before it is
        // read, so the answer is silently wrong (and sizeInBits is stale).
        a.orToContainer(b, a);
        assertNotEquals(buildA().or(buildB()), a);
        // observable corruption: A lost its own literals in words 4-5,
        // and sizeInBits reports a full extra word (448 instead of 404)
        assertEquals(448, a.sizeInBits());
        assertEquals(Arrays.asList(128, 384, 385, 386, 387, 388, 389, 390,
                391, 392, 393, 394, 395, 396, 397, 398, 399, 400, 401, 402,
                403), a.toList());
        // the safe public entry point or() never exposes the aliasing:
        assertEquals(buildA().or(buildB()), buildA().or(buildB()));
    }

    /* ===================== Risk 2: truncated serialization ===================== */

    /**
     * deserialize reads the trailing rlw-position int after every word.
     * A truncated stream fails with EOFException instead of silently
     * producing a usable bitmap.
     */
    @Test
    public void testRisk2TruncatedSerializationThrows() throws IOException {
        final byte[] wire = serialize(buildA());
        assertEquals(44, wire.length);

        final byte[] missingTrailingPosition = Arrays.copyOf(wire, wire.length - 4);
        try {
            deserialize(missingTrailingPosition);
            org.junit.Assert.fail("expected EOFException");
        } catch (final EOFException expected) {
            // documented, observable failure mode
        }

        final byte[] headerOnly = Arrays.copyOf(wire, 8);
        try {
            deserialize(headerOnly);
            org.junit.Assert.fail("expected EOFException");
        } catch (final EOFException expected) {
            // documented, observable failure mode
        }
    }

    /* ===================== Risk 3: non-zero ByteBuffer position ===================== */

    /**
     * The memory-mapped constructor interprets the int/long views as
     * starting at the buffer's current position. A non-zero position
     * reinterprets payload bytes as the header.
     */
    @Test
    public void testRisk3ByteBufferMustHaveZeroPosition() throws IOException {
        final EWAHCompressedBitmap a = EWAHCompressedBitmap.bitmapOf(0, 64, 300);
        final byte[] wire = serialize(a);

        // A non-zero position makes the constructor interpret payload
        // bytes as the sizeInBits header: here sizeInBits becomes 4
        // instead of 301 and the bitmap no longer equals the original.
        final ByteBuffer wrongPosition = ByteBuffer.wrap(wire);
        wrongPosition.position(8);
        final EWAHCompressedBitmap misread =
                new EWAHCompressedBitmap(wrongPosition);
        assertEquals(4, misread.sizeInBits());
        assertNotEquals(a.sizeInBits(), misread.sizeInBits());
        assertFalse(misread.equals(a));

        final ByteBuffer correct = ByteBuffer.wrap(wire);
        assertEquals(0, correct.position());
        assertTrue(new EWAHCompressedBitmap(correct).equals(a));
    }

    /* ===================== Risk 4: reverse iteration contract ===================== */

    /**
     * addWord(word, bitsThatMatter) trusts the expert caller. Forward
     * iteration trusts the stored word; reverse iteration is bounded by
     * sizeInBits, so bits above the declared tail diverge.
     */
    @Test
    public void testRisk4UnmaskedTailDivergesForwardFromReverse() {
        final EWAHCompressedBitmap bad = new EWAHCompressedBitmap();
        bad.addStreamOfEmptyWords(false, 1);
        bad.addWord((1L << 5) | (1L << 63), 6);

        final List<Integer> forward = forwardBits(bad);
        final List<Integer> reverse = reverseBits(bad);
        assertEquals(Arrays.asList(69, 127), forward);
        assertEquals(Arrays.asList(69), reverse);
        assertEquals(Arrays.asList(69), bad.toList());
        // forward exposes a position outside the declared bitmap domain
        assertEquals(70, bad.sizeInBits());
        assertTrue(forward.contains(127));
        assertFalse(reverse.contains(127));
        assertEquals(2, bad.cardinality());

        // a correctly masked tail keeps the two iterators consistent
        final EWAHCompressedBitmap good = new EWAHCompressedBitmap();
        good.addStreamOfEmptyWords(false, 1);
        good.addWord(1L << 5, 6);
        final List<Integer> goodReverse = reverseBits(good);
        assertEquals(Arrays.asList(69), forwardBits(good));
        assertEquals(Arrays.asList(69), goodReverse);
    }

    /* ===================== Risk 5: empty bitmap ===================== */

    /**
     * The empty bitmap keeps a single zero marker word in the buffer and
     * still reports sizeInBits 0; serialization round trips, cardinality
     * is 0, and the RLW scan sees one zero-size marker.
     */
    @Test
    public void testRisk5EmptyBitmapInvariants() throws IOException {
        final EWAHCompressedBitmap empty = new EWAHCompressedBitmap();
        assertEquals(0, empty.sizeInBits());
        assertEquals(0, empty.cardinality());
        assertEquals(8, empty.sizeInBytes());
        assertEquals(20, empty.serializedSizeInBytes());
        assertTrue(empty.toList().isEmpty());

        final List<RlwSnapshot> snaps = snapshot(empty);
        assertEquals(1, snaps.size());
        assertEquals(0L, snaps.get(0).representedWords());

        final byte[] wire = serialize(empty);
        assertEquals(20, wire.length);
        assertEquals(20, empty.serializedSizeInBytes());
        final EWAHCompressedBitmap roundTripped = deserialize(wire);
        assertEquals(0, roundTripped.sizeInBits());
        assertEquals(empty, roundTripped);
        assertTrue(roundTripped.toList().isEmpty());

        // empty participates in aggregation without changing the answer
        assertEquals(buildA(), empty.or(buildA()));
        assertEquals(0, empty.and(buildA()).cardinality());
        assertEquals(buildA().sizeInBits(), empty.and(buildA()).sizeInBits());
    }
}
