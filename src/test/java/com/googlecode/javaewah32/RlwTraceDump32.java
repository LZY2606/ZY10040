package com.googlecode.javaewah32;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Stand-alone debug utility that produces the 32-bit side of the hand state
 * table referenced by ANALYSIS.md. It is the structural twin of
 * {@link com.googlecode.javaewah.RlwTraceDump64}.
 *
 * Run (from the repository root, after {@code mvn -q -DskipTests package}):
 * <pre>
 * mvn -q compile test-compile
 * java -cp target/classes:target/test-classes \
 *     com.googlecode.javaewah32.RlwTraceDump32
 * </pre>
 */
public final class RlwTraceDump32 {

    private RlwTraceDump32() {
    }

    static final String SEP = "|";

    static EWAHCompressedBitmap32 buildA() {
        final EWAHCompressedBitmap32 b = new EWAHCompressedBitmap32();
        b.addStreamOfEmptyWords(false, 4);
        b.addLiteralWord(1 << 5);
        b.addLiteralWord((1 << 10) - 1);
        b.addLiteralWord(1);
        b.setSizeInBitsWithinLastWord(6 * EWAHCompressedBitmap32.WORD_IN_BITS + 10);
        return b;
    }

    static EWAHCompressedBitmap32 buildB() {
        final EWAHCompressedBitmap32 b = new EWAHCompressedBitmap32();
        b.addStreamOfEmptyWords(false, 2);
        b.addLiteralWord(1);
        b.addStreamOfEmptyWords(false, 3);
        b.addLiteralWord((1 << 20) - 1);
        b.setSizeInBitsWithinLastWord(6 * EWAHCompressedBitmap32.WORD_IN_BITS + 20);
        return b;
    }

    static String hex(final int w) {
        return String.format("0x%08x", w);
    }

    static int wireBytes(final EWAHCompressedBitmap32 b) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.serialize(new DataOutputStream(bos));
        return bos.size();
    }

    static void dump(final String tag, final EWAHCompressedBitmap32 b)
            throws IOException {
        System.out.println("# " + tag + " (32-bit)");
        System.out.println(SEP + " sizeInBits=" + b.sizeInBits()
                + " sizeInBytes=" + b.sizeInBytes()
                + " serializedSizeInBytes=" + b.serializedSizeInBytes()
                + " wire=" + wireBytes(b));
        final EWAHIterator32 it = b.getEWAHIterator();
        int uncompressedWords = 0;
        int rlwIndex = 0;
        while (it.hasNext()) {
            final RunningLengthWord32 r = it.next();
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
            uncompressedWords += r.size();
        }
        System.out.println(SEP + " uncompressedWords=" + uncompressedWords
                + " ceil(sizeInBits/32)="
                + ((b.sizeInBits() + 31) / 32)
                + " cardinality=" + b.cardinality()
                + " bits=" + b.toList());
    }

    /** Command line entry point producing the debug output. */
    public static void main(final String[] args) throws IOException {
        final EWAHCompressedBitmap32 a = buildA();
        final EWAHCompressedBitmap32 b = buildB();
        dump("A", a);
        dump("B", b);
        dump("A OR B", a.or(b));
        dump("A AND B", a.and(b));
    }
}
