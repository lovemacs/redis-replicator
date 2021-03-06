/*
 * Copyright 2016 leon chen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.moilioncircle.redis.replicator.util;

import java.util.Arrays;
import java.util.Iterator;

/**
 * Created by leon on 1/29/17.
 */
public class ByteArray implements Iterable<byte[]> {
    public static final long MIN_VALUE = 0L;
    public static final long MAX_VALUE = 4611686014132420609L; //Integer.MAX_VALUE * Integer.MAX_VALUE

    protected final long length;
    protected byte[] smallBytes;
    protected byte[][] largeBytes;

    public ByteArray(byte[] smallBytes) {
        this.length = smallBytes.length;
        this.smallBytes = smallBytes;
    }

    public ByteArray(long length) {
        this.length = length;
        if (length > MAX_VALUE) {
            throw new IllegalArgumentException(String.valueOf(length));
        } else if (length <= Integer.MAX_VALUE) {
            this.smallBytes = new byte[(int) length];
        } else {
            int x = (int) (length / Integer.MAX_VALUE);
            int y = (int) (length % Integer.MAX_VALUE);
            largeBytes = new byte[x + 1][];
            for (int i = 0; i < largeBytes.length; i++) {
                if (i == largeBytes.length - 1) {
                    largeBytes[i] = new byte[y];
                } else {
                    largeBytes[i] = new byte[Integer.MAX_VALUE];
                }
            }
        }
    }

    public void set(long idx, byte value) {
        if (idx < Integer.MAX_VALUE) {
            smallBytes[(int) idx] = value;
            return;
        }
        int x = (int) (idx / Integer.MAX_VALUE);
        int y = (int) (idx % Integer.MAX_VALUE);
        largeBytes[x][y] = value;
    }

    public byte get(long idx) {
        if (idx < Integer.MAX_VALUE) return smallBytes[(int) idx];
        int x = (int) (idx / Integer.MAX_VALUE);
        int y = (int) (idx % Integer.MAX_VALUE);
        return largeBytes[x][y];
    }

    public long length() {
        return this.length;
    }

    public byte[] first() {
        return this.iterator().next();
    }

    @Override
    public Iterator<byte[]> iterator() {
        return new Iter();
    }

    public static void arraycopy(ByteArray src, long srcPos, ByteArray dest, long destPos, long length) {
        if (srcPos + length > src.length || destPos + length > dest.length) {
            throw new IndexOutOfBoundsException();
        }
        if (srcPos + length <= Integer.MAX_VALUE && destPos + length <= Integer.MAX_VALUE) {
            System.arraycopy(src.smallBytes, (int) srcPos, dest.smallBytes, (int) destPos, (int) length);
            return;
        }
        while (length > 0) {
            int x1 = (int) (srcPos / Integer.MAX_VALUE);
            int y1 = (int) (srcPos % Integer.MAX_VALUE);
            int x2 = (int) (destPos / Integer.MAX_VALUE);
            int y2 = (int) (destPos % Integer.MAX_VALUE);
            int min = Math.min(Integer.MAX_VALUE - y1, Integer.MAX_VALUE - y2);
            if (length <= Integer.MAX_VALUE) min = Math.min(min, (int) length);
            System.arraycopy(src.largeBytes[x1], y1, dest.largeBytes[x2], y2, min);
            srcPos += min;
            destPos += min;
            length -= min;
        }
        assert length == 0;
    }

    @Override
    public String toString() {
        if (smallBytes != null) return Arrays.toString(smallBytes);
        StringBuilder builder = new StringBuilder();
        for (byte[] b : largeBytes) {
            builder.append(Arrays.toString(b));
        }
        return builder.toString();
    }

    protected class Iter implements Iterator<byte[]> {
        protected int index = 0;

        @Override
        public boolean hasNext() {
            if (smallBytes != null) return index < 1;
            return index < largeBytes.length;
        }

        @Override
        public byte[] next() {
            if (smallBytes != null) {
                index++;
                return smallBytes;
            }
            return largeBytes[index++];
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }
}
