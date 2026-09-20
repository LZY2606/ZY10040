package com.googlecode.javaewah;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Stand-alone debug utility that produces the 64-bit side of the hand state
 * table referenced by ANALYSIS.md. It builds two bitmaps (A and B) which
 * each contain a long zero run, literal words and a non-integral last
 * word, then prints the RLW layout of the inputs and of the OR/AND results.
 *
 * Run (from the repository root, after {@code mvn -q -DskipTests package}):
 * <pre>
 * mvn -q compile test-compile
 * java -cp target/classes:target/test-classes \
 *     com.googlecode.javaewah.RlwTraceDump64
 * </pre>
 * Every line starts with a '#' (section) or a '|' (table row) so that the
 * output can be diffed directly against the columns of the hand state table.
 */
public final class RlwTraceDump64 {

    private RlwTraceDump64() {
    }

    static final String SEP = "|";

    static EWAHCompressedBitmap buildA() {
        final EWAHCompressedBitmap b = new EWAHCompressedBitmap();
        b.addStreamOfEmptyWords(false, 4);
        b.addLiteralWord(1L << 5);
        b.addLiteralWord((1L << 10) - 1);
        b.addLiteralWord(1L);
        b.setSizeInBitsWithinLastWord(6 * EWAHCompressedBitmap.WORD_IN_BITS + 10);
        return b;
    }

    static EWAHCompressedBitmap buildB() {
        final EWAHCompressedBitmap b = new EWAHCompressedBitmap();
        b.addStreamOfEmptyWords(false, 2);
        b.addLiteralWord(1L);
        b.addStreamOfEmptyWords(false, 3);
        b.addLiteralWord((1L << 20) - 1);
        b.setSizeInBitsWithinLastWord(6 * EWAHCompressedBitmap.WORD_IN_BITS + 20);
        return b;
    }

    static String hex(final long w) {
        return String.format("0x%016x", w);
    }

    static int wireBytes(final EWAHCompressedBitmap b) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.serialize(new DataOutputStream(bos));
        return bos.size();
    }

    static void dump(final String tag, final EWAHCompressedBitmap b)
            throws IOException {
        System.out.println("# " + tag + " (64-bit)");
        System.out.println(SEP + " sizeInBits=" + b.sizeInBits()
                + " sizeInBytes=" + b.sizeInBytes()
                + " serializedSizeInBytes=" + b.serializedSizeInBytes()
                + " wire=" + wireBytes(b));
        final EWAHIterator it = b.getEWAHIterator();
        int uncompressedWords = 0;
        int rlwIndex = 0;
        while (it.hasNext()) {
            final RunningLengthWord r = it.next();
            System.out.println(SEP + " rlw#" + rlwIndex++
                    + " position=" + r.position
                    + " runningBit=" + r.getRunningBit()
                    + " runningLength=" + r.getRunningLength()
                    + " literalCount=" + r.getNumberOfLiteralWords()
                    + " size=" + r.size());
            for (int k = 0; k < r.getNumberOfLiteralWords(); ++k) {
                System.out.println(SEP + "   literal[" + k + "]="
                        + hex(it.buffer().getWord(it.literalWords() + k)));
            }
            uncompressedWords += (int) r.size();
        }
        System.out.println(SEP + " uncompressedWords=" + uncompressedWords
                + " ceil(sizeInBits/64)="
                + ((b.sizeInBits() + 63) / 64)
                + " cardinality=" + b.cardinality()
                + " bits=" + b.toList());
    }

    /** Command line entry point producing the debug output. */
    public static void main(final String[] args) throws IOException {
        final EWAHCompressedBitmap a = buildA();
        final EWAHCompressedBitmap b = buildB();
        dump("A", a);
        dump("B", b);
        dump("A OR B", a.or(b));
        dump("A AND B", a.and(b));
    }
}
