package com.googlecode.javaewah;

import com.googlecode.javaewah32.EWAHCompressedBitmap32;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

public class RunningLengthWordUnderstandingTest {

    @Test
    public void understandingCrossWordInvariantOrAndAndSerializationRoundTrip() throws Exception {
        Scenario64 scenario64 = new Scenario64();
        EWAHCompressedBitmap32 a32 = bitmapOf32(1, 179, 199);
        EWAHCompressedBitmap32 b32 = bitmapOf32(2, 132, 179);
        EWAHCompressedBitmap32 or32 = a32.or(b32);
        EWAHCompressedBitmap32 and32 = a32.and(b32);
        Assert.assertEquals(Arrays.asList(1, 2, 300, 349, 399),
                scenario64.or.toList());
        Assert.assertEquals(Arrays.asList(349),
                scenario64.and.toList());
        Assert.assertEquals(Arrays.asList(1, 2, 132, 179, 199),
                or32.toList());
        Assert.assertEquals(Arrays.asList(179),
                and32.toList());

        assertSerializedRoundTrip(scenario64.or,
                Arrays.asList(1, 2, 300, 349, 399), 400, 60);
        assertSerializedRoundTrip(scenario64.and,
                Arrays.asList(349), 400, 36);
        assertSerializedRoundTrip32(or32,
                Arrays.asList(1, 2, 132, 179, 199), 200, 36);
        assertSerializedRoundTrip32(and32,
                Arrays.asList(179), 200, 24);

        printManualStateTable(scenario64);
    }

    @Test
    public void understandingRiskOfAggregateInputAliasing() {
        EWAHCompressedBitmap left = bitmapOf64(1, 100);
        EWAHCompressedBitmap right = bitmapOf64(2, 100);

        left.orToContainer(right, left);

        Assert.assertEquals(Arrays.asList(2, 100), left.toList());
        Assert.assertEquals(Arrays.asList(2, 100), right.toList());

        EWAHCompressedBitmap32 left32 = bitmapOf32(1, 50);
        EWAHCompressedBitmap32 right32 = bitmapOf32(2, 50);

        left32.orToContainer(right32, left32);

        Assert.assertEquals(Arrays.asList(2, 50), left32.toList());
        Assert.assertEquals(Arrays.asList(2, 50), right32.toList());
    }

    @Test
    public void understandingRiskOfTruncatedSerialization() throws Exception {
        byte[] bytes = serialize64(bitmapOf64(1));

        EWAHCompressedBitmap restored = new EWAHCompressedBitmap();
        try {
            restored.deserialize(new DataInputStream(
                    new ByteArrayInputStream(bytes, 0, bytes.length - 1)));
            Assert.fail("Expected the truncated trailing word-position to fail");
        } catch (EOFException expected) {
            // Expected: deserialize reads sizeInBits, word count, every word, and rlw position.
        }
    }

    @Test
    public void understandingRiskOfNonZeroByteBufferPosition() {
        ByteBuffer buffer = ByteBuffer.wrap(serialize64(bitmapOf64(1)));
        buffer.position(8);

        EWAHCompressedBitmap view = new EWAHCompressedBitmap(buffer);

        Assert.assertEquals(2, view.sizeInBits());
        Assert.assertTrue(view.toList().isEmpty());

        ByteBuffer buffer32 = ByteBuffer.wrap(serialize32(bitmapOf32(1)));
        buffer32.position(8);
        try {
            new EWAHCompressedBitmap32(buffer32);
            Assert.fail("Expected the sliced 32-bit view to be too short");
        } catch (IndexOutOfBoundsException expected) {
            // Expected: the sliced IntBuffer payload inherits the non-zero position.
        }
    }

    @Test
    public void understandingRiskOfReverseIterationOnEmptyBitmap() {
        IntIterator reverse = new EWAHCompressedBitmap().reverseIntIterator();

        Assert.assertFalse(reverse.hasNext());
        Assert.assertEquals(-1, reverse.next());
        Assert.assertFalse(reverse.hasNext());

        IntIterator reverse32 = new EWAHCompressedBitmap32().reverseIntIterator();

        Assert.assertFalse(reverse32.hasNext());
        Assert.assertEquals(-1, reverse32.next());
    }

    @Test
    public void understandingRiskOfEmptyBitmapRepresentingZeroWordsButContainingRlw() throws Exception {
        EWAHCompressedBitmap empty = new EWAHCompressedBitmap();

        Assert.assertEquals(0, empty.sizeInBits());
        Assert.assertEquals(1, compressedWordCount64(empty));
        Assert.assertEquals(20, empty.serializedSizeInBytes());
        Assert.assertFalse(empty.reverseIntIterator().hasNext());

        EWAHCompressedBitmap restored = deserialize64(serialize64(empty));
        Assert.assertEquals(0, restored.sizeInBits());
        Assert.assertEquals(1, compressedWordCount64(restored));
    }

    private static void assertSerializedRoundTrip(EWAHCompressedBitmap bitmap,
                                                  List<Integer> expectedPositions,
                                                  int expectedSizeInBits,
                                                  int expectedSerializedSize) throws Exception {
        byte[] bytes = serialize64(bitmap);
        Assert.assertEquals(expectedSerializedSize, bytes.length);
        Assert.assertEquals(expectedSerializedSize, bitmap.serializedSizeInBytes());

        EWAHCompressedBitmap restored = deserialize64(bytes);
        Assert.assertEquals(expectedSizeInBits, restored.sizeInBits());
        Assert.assertEquals(expectedPositions, restored.toList());
        Assert.assertEquals(bitmap.toList(), restored.toList());
        Assert.assertEquals(bitmap.sizeInBytes(), restored.sizeInBytes());
    }

    private static void assertSerializedRoundTrip32(EWAHCompressedBitmap32 bitmap,
                                                    List<Integer> expectedPositions,
                                                    int expectedSizeInBits,
                                                    int expectedSerializedSize) throws Exception {
        byte[] bytes = serialize32(bitmap);
        Assert.assertEquals(expectedSerializedSize, bytes.length);
        Assert.assertEquals(expectedSerializedSize, bitmap.serializedSizeInBytes());

        EWAHCompressedBitmap32 restored = deserialize32(bytes);
        Assert.assertEquals(expectedSizeInBits, restored.sizeInBits());
        Assert.assertEquals(expectedPositions, restored.toList());
        Assert.assertEquals(bitmap.toList(), restored.toList());
        Assert.assertEquals(bitmap.sizeInBytes(), restored.sizeInBytes());
    }

    private static int compressedWordCount64(EWAHCompressedBitmap bitmap) {
        EWAHIterator iterator = bitmap.getEWAHIterator();
        int count = 0;
        while (iterator.hasNext()) {
            RunningLengthWord rlw = iterator.next();
            count += 1 + rlw.getNumberOfLiteralWords();
        }
        return count;
    }

    private static byte[] serialize64(EWAHCompressedBitmap bitmap) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            bitmap.serialize(new DataOutputStream(output));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return output.toByteArray();
    }

    private static byte[] serialize32(EWAHCompressedBitmap32 bitmap) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            bitmap.serialize(new DataOutputStream(output));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return output.toByteArray();
    }

    private static EWAHCompressedBitmap deserialize64(byte[] bytes) {
        EWAHCompressedBitmap bitmap = new EWAHCompressedBitmap();
        try {
            bitmap.deserialize(new DataInputStream(new ByteArrayInputStream(bytes)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return bitmap;
    }

    private static EWAHCompressedBitmap32 deserialize32(byte[] bytes) {
        EWAHCompressedBitmap32 bitmap = new EWAHCompressedBitmap32();
        try {
            bitmap.deserialize(new DataInputStream(new ByteArrayInputStream(bytes)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return bitmap;
    }

    private static EWAHCompressedBitmap bitmapOf64(int... positions) {
        EWAHCompressedBitmap bitmap = new EWAHCompressedBitmap();
        for (int position : positions) {
            bitmap.set(position);
        }
        return bitmap;
    }

    private static EWAHCompressedBitmap32 bitmapOf32(int... positions) {
        EWAHCompressedBitmap32 bitmap = new EWAHCompressedBitmap32();
        for (int position : positions) {
            bitmap.set(position);
        }
        return bitmap;
    }

    private static void printManualStateTable(Scenario64 scenario64) {
        StringBuilder out = new StringBuilder("understanding-state-table\n");
        out.append("64 input A: ").append(scenario64.a.toList()).append('\n');
        out.append("64 input B: ").append(scenario64.b.toList()).append('\n');
        appendRlwStates(out, "64 OR", scenario64.or);
        appendRlwStates(out, "64 AND", scenario64.and);
        System.out.print(out);
    }

    private static void appendRlwStates(StringBuilder out, String label,
                                        EWAHCompressedBitmap bitmap) {
        EWAHIterator iterator = bitmap.getEWAHIterator();
        int rlwIndex = 0;
        while (iterator.hasNext()) {
            RunningLengthWord rlw = iterator.next();
            out.append(label)
                    .append(" RLW#").append(rlwIndex++)
                    .append(" bufferPosition=").append(rlw.position)
                    .append(" runningBit=").append(rlw.getRunningBit())
                    .append(" runningLength=").append(rlw.getRunningLength())
                    .append(" literalCount=").append(rlw.getNumberOfLiteralWords())
                    .append(" representedWords=").append(rlw.size())
                    .append(" literalStart=").append(iterator.literalWords())
                    .append('\n');
        }
    }

    private static final class Scenario64 {
        private final EWAHCompressedBitmap a;
        private final EWAHCompressedBitmap b;
        private final EWAHCompressedBitmap or;
        private final EWAHCompressedBitmap and;

        private Scenario64() {
            this.a = bitmapOf64(1, 349, 399);
            this.b = bitmapOf64(2, 300, 349);
            TracingBitmapStorage orTrace = new TracingBitmapStorage("64 OR");
            this.a.orToContainer(this.b, orTrace);
            this.or = orTrace.result;
            TracingBitmapStorage andTrace = new TracingBitmapStorage("64 AND");
            this.a.andToContainer(this.b, andTrace);
            this.and = andTrace.result;
        }
    }

    private static final class TracingBitmapStorage implements BitmapStorage {
        private final String label;
        private final EWAHCompressedBitmap result = new EWAHCompressedBitmap();

        private TracingBitmapStorage(String label) {
            this.label = label;
        }

        @Override
        public void addWord(long newData) {
            trace("addWord", newData == 0 ? "0" : newData == ~0L ? "~0" : Long.toHexString(newData));
            result.addWord(newData);
        }

        @Override
        public void addLiteralWord(long newData) {
            trace("addLiteralWord", Long.toHexString(newData));
            result.addLiteralWord(newData);
        }

        @Override
        public void addStreamOfLiteralWords(Buffer buffer, int start, int number) {
            trace("addStreamOfLiteralWords", "start=" + start + " number=" + number);
            result.addStreamOfLiteralWords(buffer, start, number);
        }

        @Override
        public void addStreamOfEmptyWords(boolean value, long number) {
            trace("addStreamOfEmptyWords", "bit=" + value + " number=" + number);
            result.addStreamOfEmptyWords(value, number);
        }

        @Override
        public void addStreamOfNegatedLiteralWords(Buffer buffer, int start, int number) {
            trace("addStreamOfNegatedLiteralWords", "start=" + start + " number=" + number);
            result.addStreamOfNegatedLiteralWords(buffer, start, number);
        }

        @Override
        public void clear() {
            trace("clear", "");
            result.clear();
        }

        @Override
        public void setSizeInBitsWithinLastWord(int size) {
            trace("setSizeInBitsWithinLastWord", "size=" + size);
            result.setSizeInBitsWithinLastWord(size);
        }

        private void trace(String action, String detail) {
            System.out.println(label + " step " + action + ' ' + detail);
        }
    }
}
