/*******************************************************************************
 *  _  _ ___ ___     _ _
 * | \| | __/ __| __| | |__
 * | .` | _|\__ \/ _` | '_ \
 * |_|\_|_| |___/\__,_|_.__/
 *
 * Copyright (c) 2014-2015. The NFSdb project and its contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.nfsdb.ql.collections;

import com.nfsdb.collections.*;
import com.nfsdb.exceptions.JournalRuntimeException;
import com.nfsdb.factory.configuration.RecordColumnMetadata;
import com.nfsdb.factory.configuration.RecordMetadata;
import com.nfsdb.io.sink.CharSink;
import com.nfsdb.ql.Record;
import com.nfsdb.ql.RecordCursor;
import com.nfsdb.ql.StorageFacade;
import com.nfsdb.ql.impl.AbstractRecord;
import com.nfsdb.ql.impl.SelectedColumnsMetadata;
import com.nfsdb.storage.ColumnType;
import com.nfsdb.utils.Numbers;
import com.nfsdb.utils.Unsafe;

import java.io.OutputStream;

public class LastVarRecordMap implements LastRecordMap {
    private static final ObjList<RecordColumnMetadata> valueMetadata = new ObjList<>();
    private final static CharSequenceObjHashMap<String> EMPTY_MAP = new CharSequenceObjHashMap<>();
    private final MultiMap map;
    private final LongList pages = new LongList();
    private final int pageSize;
    private final int maxRecordSize;
    private final IntHashSet slaveKeyIndexes;
    private final IntHashSet masterKeyIndexes;
    private final IntList slaveValueIndexes;
    private final IntList varColumns = new IntList();
    private final FreeList freeList = new FreeList();
    private final ObjList<ColumnType> slaveKeyTypes;
    private final ObjList<ColumnType> masterKeyTypes;
    private final ObjList<ColumnType> slaveValueTypes;
    private final IntList fixedOffsets;
    private final int varOffset;
    private final IntIntHashMap symTableRemap = new IntIntHashMap();
    private final SelectedColumnsMetadata metadata;
    private final MapRecord record;
    private final int bits;
    private final int mask;
    private long appendOffset;
    private StorageFacade storageFacade;

    // todo: extract config
    // todo: make sure blobs are not supported and not provided
    public LastVarRecordMap(RecordMetadata masterMetadata, RecordMetadata slaveMetadata, CharSequenceHashSet keyColumns, int pageSize) {
        this.pageSize = Numbers.ceilPow2(pageSize);
        this.maxRecordSize = pageSize - 4;
        this.bits = Numbers.msb(this.pageSize);
        this.mask = this.pageSize - 1;

        final int ksz = keyColumns.size();
        this.masterKeyTypes = new ObjList<>(ksz);
        this.slaveKeyTypes = new ObjList<>(ksz);
        this.masterKeyIndexes = new IntHashSet(ksz);
        this.slaveKeyIndexes = new IntHashSet(ksz);

        // collect key field indexes for slave
        ObjList<RecordColumnMetadata> keyCols = new ObjList<>(ksz);

        for (int i = 0; i < ksz; i++) {
            int idx;
            idx = masterMetadata.getColumnIndex(keyColumns.get(i));
            masterKeyTypes.add(masterMetadata.getColumn(idx).getType());
            masterKeyIndexes.add(idx);

            idx = slaveMetadata.getColumnIndex(keyColumns.get(i));
            slaveKeyIndexes.add(idx);
            slaveKeyTypes.add(slaveMetadata.getColumn(idx).getType());
            keyCols.add(slaveMetadata.getColumn(idx));
        }

        this.fixedOffsets = new IntList(ksz - keyCols.size());
        this.slaveValueIndexes = new IntList(ksz - keyCols.size());
        this.slaveValueTypes = new ObjList<>(ksz - keyCols.size());

        ObjList<CharSequence> slaveColumnNames = new ObjList<>();
        int varOffset = 0;
        // collect indexes of non-key fields in slave record
        for (int i = 0, n = slaveMetadata.getColumnCount(); i < n; i++) {

            if (slaveKeyIndexes.contains(i)) {
                continue;
            }

            slaveColumnNames.add(slaveMetadata.getColumn(i).getName());
            fixedOffsets.add(varOffset);
            slaveValueIndexes.add(i);
            ColumnType type = slaveMetadata.getColumn(i).getType();
            slaveValueTypes.add(type);

            switch (type) {
                case INT:
                case FLOAT:
                case SYMBOL:
                    varOffset += 4;
                    break;
                case LONG:
                case DOUBLE:
                case DATE:
                    varOffset += 8;
                    break;
                case BOOLEAN:
                case BYTE:
                    varOffset++;
                    break;
                case SHORT:
                    varOffset += 2;
                    break;
                default:
                    varColumns.add(i);
                    varOffset += 4;
                    break;
            }
        }

        if (varOffset > maxRecordSize) {
            throw new JournalRuntimeException("Record size is too large");
        }

        this.varOffset = varOffset;
        this.map = new MultiMap(valueMetadata, keyCols, null);
        this.metadata = new SelectedColumnsMetadata(slaveMetadata, slaveColumnNames, EMPTY_MAP);
        this.record = new MapRecord(this.metadata);
    }

    @Override
    public void close() {
        for (int i = 0; i < pages.size(); i++) {
            Unsafe.getUnsafe().freeMemory(pages.getQuick(i));
        }
        pages.clear();
    }

    @Override
    public Record get(Record master) {
        MapValues values = getByMaster(master);
        if (values == null) {
            return null;
        }

        long offset = values.getLong(0);
        return record.of(pages.getQuick(pageIndex(offset)) + pageOffset(offset));
    }

    @Override
    public RecordMetadata getMetadata() {
        return metadata;
    }

    @Override
    public void put(Record record) {
        final MapValues values = getBySlave(record);
        // calculate record size
        int size = varOffset;
        for (int i = 0, n = varColumns.size(); i < n; i++) {
            size += record.getStrLen(varColumns.getQuick(i)) * 2 + 4;
        }

        // record is larger than page size
        // won't handle that as we don't write one record across multiple pages
        if (size > maxRecordSize) {
            throw new JournalRuntimeException("Record size is too large");
        }

        // new record, append right away
        if (values.isNew()) {
            appendRec(record, size, values);
        } else {
            // old record, attempt to overwrite
            long offset = values.getLong(0);
            int pgInx = pageIndex(offset);
            int pgOfs = pageOffset(offset);

            int oldSize = Unsafe.getUnsafe().getInt(pages.getQuick(pgInx) + pgOfs);

            if (size > oldSize) {
                // new record is larger than previous, must write to new location
                // in the mean time free old location
                freeList.add(offset, oldSize);

                if (freeList.getTotalSize() < maxRecordSize) {
                    // if free list is too small, keep appending
                    appendRec(record, size, values);
                } else {
                    // free list is large enough, we need to start reusing
                    long _offset = freeList.findAndRemove(size);
                    if (_offset == -1) {
                        // could not find suitable free block, append
                        appendRec(record, size, values);
                    } else {
                        writeRec(record, _offset);
                    }
                }
            } else {
                // new record is smaller or equal in size to previous one, overwrite safely
                writeRec(record, offset);
            }
        }
    }

    @Override
    public void setSlaveCursor(RecordCursor<? extends Record> cursor) {
        // hold on to storage facade an remap foreign indexes as
        // queries to symbols will be made using our indexes
        this.storageFacade = cursor.getSymFacade();
        for (int i = 0, n = slaveValueTypes.size(); i < n; i++) {
            if (slaveValueTypes.getQuick(i) == ColumnType.SYMBOL) {
                symTableRemap.put(i, slaveValueIndexes.getQuick(i));
            }
        }
    }

    private static MultiMap.KeyWriter get(MultiMap map, Record record, IntHashSet indices, ObjList<ColumnType> types) {
        MultiMap.KeyWriter kw = map.keyWriter();
        for (int i = 0, n = indices.size(); i < n; i++) {
            int idx = indices.get(i);
            switch (types.getQuick(i)) {
                case INT:
                    kw.putInt(record.getInt(idx));
                    break;
                case LONG:
                    kw.putLong(record.getLong(idx));
                    break;
                case FLOAT:
                    kw.putFloat(record.getFloat(idx));
                    break;
                case DOUBLE:
                    kw.putDouble(record.getDouble(idx));
                    break;
                case BOOLEAN:
                    kw.putBoolean(record.getBool(idx));
                    break;
                case BYTE:
                    kw.putByte(record.get(idx));
                    break;
                case SHORT:
                    kw.putShort(record.getShort(idx));
                    break;
                case DATE:
                    kw.putLong(record.getDate(idx));
                    break;
                case STRING:
                    kw.putStr(record.getFlyweightStr(idx));
                    break;
                case SYMBOL:
                    // this is key field
                    // we have to write out string rather than int
                    // because master int values for same strings can be different
                    kw.putStr(record.getSym(idx));
                    break;
            }
        }
        return kw;
    }

    private void appendRec(Record record, int size, MapValues values) {
        int pgInx = pageIndex(appendOffset);
        int pgOfs = pageOffset(appendOffset);

        // input is net size of payload
        // add 4 byte prefix + 10%
        size = size + 4 + size / 10;

        if (pgOfs + size > pageSize) {
            pgInx++;
            pgOfs = 0;
            values.putLong(0, appendOffset = (pgInx * pageSize));
        } else {
            values.putLong(0, appendOffset);
        }

        appendOffset += size;

        // allocate if necessary
        if (pgInx == pages.size()) {
            pages.add(Unsafe.getUnsafe().allocateMemory(pageSize));
        }

        long addr = pages.getQuick(pgInx) + pgOfs;
        // write out record size + 10%
        // and actual size
        Unsafe.getUnsafe().putInt(addr, size - 4);
        writeRec0(addr + 4, record);
    }

    private MapValues getByMaster(Record record) {
        return map.getValues(get(map, record, masterKeyIndexes, masterKeyTypes));
    }

    private MapValues getBySlave(Record record) {
        return map.getOrCreateValues(get(map, record, slaveKeyIndexes, slaveKeyTypes));
    }

    private int pageIndex(long offset) {
        return (int) (offset >> bits);
    }

    private int pageOffset(long offset) {
        return (int) (offset & mask);
    }

    private void writeRec(Record record, long offset) {
        writeRec0(pages.getQuick(pageIndex(offset)) + pageOffset(offset) + 4, record);
    }

    private void writeRec0(long addr, Record record) {
        int varOffset = this.varOffset;
        for (int i = 0, n = slaveValueIndexes.size(); i < n; i++) {
            int idx = slaveValueIndexes.getQuick(i);
            long address = addr + fixedOffsets.getQuick(i);
            switch (slaveValueTypes.getQuick(i)) {
                case INT:
                case SYMBOL:
                    // write out int as symbol value
                    // need symbol facade to resolve back to string
                    Unsafe.getUnsafe().putInt(address, record.getInt(idx));
                    break;
                case LONG:
                    Unsafe.getUnsafe().putLong(address, record.getLong(idx));
                    break;
                case FLOAT:
                    Unsafe.getUnsafe().putFloat(address, record.getFloat(idx));
                    break;
                case DOUBLE:
                    Unsafe.getUnsafe().putDouble(address, record.getDouble(idx));
                    break;
                case BOOLEAN:
                case BYTE:
                    Unsafe.getUnsafe().putByte(address, record.get(idx));
                    break;
                case SHORT:
                    Unsafe.getUnsafe().putShort(address, record.getShort(idx));
                    break;
                case DATE:
                    Unsafe.getUnsafe().putLong(address, record.getDate(idx));
                    break;
                case STRING:
                    Unsafe.getUnsafe().putInt(address, varOffset);
                    varOffset += writeStr(addr + varOffset, record.getFlyweightStr(idx));
                    break;
            }
        }
    }

    private int writeStr(long addr, CharSequence value) {
        int len = value.length();
        Unsafe.getUnsafe().putInt(addr, len);
        addr += 4;
        for (int i = 0; i < len; i++) {
            Unsafe.getUnsafe().putChar(addr + (i << 1), value.charAt(i));
        }
        return (len << 1) + 4;
    }

    public class MapRecord extends AbstractRecord {
        private final DirectCharSequence cs = new DirectCharSequence();
        private long address;
        private char[] strBuf;

        public MapRecord(RecordMetadata metadata) {
            super(metadata);
        }

        @Override
        public byte get(int col) {
            return Unsafe.getUnsafe().getByte(address + fixedOffsets.getQuick(col));
        }

        @Override
        public void getBin(int col, OutputStream s) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DirectInputStream getBin(int col) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getBinLen(int col) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean getBool(int col) {
            return Unsafe.getUnsafe().getByte(address + fixedOffsets.getQuick(col)) == 1;
        }

        @Override
        public long getDate(int col) {
            return Unsafe.getUnsafe().getLong(address + fixedOffsets.getQuick(col));
        }

        @Override
        public double getDouble(int col) {
            return Unsafe.getUnsafe().getDouble(address + fixedOffsets.getQuick(col));
        }

        @Override
        public float getFloat(int col) {
            return Unsafe.getUnsafe().getFloat(address + fixedOffsets.getQuick(col));
        }

        @Override
        public CharSequence getFlyweightStr(int col) {
            int offset = Unsafe.getUnsafe().getInt(address + fixedOffsets.getQuick(col));
            int len = Unsafe.getUnsafe().getInt(address + offset);
            return cs.init(address + offset + 4, address + offset + 4 + len * 2);
        }

        @Override
        public int getInt(int col) {
            return Unsafe.getUnsafe().getInt(address + fixedOffsets.getQuick(col));
        }

        @Override
        public long getLong(int col) {
            return Unsafe.getUnsafe().getLong(address + fixedOffsets.getQuick(col));
        }

        @Override
        public long getRowId() {
            return -1;
        }

        @Override
        public short getShort(int col) {
            return Unsafe.getUnsafe().getShort(address + fixedOffsets.getQuick(col));
        }

        @Override
        public CharSequence getStr(int col) {
            int offset = Unsafe.getUnsafe().getInt(address + fixedOffsets.getQuick(col));
            int len = Unsafe.getUnsafe().getInt(address + offset);

            if (strBuf == null || strBuf.length < len) {
                strBuf = new char[len];
            }

            long lim = address + offset + 4 + len * 2;
            int i = 0;
            for (long p = address + offset + 4; p < lim; p += 2) {
                strBuf[i++] = Unsafe.getUnsafe().getChar(p);
            }

            return new String(strBuf, 0, len);
        }

        @Override
        public void getStr(int col, CharSink sink) {
            int offset = Unsafe.getUnsafe().getInt(address + fixedOffsets.getQuick(col));
            int len = Unsafe.getUnsafe().getInt(address + offset);

            long lim = address + offset + 4 + len * 2;
            for (long p = address + offset + 4; p < lim; p += 2) {
                sink.put(Unsafe.getUnsafe().getChar(p));
            }
        }

        @Override
        public int getStrLen(int col) {
            int offset = Unsafe.getUnsafe().getInt(address + fixedOffsets.getQuick(col));
            return Unsafe.getUnsafe().getInt(address + offset);
        }

        @Override
        public String getSym(int col) {
            return storageFacade.getSymbolTable(symTableRemap.get(col)).value(Unsafe.getUnsafe().getInt(address + fixedOffsets.getQuick(col)));
        }

        private MapRecord of(long address) {
            this.address = address + 4;
            return this;
        }
    }

    static {
        valueMetadata.add(LongMetadata.INSTANCE);
    }
}