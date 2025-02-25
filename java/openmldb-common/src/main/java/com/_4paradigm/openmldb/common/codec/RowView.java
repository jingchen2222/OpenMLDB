/*
 * Copyright 2021 4Paradigm
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com._4paradigm.openmldb.common.codec;

import com._4paradigm.openmldb.proto.Type.DataType;
import com._4paradigm.openmldb.proto.Common.ColumnDesc;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;

public class RowView {

    private final static byte BOOL_FALSE = 0;
    private ByteBuffer row = null;
    private int size = 0;
    private List<ColumnDesc> schema = new ArrayList<>();
    private int stringFieldCnt = 0;
    private int strFieldStartOffset = 0;
    private int strAddrLength = 0;
    private List<Integer> offsetVec = new ArrayList<>();
    private boolean isValid = false;

    public RowView(List<ColumnDesc> schema) throws Exception {
        this.schema = schema;
        this.isValid = true;
        if (schema.size() == 0) {
            isValid = false;
            return;
        }
        init();
    }

    public RowView(List<ColumnDesc> schema, ByteBuffer row, int size) throws Exception {
        this.schema = schema;
        this.isValid = true;
        this.size = size;
        if (row.order() == ByteOrder.BIG_ENDIAN) {
            row = row.order(ByteOrder.LITTLE_ENDIAN);
        }
        this.row = row;
        if (schema.size() == 0) {
            isValid = false;
            return;
        }
        if (init()) {
            reset(row, size);
        }
    }

    public static int getSchemaVersion(ByteBuffer row) {
        if (row.order() == ByteOrder.BIG_ENDIAN) {
            row = row.order(ByteOrder.LITTLE_ENDIAN);
        }
        byte bt = row.get(1);
        return bt;
    }

    private boolean init() throws Exception {
        strFieldStartOffset = CodecUtil.HEADER_LENGTH + CodecUtil.getBitMapSize(schema.size());
        for (int idx = 0; idx < schema.size(); idx++) {
            ColumnDesc column = schema.get(idx);
            if (column.getDataType() == DataType.kVarchar || column.getDataType() == DataType.kString) {
                offsetVec.add(stringFieldCnt);
                stringFieldCnt++;
            } else {
                if (CodecUtil.TYPE_SIZE_MAP.get(column.getDataType()) == null) {
                    isValid = false;
                    throw new Exception("type is not supported");
                } else {
                    offsetVec.add(strFieldStartOffset);
                    strFieldStartOffset += CodecUtil.TYPE_SIZE_MAP.get(column.getDataType());
                }
            }
        }
        return true;
    }

    public boolean reset(ByteBuffer row, int size) {
        if (schema.size() == 0 || row == null || size <= CodecUtil.HEADER_LENGTH ||
                row.getInt(CodecUtil.VERSION_LENGTH) != size) {
            isValid = false;
            return false;
        }
        if (row.order() == ByteOrder.BIG_ENDIAN) {
            row = row.order(ByteOrder.LITTLE_ENDIAN);
        }
        this.row = row;
        this.size = size;
        strAddrLength = CodecUtil.getAddrLength(size);
        return true;
    }

    private boolean reset(ByteBuffer row) {
        if (schema.size() == 0 || row == null) {
            isValid = false;
            return false;
        }
        if (row.order() == ByteOrder.BIG_ENDIAN) {
            row = row.order(ByteOrder.LITTLE_ENDIAN);
        }
        this.row = row;
        this.size = row.getInt(CodecUtil.VERSION_LENGTH);
        if (this.size < CodecUtil.HEADER_LENGTH) {
            isValid = false;
            return false;
        }
        strAddrLength = CodecUtil.getAddrLength(size);
        return true;
    }

    private boolean checkValid(int idx, DataType type) {
        if (row == null || !isValid) {
            return false;
        }
        if (idx >= schema.size()) {
            return false;
        }
        ColumnDesc column = schema.get(idx);
        if (column.getDataType() != type) {
            return false;
        }
        return true;
    }

    public boolean isNull(int idx) {
        return isNull(row, idx);
    }

    public boolean isNull(ByteBuffer row, int idx) {
        if (row.order() == ByteOrder.BIG_ENDIAN) {
            row = row.order(ByteOrder.LITTLE_ENDIAN);
        }
        int ptr = CodecUtil.HEADER_LENGTH + (idx >> 3);
        byte bt = row.get(ptr);
        int ret = bt & (1 << (idx & 0x07));
        return (ret > 0) ? true : false;
    }

    public static int getSize(ByteBuffer row) {
        return row.getInt(CodecUtil.VERSION_LENGTH);
    }

    public Boolean getBool(int idx) throws Exception {
        return (Boolean) getValue(row, idx, DataType.kBool);
    }

    public Integer getInt(int idx) throws Exception {
        return (Integer) getValue(row, idx, DataType.kInt);
    }

    public Long getTimestamp(int idx) throws Exception {
        return (Long) getValue(row, idx, DataType.kTimestamp);
    }

    public Long getBigInt(int idx) throws Exception {
        return (Long) getValue(row, idx, DataType.kBigInt);
    }

    public Short getSmallInt(int idx) throws Exception {
        return (Short) getValue(row, idx, DataType.kSmallInt);
    }

    public Float getFloat(int idx) throws Exception {
        return (Float) getValue(row, idx, DataType.kFloat);
    }

    public Double getDouble(int idx) throws Exception {
        return (Double) getValue(row, idx, DataType.kDouble);
    }

    public Object getIntegersNum(ByteBuffer row, int idx, DataType type) throws Exception {
        switch (type) {
            case kSmallInt: {
                return (Short) getValue(row, idx, type);
            }
            case kInt: {
                return (Integer) getValue(row, idx, type);
            }
            case kTimestamp:
            case kBigInt: {
                return (Long) getValue(row, idx, type);
            }
            default:
                throw new Exception("unsupported data type");
        }
    }

    public Object getValue(int idx, DataType type) throws Exception {
        return getValue(this.row, idx, type);
    }

    public Object getValue(ByteBuffer row, int idx) throws Exception {
        if (schema.size() == 0 || row == null || idx >= schema.size() || !isValid) {
            throw new Exception("input mistake");
        }
        if (row.order() == ByteOrder.BIG_ENDIAN) {
            row = row.order(ByteOrder.LITTLE_ENDIAN);
        }
        ColumnDesc column = schema.get(idx);
        int rowSize = getSize(row);
        if (rowSize <= CodecUtil.HEADER_LENGTH) {
            throw new Exception("row size is not bigger than header length");
        }
        int localStrAddrLength = CodecUtil.getAddrLength(rowSize);
        Object val = readObject(row, idx, column.getDataType(), rowSize,localStrAddrLength);
        return val;
    }

    public Object getValue(ByteBuffer row, int idx, DataType type) throws Exception {
        if (schema.size() == 0 || row == null || idx >= schema.size() || !isValid) {
            throw new Exception("input mistake");
        }
        if (row.order() == ByteOrder.BIG_ENDIAN) {
            row = row.order(ByteOrder.LITTLE_ENDIAN);
        }
        ColumnDesc column = schema.get(idx);
        if (column.getDataType() != type) {
            throw new Exception("data type mismatch");
        }
        int rowSize = getSize(row);
        if (rowSize <= CodecUtil.HEADER_LENGTH) {
            throw new Exception("row size is not bigger than header length");
        }
        int localStrAddrLength = CodecUtil.getAddrLength(rowSize);
        Object val = readObject(row, idx, type, rowSize,localStrAddrLength);
        return val;
    }

    public String getString(int idx) throws Exception {
        return (String) getValue(row, idx, DataType.kVarchar);
    }

    private Object readObject(ByteBuffer buf, int index, DataType dt, int rowSize,
                              int localStrAddrLength) throws Exception {
        if (isNull(buf, index)) {
            return null;
        }
        int offset = offsetVec.get(index);
        switch (dt) {
            case kBool:
                return buf.get(offset) == BOOL_FALSE ? false: true;
            case kSmallInt:
                return buf.getShort(offset);
            case kInt:
                return buf.getInt(offset);
            case kBigInt:
                return buf.getLong(offset);
            case kFloat:
                return buf.getFloat(offset);
            case kDouble:
                return buf.getDouble(offset);
            case kTimestamp:
                return new DateTime(buf.getLong(offset));
            case kDate:
                int date = buf.getInt(offset);
                int day = date & 0x0000000FF;
                date = date >> 8;
                int month = date & 0x0000FF;
                int year = date >> 8;
                return new Date(year, month, day);
            case kVarchar:
            case kString:
                int nextStrFieldOffset = 0;
                if (offset < stringFieldCnt - 1) {
                    nextStrFieldOffset = offset + 1;
                }
                return getStrField(buf, offset, nextStrFieldOffset,
                        strFieldStartOffset, localStrAddrLength, rowSize);
            default:
                throw new Exception("invalid column type" + dt.name());
        }
    }

    public void read(ByteBuffer buf, Object[] row, int start, int length) throws Exception{
        if (buf == null) throw new Exception("buf is null");
        int rowSize = buf.getInt(CodecUtil.VERSION_LENGTH);
        int localStrAddrLength = CodecUtil.getAddrLength(rowSize);
        int index = start;
        for (int i = 0; i < schema.size() && i < length ; i++) {
            ColumnDesc column = schema.get(i);
            row[index] = readObject(buf, i, column.getDataType(), rowSize, localStrAddrLength);
            index ++;
        }
    }
    public Object[] read(ByteBuffer buf) throws Exception{
        Object[] row = new Object[schema.size()];
        read(buf, row, 0, row.length);
        return row;
    }

    public List<ColumnDesc> getSchema() {
        return this.schema;
    }

    public String getStrField(ByteBuffer row, int fieldOffset,
                              int nextStrFieldOffset, int strStartOffset,
                              int addrSpace,
                              int total_size) throws Exception {
        if (row == null) {
            throw new Exception("row is null");
        }
        if (row.order() == ByteOrder.BIG_ENDIAN) {
            row = row.order(ByteOrder.LITTLE_ENDIAN);
        }
        int rowWithOffset = strStartOffset;
        int strOffset = 0;
        int nextStrOffset = 0;
        switch (addrSpace) {
            case 1: {
                strOffset = row.get(rowWithOffset + fieldOffset * addrSpace) & 0xFF;
                if (nextStrFieldOffset > 0) {
                    nextStrOffset = row.get(rowWithOffset + nextStrFieldOffset * addrSpace) & 0xFF;
                }
                break;
            }
            case 2: {
                strOffset = row.getShort(rowWithOffset + fieldOffset * addrSpace) & 0xFFFF;
                if (nextStrFieldOffset > 0) {
                    nextStrOffset = row.getShort(rowWithOffset + nextStrFieldOffset * addrSpace) & 0xFFFF;
                }
                break;
            }
            case 3: {
                int curRowWithOffset = rowWithOffset + fieldOffset * addrSpace;
                strOffset = row.get(curRowWithOffset) & 0xFF;
                strOffset = (strOffset << 8) + (row.get((curRowWithOffset + 1)) & 0xFF);
                strOffset = (strOffset << 8) + (row.get((curRowWithOffset + 2)) & 0xFF);
                if (nextStrFieldOffset > 0) {
                    int nextRowWithOffset = rowWithOffset + nextStrFieldOffset * addrSpace;
                    nextStrOffset = row.get((nextRowWithOffset)) & 0xFF;
                    nextStrOffset = (nextStrOffset << 8) + (row.get(nextRowWithOffset + 1) & 0xFF);
                    nextStrOffset = (nextStrOffset << 8) + (row.get(nextRowWithOffset + 2) & 0xFF);
                }
                break;
            }
            case 4: {
                strOffset = row.getInt(rowWithOffset + fieldOffset * addrSpace);
                if (nextStrFieldOffset > 0) {
                    nextStrOffset = row.getInt(rowWithOffset + nextStrFieldOffset * addrSpace);
                }
                break;
            }
            default: {
                throw new Exception("addrSpace mistakes");
            }
        }
        int len;
        if (nextStrFieldOffset <= 0) {
            len = total_size - strOffset;
        } else {
            len = nextStrOffset - strOffset;
        }
        byte[] arr = new byte[len];
        row.position(strOffset);
        row.get(arr);
        return new String(arr, CodecUtil.CHARSET);
    }
}
