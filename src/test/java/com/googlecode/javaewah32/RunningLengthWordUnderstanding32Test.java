package com.googlecode.javaewah32;

import org.junit.Test;

public class RunningLengthWordUnderstanding32Test {

    @Test
    public void understanding32ManualStateTable() {
        EWAHCompressedBitmap32 a = bitmapOf(1, 179, 199);
        EWAHCompressedBitmap32 b = bitmapOf(2, 132, 179);

        TracingBitmapStorage32 orTrace = new TracingBitmapStorage32("32 OR");
        a.orToContainer(b, orTrace);
        TracingBitmapStorage32 andTrace = new TracingBitmapStorage32("32 AND");
        a.andToContainer(b, andTrace);

        StringBuilder out = new StringBuilder("understanding-state-table\n");
        out.append("32 input A: ").append(a.toList()).append('\n');
        out.append("32 input B: ").append(b.toList()).append('\n');
        appendRlwStates(out, "32 OR", orTrace.result);
        appendRlwStates(out, "32 AND", andTrace.result);
        System.out.print(out);
    }

    private static void appendRlwStates(StringBuilder out, String label,
                                        EWAHCompressedBitmap32 bitmap) {
        EWAHIterator32 iterator = bitmap.getEWAHIterator();
        int rlwIndex = 0;
        while (iterator.hasNext()) {
            RunningLengthWord32 rlw = iterator.next();
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

    private static EWAHCompressedBitmap32 bitmapOf(int... positions) {
        EWAHCompressedBitmap32 bitmap = new EWAHCompressedBitmap32();
        for (int position : positions) {
            bitmap.set(position);
        }
        return bitmap;
    }

    private static final class TracingBitmapStorage32 implements BitmapStorage32 {
        private final EWAHCompressedBitmap32 result = new EWAHCompressedBitmap32();
        private final String label;

        private TracingBitmapStorage32(String label) {
            this.label = label;
        }

        @Override
        public void addWord(int newData) {
            trace("addWord", newData == 0 ? "0" : newData == ~0 ? "~0" : Integer.toHexString(newData));
            result.addWord(newData);
        }

        @Override
        public void addLiteralWord(int newData) {
            trace("addLiteralWord", Integer.toHexString(newData));
            result.addLiteralWord(newData);
        }

        @Override
        public void addStreamOfLiteralWords(Buffer32 buffer, int start, int number) {
            trace("addStreamOfLiteralWords", "start=" + start + " number=" + number);
            result.addStreamOfLiteralWords(buffer, start, number);
        }

        @Override
        public void addStreamOfEmptyWords(boolean value, int number) {
            trace("addStreamOfEmptyWords", "bit=" + value + " number=" + number);
            result.addStreamOfEmptyWords(value, number);
        }

        @Override
        public void addStreamOfNegatedLiteralWords(Buffer32 buffer, int start, int number) {
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
