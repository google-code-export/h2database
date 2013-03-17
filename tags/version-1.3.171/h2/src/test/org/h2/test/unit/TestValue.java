/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.unit;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.UUID;
import org.h2.constant.ErrorCode;
import org.h2.test.TestBase;
import org.h2.test.utils.AssertThrows;
import org.h2.tools.SimpleResultSet;
import org.h2.value.DataType;
import org.h2.value.Value;
import org.h2.value.ValueArray;
import org.h2.value.ValueBytes;
import org.h2.value.ValueDecimal;
import org.h2.value.ValueDouble;
import org.h2.value.ValueFloat;
import org.h2.value.ValueLobDb;
import org.h2.value.ValueResultSet;
import org.h2.value.ValueString;
import org.h2.value.ValueUuid;

/**
 * Tests features of values.
 */
public class TestValue extends TestBase {

    /**
     * Run just this test.
     *
     * @param a ignored
     */
    public static void main(String... a) throws Exception {
        TestBase.createCaller().init().test();
    }

    public void test() throws SQLException {
        testCastTrim();
        testValueResultSet();
        testDataType();
        testUUID();
        testDouble(false);
        testDouble(true);
        testModulusDouble();
        testModulusDecimal();
        testModulusOperator();
    }

    private void testCastTrim() {
        Value v;
        String spaces = new String(new char[100]).replace((char) 0, ' ');

        v = ValueArray.get(new Value[]{ValueString.get("hello"), ValueString.get("world")});
        assertEquals(10, v.getPrecision());
        assertEquals(5, v.convertPrecision(5, true).getPrecision());
        v = ValueArray.get(new Value[]{ValueString.get(""), ValueString.get("")});
        assertEquals(0, v.getPrecision());
        assertEquals("('')", v.convertPrecision(1, true).toString());

        v = ValueBytes.get(spaces.getBytes());
        assertEquals(100, v.getPrecision());
        assertEquals(10, v.convertPrecision(10, false).getPrecision());
        assertEquals(10, v.convertPrecision(10, false).getBytes().length);
        assertEquals(32, v.convertPrecision(10, false).getBytes()[9]);
        assertEquals(10, v.convertPrecision(10, true).getPrecision());

        final Value vd = ValueDecimal.get(new BigDecimal("1234567890.123456789"));
        assertEquals(19, vd.getPrecision());
        assertEquals("1234567890.1234567", vd.convertPrecision(10, true).getString());
        new AssertThrows(ErrorCode.NUMERIC_VALUE_OUT_OF_RANGE_1) { public void test() {
            vd.convertPrecision(10, false);
        }};

        v = ValueLobDb.createSmallLob(Value.CLOB, spaces.getBytes(), 100);
        assertEquals(100, v.getPrecision());
        assertEquals(10, v.convertPrecision(10, false).getPrecision());
        assertEquals(10, v.convertPrecision(10, false).getString().length());
        assertEquals("          ", v.convertPrecision(10, false).getString());
        assertEquals(10, v.convertPrecision(10, true).getPrecision());

        v = ValueLobDb.createSmallLob(Value.BLOB, spaces.getBytes(), 100);
        assertEquals(100, v.getPrecision());
        assertEquals(10, v.convertPrecision(10, false).getPrecision());
        assertEquals(10, v.convertPrecision(10, false).getBytes().length);
        assertEquals(32, v.convertPrecision(10, false).getBytes()[9]);
        assertEquals(10, v.convertPrecision(10, true).getPrecision());

        ResultSet rs = new SimpleResultSet();
        v = ValueResultSet.get(rs);
        assertEquals(Integer.MAX_VALUE, v.getPrecision());
        assertEquals(Integer.MAX_VALUE, v.convertPrecision(10, false).getPrecision());
        assertTrue(rs == v.convertPrecision(10, false).getObject());
        assertFalse(rs == v.convertPrecision(10, true).getObject());
        assertEquals(Integer.MAX_VALUE, v.convertPrecision(10, true).getPrecision());

        v = ValueString.get(spaces);
        assertEquals(100, v.getPrecision());
        assertEquals(10, v.convertPrecision(10, false).getPrecision());
        assertEquals("          ", v.convertPrecision(10, false).getString());
        assertEquals("          ", v.convertPrecision(10, true).getString());

    }

    private void testValueResultSet() throws SQLException {
        SimpleResultSet rs = new SimpleResultSet();
        rs.addColumn("ID", Types.INTEGER, 0, 0);
        rs.addColumn("NAME", Types.VARCHAR, 255, 0);
        rs.addRow(1, "Hello");
        rs.addRow(2, "World");
        rs.addRow(3, "Peace");

        ValueResultSet v;
        v = ValueResultSet.get(rs);
        assertTrue(rs == v.getObject());

        v = ValueResultSet.getCopy(rs, 2);
        assertEquals(0, v.hashCode());
        assertEquals(Integer.MAX_VALUE, v.getDisplaySize());
        assertEquals(Integer.MAX_VALUE, v.getPrecision());
        assertEquals(0, v.getScale());
        assertEquals("", v.getSQL());
        assertEquals(Value.RESULT_SET, v.getType());
        assertEquals("((1, Hello), (2, World))", v.getString());
        rs.beforeFirst();
        ValueResultSet v2 = ValueResultSet.getCopy(rs, 2);
        assertTrue(v.equals(v));
        assertFalse(v.equals(v2));
        rs.beforeFirst();

        ResultSet rs2 = v.getResultSet();
        rs2.next();
        rs.next();
        assertEquals(rs.getInt(1), rs2.getInt(1));
        assertEquals(rs.getString(2), rs2.getString(2));
        rs2.next();
        rs.next();
        assertEquals(rs.getInt(1), rs2.getInt(1));
        assertEquals(rs.getString(2), rs2.getString(2));
        assertFalse(rs2.next());
        assertTrue(rs.next());
    }

    private void testDataType() {
        testDataType(Value.NULL, null);
        testDataType(Value.NULL, Void.class);
        testDataType(Value.NULL, void.class);
        testDataType(Value.ARRAY, String[].class);
        testDataType(Value.STRING, String.class);
        testDataType(Value.INT, Integer.class);
        testDataType(Value.LONG, Long.class);
        testDataType(Value.BOOLEAN, Boolean.class);
        testDataType(Value.DOUBLE, Double.class);
        testDataType(Value.BYTE, Byte.class);
        testDataType(Value.SHORT, Short.class);
        testDataType(Value.FLOAT, Float.class);
        testDataType(Value.BYTES, byte[].class);
        testDataType(Value.UUID, UUID.class);
        testDataType(Value.NULL, Void.class);
        testDataType(Value.DECIMAL, BigDecimal.class);
        testDataType(Value.RESULT_SET, ResultSet.class);
        testDataType(Value.BLOB, Value.ValueBlob.class);
        testDataType(Value.CLOB, Value.ValueClob.class);
        testDataType(Value.DATE, Date.class);
        testDataType(Value.TIME, Time.class);
        testDataType(Value.TIMESTAMP, Timestamp.class);
        testDataType(Value.TIMESTAMP, java.util.Date.class);
        testDataType(Value.CLOB, java.io.Reader.class);
        testDataType(Value.CLOB, java.sql.Clob.class);
        testDataType(Value.BLOB, java.io.InputStream.class);
        testDataType(Value.BLOB, java.sql.Blob.class);
        testDataType(Value.ARRAY, Object[].class);
        testDataType(Value.JAVA_OBJECT, StringBuffer.class);
    }

    private void testDataType(int type, Class<?> clazz) {
        assertEquals(type, DataType.getTypeFromClass(clazz));
    }

    private void testDouble(boolean useFloat) {
        double[] d = {
                Double.NEGATIVE_INFINITY,
                -1,
                0,
                1,
                Double.POSITIVE_INFINITY,
                Double.NaN
        };
        Value[] values = new Value[d.length];
        for (int i = 0; i < d.length; i++) {
            Value v = useFloat ? (Value) ValueFloat.get((float) d[i]) : (Value) ValueDouble.get(d[i]);
            values[i] = v;
            assertTrue(values[i].compareTypeSave(values[i], null) == 0);
            assertTrue(v.equals(v));
            assertEquals(i < 2 ? -1 : i > 2 ? 1 : 0, v.getSignum());
        }
        for (int i = 0; i < d.length - 1; i++) {
            assertTrue(values[i].compareTypeSave(values[i+1], null) < 0);
            assertTrue(values[i + 1].compareTypeSave(values[i], null) > 0);
            assertTrue(!values[i].equals(values[i+1]));
        }
    }

    private void testUUID() {
        long maxHigh = 0, maxLow = 0, minHigh = -1L, minLow = -1L;
        for (int i = 0; i < 100; i++) {
            ValueUuid uuid = ValueUuid.getNewRandom();
            maxHigh |= uuid.getHigh();
            maxLow |= uuid.getLow();
            minHigh &= uuid.getHigh();
            minLow &= uuid.getLow();
        }
        ValueUuid max = ValueUuid.get(maxHigh, maxLow);
        assertEquals("ffffffff-ffff-4fff-bfff-ffffffffffff", max.getString());
        ValueUuid min = ValueUuid.get(minHigh, minLow);
        assertEquals("00000000-0000-4000-8000-000000000000", min.getString());
    }

    private void testModulusDouble() {
        final ValueDouble vd1 = ValueDouble.get(12);
        new AssertThrows(ErrorCode.DIVISION_BY_ZERO_1) { public void test() {
            vd1.modulus(ValueDouble.get(0));
        }};
        ValueDouble vd2 = ValueDouble.get(10);
        ValueDouble vd3 = vd1.modulus(vd2);
        assertEquals(2, vd3.getDouble());
    }

    private void testModulusDecimal() {
        final ValueDecimal vd1 = ValueDecimal.get(new BigDecimal(12));
        new AssertThrows(ErrorCode.DIVISION_BY_ZERO_1) { public void test() {
            vd1.modulus(ValueDecimal.get(new BigDecimal(0)));
        }};
        ValueDecimal vd2 = ValueDecimal.get(new BigDecimal(10));
        ValueDecimal vd3 = vd1.modulus(vd2);
        assertEquals(2, vd3.getDouble());
    }

    private void testModulusOperator() throws SQLException {
        Connection conn = getConnection("modulus");
        try {
            ResultSet rs = conn.createStatement().executeQuery("CALL 12 % 10");
            rs.next();
            assertEquals(2, rs.getInt(1));
        } finally {
            conn.close();
            deleteDb("modulus");
        }
    }

}
