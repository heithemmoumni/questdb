/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2018 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package com.questdb.griffin.engine.table;

import com.questdb.cairo.AbstractRecordCursorFactory;
import com.questdb.cairo.CairoConfiguration;
import com.questdb.cairo.sql.DataFrameCursorFactory;
import com.questdb.cairo.sql.RecordCursor;
import com.questdb.cairo.sql.RecordMetadata;
import com.questdb.griffin.engine.LongTreeSet;

abstract class AbstractTreeSetRecordCursorFactory extends AbstractRecordCursorFactory {
    protected final LongTreeSet treeSet;
    protected final DataFrameCursorFactory dataFrameCursorFactory;
    protected AbstractTreeSetRecordCursor cursor;

    public AbstractTreeSetRecordCursorFactory(
            RecordMetadata metadata,
            DataFrameCursorFactory dataFrameCursorFactory,
            CairoConfiguration configuration) {
        super(metadata);
        this.treeSet = new LongTreeSet(configuration.getSqlTreeDefaultPageSize());
        this.dataFrameCursorFactory = dataFrameCursorFactory;
    }

    @Override
    public void close() {
        treeSet.close();
        if (cursor != null) {
            cursor.close();
            cursor = null;
        }
    }

    @Override
    public RecordCursor getCursor() {
        cursor.of(dataFrameCursorFactory.getCursor());
        return cursor;
    }
}