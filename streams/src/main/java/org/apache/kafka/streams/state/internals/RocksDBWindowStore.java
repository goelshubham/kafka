/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.kafka.streams.state.internals;

import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.StateRestoreCallback;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.Serdes;
import org.apache.kafka.streams.state.WindowStore;
import org.apache.kafka.streams.state.WindowStoreIterator;
import org.apache.kafka.streams.state.WindowStoreUtils;


import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.SimpleTimeZone;

public class RocksDBWindowStore<K, V> implements WindowStore<K, V> {

    public static final long MIN_SEGMENT_INTERVAL = 60 * 1000; // one minute

    private static final long USE_CURRENT_TIMESTAMP = -1L;

    private static class Segment extends RocksDBStore<byte[], byte[]> {
        public final long id;

        Segment(String segmentName, String windowName, long id) {
            super(segmentName, windowName, WindowStoreUtils.INNER_SERDES);
            this.id = id;
        }

        public void destroy() {
            Utils.delete(dbDir);
        }
    }

    private static class RocksDBWindowStoreIterator<V> implements WindowStoreIterator<V> {
        private final Serdes<?, V> serdes;
        private final KeyValueIterator<byte[], byte[]>[] iterators;
        private int index = 0;

        RocksDBWindowStoreIterator(Serdes<?, V> serdes) {
            this(serdes, WindowStoreUtils.NO_ITERATORS);
        }

        RocksDBWindowStoreIterator(Serdes<?, V> serdes, KeyValueIterator<byte[], byte[]>[] iterators) {
            this.serdes = serdes;
            this.iterators = iterators;
        }

        @Override
        public boolean hasNext() {
            while (index < iterators.length) {
                if (iterators[index].hasNext())
                    return true;

                index++;
            }
            return false;
        }

        @Override
        public KeyValue<Long, V> next() {
            if (index >= iterators.length)
                throw new NoSuchElementException();

            KeyValue<byte[], byte[]> kv = iterators[index].next();

            return new KeyValue<>(WindowStoreUtils.timestampFromBinaryKey(kv.key),
                                  serdes.valueFrom(kv.value));
        }

        @Override
        public void remove() {
            if (index < iterators.length)
                iterators[index].remove();
        }

        @Override
        public void close() {
            for (KeyValueIterator<byte[], byte[]> iterator : iterators) {
                iterator.close();
            }
        }
    }

    private final String name;
    private final long segmentInterval;
    private final boolean retainDuplicates;
    private final Segment[] segments;
    private final Serdes<K, V> serdes;
    private final SimpleDateFormat formatter;
    private final StoreChangeLogger.ValueGetter<byte[], byte[]> getter;

    private ProcessorContext context;
    private int seqnum = 0;
    private long currentSegmentId = -1L;

    private boolean loggingEnabled = false;
    private StoreChangeLogger<byte[], byte[]> changeLogger = null;

    public RocksDBWindowStore(String name, long retentionPeriod, int numSegments, boolean retainDuplicates, Serdes<K, V> serdes) {
        this.name = name;

        // The segment interval must be greater than MIN_SEGMENT_INTERVAL
        this.segmentInterval = Math.max(retentionPeriod / (numSegments - 1), MIN_SEGMENT_INTERVAL);

        this.segments = new Segment[numSegments];
        this.serdes = serdes;

        this.retainDuplicates = retainDuplicates;

        this.getter = new StoreChangeLogger.ValueGetter<byte[], byte[]>() {
            public byte[] get(byte[] key) {
                return getInternal(key);
            }
        };

        // Create a date formatter. Formatted timestamps are used as segment name suffixes
        this.formatter = new SimpleDateFormat("yyyyMMddHHmm");
        this.formatter.setTimeZone(new SimpleTimeZone(0, "GMT"));
    }

    public RocksDBWindowStore<K, V> enableLogging() {
        loggingEnabled = true;

        return this;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void init(ProcessorContext context) {
        this.context = context;

        openExistingSegments();

        this.changeLogger = this.loggingEnabled ?
                new RawStoreChangeLogger(name, context) : null;

        // register and possibly restore the state from the logs
        context.register(this, loggingEnabled, new StateRestoreCallback() {
            @Override
            public void restore(byte[] key, byte[] value) {
                putInternal(key, value);
            }
        });

        flush();
    }

    private void openExistingSegments() {
        try {
            File dir = new File(context.stateDir(), name);

            if (dir.exists()) {
                for (String segmentName : dir.list()) {
                    long segmentId = segmentIdFromSegmentName(segmentName);
                    if (segmentId >= 0)
                        getSegment(segmentId);
                }
            } else {
                dir.mkdir();
            }
        } catch (Exception ex) {

        }
    }

    @Override
    public boolean persistent() {
        return true;
    }

    @Override
    public void flush() {
        for (Segment segment : segments) {
            if (segment != null)
                segment.flush();
        }

        if (loggingEnabled)
            changeLogger.logChange(this.getter);
    }

    @Override
    public void close() {
        flush();
        for (Segment segment : segments) {
            if (segment != null)
                segment.close();
        }
    }

    @Override
    public void put(K key, V value) {
        byte[] rawKey = putAndReturnInternalKey(key, value, USE_CURRENT_TIMESTAMP);

        if (loggingEnabled) {
            changeLogger.add(rawKey);
            changeLogger.maybeLogChange(this.getter);
        }
    }

    @Override
    public void put(K key, V value, long timestamp) {
        byte[] rawKey = putAndReturnInternalKey(key, value, timestamp);

        if (loggingEnabled) {
            changeLogger.add(rawKey);
            changeLogger.maybeLogChange(this.getter);
        }
    }

    private byte[] putAndReturnInternalKey(K key, V value, long t) {
        long timestamp = t == USE_CURRENT_TIMESTAMP ? context.timestamp() : t;

        long segmentId = segmentId(timestamp);

        if (segmentId > currentSegmentId) {
            // A new segment will be created. Clean up old segments first.
            currentSegmentId = segmentId;
            cleanup();
        }

        // If the record is within the retention period, put it in the store.
        Segment segment = getSegment(segmentId);
        if (segment != null) {
            if (retainDuplicates)
                seqnum = (seqnum + 1) & 0x7FFFFFFF;
            byte[] binaryKey = WindowStoreUtils.toBinaryKey(key, timestamp, seqnum, serdes);
            segment.put(binaryKey, serdes.rawValue(value));
            return binaryKey;
        } else {
            return null;
        }
    }

    private void putInternal(byte[] binaryKey, byte[] binaryValue) {
        long segmentId = segmentId(WindowStoreUtils.timestampFromBinaryKey(binaryKey));

        if (segmentId > currentSegmentId) {
            // A new segment will be created. Clean up old segments first.
            currentSegmentId = segmentId;
            cleanup();
        }

        // If the record is within the retention period, put it in the store.
        Segment segment = getSegment(segmentId);
        if (segment != null)
            segment.put(binaryKey, binaryValue);
    }

    private byte[] getInternal(byte[] binaryKey) {
        long segmentId = segmentId(WindowStoreUtils.timestampFromBinaryKey(binaryKey));

        Segment segment = getSegment(segmentId);
        if (segment != null) {
            return segment.get(binaryKey);
        } else {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public WindowStoreIterator<V> fetch(K key, long timeFrom, long timeTo) {
        long segFrom = segmentId(timeFrom);
        long segTo = segmentId(Math.max(0L, timeTo));

        byte[] binaryFrom = WindowStoreUtils.toBinaryKey(key, timeFrom, 0, serdes);
        byte[] binaryTo = WindowStoreUtils.toBinaryKey(key, timeTo, Integer.MAX_VALUE, serdes);

        ArrayList<KeyValueIterator<byte[], byte[]>> iterators = new ArrayList<>();

        for (long segmentId = segFrom; segmentId <= segTo; segmentId++) {
            Segment segment = getSegment(segmentId);
            if (segment != null)
                iterators.add(segment.range(binaryFrom, binaryTo));
        }

        if (iterators.size() > 0) {
            return new RocksDBWindowStoreIterator<>(serdes, iterators.toArray(new KeyValueIterator[iterators.size()]));
        } else {
            return new RocksDBWindowStoreIterator<>(serdes);
        }
    }

    private Segment getSegment(long segmentId) {
        if (segmentId > currentSegmentId - segments.length) {
            int index = (int) (segmentId % segments.length);

            if (segments[index] != null && segments[index].id != segmentId) {
                cleanup();
            }

            if (segments[index] == null) {
                segments[index] = new Segment(segmentName(segmentId), name, segmentId);
                segments[index].openDB(context);
            }

            return segments[index];

        } else {
            return null;
        }
    }

    private void cleanup() {
        for (int i = 0; i < segments.length; i++) {
            if (segments[i] != null && segments[i].id <= currentSegmentId - segments.length) {
                segments[i].close();
                segments[i].destroy();
                segments[i] = null;
            }
        }
    }

    private long segmentId(long timestamp) {
        return timestamp / segmentInterval;
    }

    // this method is defined public since it is used for unit tests
    public String segmentName(long segmentId) {
        return formatter.format(new Date(segmentId * segmentInterval));
    }

    public long segmentIdFromSegmentName(String segmentName) {
        try {
            Date date = formatter.parse(segmentName);
            return date.getTime() / segmentInterval;
        } catch (Exception ex) {
            return -1L;
        }
    }

    // this method is defined public since it is used for unit tests
    public Set<Long> segmentIds() {
        HashSet<Long> segmentIds = new HashSet<>();

        for (Segment segment : segments) {
            if (segment != null)
                segmentIds.add(segment.id);
        }

        return segmentIds;
    }

}
