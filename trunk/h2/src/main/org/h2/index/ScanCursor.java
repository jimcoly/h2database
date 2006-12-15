/*
 * Copyright 2004-2006 H2 Group. Licensed under the H2 License, Version 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.index;

import java.sql.SQLException;

import org.h2.result.Row;


/**
 * @author Thomas
 */
public class ScanCursor implements Cursor {
    private ScanIndex scan;
    private Row row;

    ScanCursor(ScanIndex scan) {
        this.scan = scan;
        row = null;
    }

    public Row get() {
        return row;
    }
    
    public int getPos() {
        return row == null ? -1 : row.getPos();
    }

    public boolean next() throws SQLException {
        row = scan.getNextRow(row);
        return row != null;
    }
}
