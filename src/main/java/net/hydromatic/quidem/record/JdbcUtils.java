/*
 * Licensed to Julian Hyde under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hydromatic.quidem.record;

import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableBiMap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static java.util.Arrays.fill;

/** JDBC utilities. */
public abstract class JdbcUtils {

  private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT =
      ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));
  private static final ThreadLocal<SimpleDateFormat> TIME_FORMAT =
      ThreadLocal.withInitial(() -> new SimpleDateFormat("hh:mm:ss"));
  private static final ThreadLocal<SimpleDateFormat> TIMESTAMP_FORMAT =
      ThreadLocal.withInitial(() ->
          new SimpleDateFormat("yyyy-MM-dd hh:mm:ss"));

  // utility class
  private JdbcUtils() {
  }

  /** Names and values of fields in {@link Types}. */
  private static final BiMap<String, Integer> JDBC_TYPES;

  static {
    final ImmutableBiMap.Builder<String, Integer> builder =
        ImmutableBiMap.builder();
    for (Field field : Types.class.getFields()) {
      if ((field.getModifiers() & Modifier.STATIC) != 0
          && field.getType() == int.class) {
        try {
          builder.put(field.getName(), field.getInt(null));
        } catch (IllegalAccessException e) {
          // ignore field
        }
      }
    }
    JDBC_TYPES = builder.build();
  }

  static String typeString(int type) {
    final String typeName = JDBC_TYPES.inverse().get(type);
    if (typeName != null) {
      return typeName;
    }
    return "type" + type;
  }

  static int stringType(String typeName) {
    final Integer type = JDBC_TYPES.get(typeName);
    if (type != null) {
      return type;
    }
    return Types.VARCHAR;
  }

  /** Writes a ResultSet to a builder.
   *
   * <p>Representation is like the following:
   *
   * <blockquote><pre>
   *   dname:VARCHAR, c:INTEGER
   *   SALES, 3
   *   MARKETING, 4
   * </pre></blockquote>
   */
  public static void write(StringBuilder b, ResultSet r) throws SQLException {
    final ResultSetMetaData metaData = r.getMetaData();
    final int columnCount = metaData.getColumnCount();
    for (int i = 0; i < columnCount; i++) {
      if (i > 0) {
        b.append(",");
      }
      b.append(metaData.getColumnName(i + 1));
      b.append(':');
      b.append(typeString(metaData.getColumnType(i + 1)));
    }
    b.append('\n');
    while (r.next()) {
      for (int i = 0; i < columnCount; i++) {
        if (i > 0) {
          b.append(",");
        }
        final String s = r.getString(i + 1);
        if (s != null) {
          // We write NULL as the empty string. This works well for types like
          // INTEGER, DATE, TIMESTAMP, BOOLEAN. For VARCHAR, we represent the
          // empty string as ''.
          if (s.isEmpty()) {
            b.append("''");
          } else {
            b.append(s);
          }
        }
      }
      b.append('\n');
    }
  }

  /** Parses a line containing comma-separated values into an array of
   * strings.
   *
   * <p>Assumes that the array has exactly the right number of slots.
   * Puts nulls into slots where the string is empty or there are not enough
   * commas.
   *
   * <p>If field values start with a single-quote, reads until it finds a
   * closing single-quote. Commas inside single-quotes are part of the value.
   * Single-quotes that are part of the value must be doubled. */
  public static void parse(String[] fields, String s) {
    int f = 0;
    for (int i = 0;; i++) {
      if (i >= s.length()) {
        // Reached end of line.
        break;
      }
      final char c = s.charAt(i);
      if (c == ',') {
        fields[f++] = null;
        continue;
      }
      if (c == '\'') {
        // Looking at a single-quoted string. Scan until we see the
        // closing single-quote. Commas are not special.
        ++i;
        final int start = i;
        boolean escape = false;
        for (;;) {
          if (i >= s.length()) {
            throw new IllegalArgumentException("missing \"'\"");
          }
          final char c2 = s.charAt(i);
          ++i;
          if (c2 == '\'') {
            if (i >= s.length()) {
              // Closing single-quote at end of line.
              String field = s.substring(start, i - 1);
              if (escape) {
                field = field.replace("''", "'");
              }
              fields[f++] = field;
              break;
            }
            final char c3 = s.charAt(i);
            if (c3 == '\'') {
              escape = true;
              ++i;
              if (i >= s.length()) {
                throw new IllegalArgumentException(
                    "missing \"'\" following escaped \"'\"");
              }
            } else if (c3 == ',') {
              // Closing single-quote occurs before comma
              String field = s.substring(start, i - 1);
              if (escape) {
                field = field.replace("''", "'");
              }
              fields[f++] = field;
              break;
            } else {
              throw new IllegalArgumentException(
                  "quoted string must be followed by comma or line ending");
            }
          }
        }
      } else {
        final int start = i;
        for (;;) {
          ++i;
          if (i >= s.length()) {
            // Field terminated by end of line
            fields[f++] = s.substring(start, i);
            break;
          }
          final char c2 = s.charAt(i);
          if (c2 == ',') {
            // Field terminated by comma
            fields[f++] = s.substring(start, i);
            break;
          }
        }
      }
    }
    // Reached end of line. Any remaining fields are null.
    while (f < fields.length) {
      fields[f++] = null;
    }
  }

  static void read(String s, Consumer<ResultSet> consumer) throws IOException {
    final BufferedReader bufferedReader =
        new BufferedReader(new StringReader(s));
    final List<String> names = new ArrayList<>();
    final List<String> typeNames = new ArrayList<>();
    String line = bufferedReader.readLine();
    if (line == null) {
      return; // no header row
    }
    final String[] headers = line.split(",");
    for (String header : headers) {
      int colon = header.indexOf(':');
      if (colon < 0) {
        names.add(header);
        typeNames.add("VARCHAR");
      } else {
        names.add(header.substring(0, colon));
        typeNames.add(header.substring(colon + 1));
      }
    }
    List<String> lines = new ArrayList<>();
    for (;;) {
      line = bufferedReader.readLine();
      if (line == null) {
        break;
      }
      lines.add(line);
    }
    final ResultSet resultSet =
        new NullResultSet() {
          private boolean wasNull;
          int line = -1;
          final String[] fields = new String[names.size()];

          @Override public ResultSetMetaData getMetaData() {
            return new NullResultSetMetaData() {
              @Override public int getColumnCount() {
                return names.size();
              }

              @Override public String getColumnName(int column) {
                return names.get(column - 1);
              }

              @Override public String getColumnTypeName(int column) {
                return typeNames.get(column - 1);
              }

              @Override public int getColumnType(int column) {
                final String typeName = getColumnTypeName(column);
                return stringType(typeName);
              }
            };
          }

          @Override public boolean next() {
            ++line;
            if (line >= lines.size()) {
              fill(fields, null);
              return false;
            }

            // Populate fields by parsing line
            parse(fields, lines.get(line));
            return true;
          }

          @Override public boolean wasNull() {
            return wasNull;
          }

          @Override public String getString(int columnIndex) {
            final String s = fields[columnIndex - 1];
            wasNull = s == null;
            return s;
          }

          @Override public boolean getBoolean(int columnIndex) {
            final String s = getString(columnIndex);
            // no need to check for null; parseBoolean(null) returns false
            return Boolean.parseBoolean(s);
          }

          @Override public short getShort(int columnIndex) {
            final String s = getString(columnIndex);
            return s == null ? 0 : Short.parseShort(s);
          }

          @Override public int getInt(int columnIndex) {
            final String s = getString(columnIndex);
            return s == null ? 0 : Integer.parseInt(s);
          }

          @Override public long getLong(int columnIndex) {
            final String s = getString(columnIndex);
            return s == null ? 0 : Long.parseLong(s);
          }

          @Override public double getDouble(int columnIndex) {
            final String s = getString(columnIndex);
            return s == null ? 0 : Double.parseDouble(s);
          }

          @Override public float getFloat(int columnIndex) {
            final String s = getString(columnIndex);
            return s == null ? 0 : Float.parseFloat(s);
          }

          @Override public BigDecimal getBigDecimal(int columnIndex) {
            final String s = getString(columnIndex);
            return s == null ? null : new BigDecimal(s);
          }

          @Override public Date getDate(int columnIndex) {
            final String s = getString(columnIndex);
            if (s == null) {
              return null;
            }
            try {
              return new Date(DATE_FORMAT.get().parse(s).getTime());
            } catch (ParseException e) {
              throw new IllegalArgumentException(
                  String.format("invalid date '%s'", s));
            }
          }

          @Override public Time getTime(int columnIndex) {
            final String s = getString(columnIndex);
            if (s == null) {
              return null;
            }
            try {
              return new Time(TIME_FORMAT.get().parse(s).getTime());
            } catch (ParseException e) {
              throw new IllegalArgumentException(
                  String.format("invalid time '%s'", s));
            }
          }

          @Override public Timestamp getTimestamp(int columnIndex) {
            final String s = getString(columnIndex);
            if (s == null) {
              return null;
            }
            try {
              return new Timestamp(TIMESTAMP_FORMAT.get().parse(s).getTime());
            } catch (ParseException e) {
              throw new IllegalArgumentException(
                  String.format("invalid timestamp '%s'", s));
            }
          }
        };
    consumer.accept(resultSet);
  }

  /** Implementation of {@link java.sql.ResultSet} that implements every method
   * but mostly does nothing. Use it as a base class. */
  private static class NullResultSet implements ResultSet {
    @Override public boolean next() {
      return false;
    }

    @Override public void close() {
    }

    @Override public boolean wasNull() {
      return false;
    }

    @Override public String getString(int columnIndex) {
      return "";
    }

    @Override public boolean getBoolean(int columnIndex) {
      return false;
    }

    @Override public byte getByte(int columnIndex) {
      return 0;
    }

    @Override public short getShort(int columnIndex) {
      return 0;
    }

    @Override public int getInt(int columnIndex) {
      return 0;
    }

    @Override public long getLong(int columnIndex) {
      return 0;
    }

    @Override public float getFloat(int columnIndex) {
      return 0;
    }

    @Override public double getDouble(int columnIndex) {
      return 0;
    }

    @Override public BigDecimal getBigDecimal(int columnIndex, int scale) {
      return null;
    }

    @Override public byte[] getBytes(int columnIndex) {
      return new byte[0];
    }

    @Override public Date getDate(int columnIndex) {
      return null;
    }

    @Override public Time getTime(int columnIndex) {
      return null;
    }

    @Override public Timestamp getTimestamp(int columnIndex) {
      return null;
    }

    @Override public InputStream getAsciiStream(int columnIndex) {
      return null;
    }

    @Override public InputStream getUnicodeStream(int columnIndex) {
      return null;
    }

    @Override public InputStream getBinaryStream(int columnIndex) {
      return null;
    }

    @Override public String getString(String columnLabel) {
      return "";
    }

    @Override public boolean getBoolean(String columnLabel) {
      return false;
    }

    @Override public byte getByte(String columnLabel) {
      return 0;
    }

    @Override public short getShort(String columnLabel) {
      return 0;
    }

    @Override public int getInt(String columnLabel) {
      return 0;
    }

    @Override public long getLong(String columnLabel) {
      return 0;
    }

    @Override public float getFloat(String columnLabel) {
      return 0;
    }

    @Override public double getDouble(String columnLabel) {
      return 0;
    }

    @Override public BigDecimal getBigDecimal(String columnLabel, int scale) {
      return null;
    }

    @Override public byte[] getBytes(String columnLabel) {
      return new byte[0];
    }

    @Override public Date getDate(String columnLabel) {
      return null;
    }

    @Override public Time getTime(String columnLabel) {
      return null;
    }

    @Override public Timestamp getTimestamp(String columnLabel) {
      return null;
    }

    @Override public InputStream getAsciiStream(String columnLabel) {
      return null;
    }

    @Override public InputStream getUnicodeStream(String columnLabel) {
      return null;
    }

    @Override public InputStream getBinaryStream(String columnLabel) {
      return null;
    }

    @Override public SQLWarning getWarnings() {
      return null;
    }

    @Override public void clearWarnings() {
    }

    @Override public String getCursorName() {
      return "";
    }

    @Override public ResultSetMetaData getMetaData() {
      return null;
    }

    @Override public Object getObject(int columnIndex) {
      return null;
    }

    @Override public Object getObject(String columnLabel) {
      return null;
    }

    @Override public int findColumn(String columnLabel) {
      return 0;
    }

    @Override public Reader getCharacterStream(int columnIndex) {
      return null;
    }

    @Override public Reader getCharacterStream(String columnLabel) {
      return null;
    }

    @Override public BigDecimal getBigDecimal(int columnIndex) {
      return null;
    }

    @Override public BigDecimal getBigDecimal(String columnLabel) {
      return null;
    }

    @Override public boolean isBeforeFirst() {
      return false;
    }

    @Override public boolean isAfterLast() {
      return false;
    }

    @Override public boolean isFirst() {
      return false;
    }

    @Override public boolean isLast() {
      return false;
    }

    @Override public void beforeFirst() {
    }

    @Override public void afterLast() {
    }

    @Override public boolean first() {
      return false;
    }

    @Override public boolean last() {
      return false;
    }

    @Override public int getRow() {
      return 0;
    }

    @Override public boolean absolute(int row) {
      return false;
    }

    @Override public boolean relative(int rows) {
      return false;
    }

    @Override public boolean previous() {
      return false;
    }

    @Override public void setFetchDirection(int direction) {
    }

    @Override public int getFetchDirection() {
      return FETCH_FORWARD;
    }

    @Override public void setFetchSize(int rows) {
    }

    @Override public int getFetchSize() {
      return 0;
    }

    @Override public int getType() {
      return TYPE_FORWARD_ONLY;
    }

    @Override public int getConcurrency() {
      return CONCUR_READ_ONLY;
    }

    @Override public boolean rowUpdated() {
      return false;
    }

    @Override public boolean rowInserted() {
      return false;
    }

    @Override public boolean rowDeleted() {
      return false;
    }

    @Override public void updateNull(int columnIndex) {
    }

    @Override public void updateBoolean(int columnIndex, boolean x) {
    }

    @Override public void updateByte(int columnIndex, byte x) {
    }

    @Override public void updateShort(int columnIndex, short x) {
    }

    @Override public void updateInt(int columnIndex, int x) {
    }

    @Override public void updateLong(int columnIndex, long x) {
    }

    @Override public void updateFloat(int columnIndex, float x) {
    }

    @Override public void updateDouble(int columnIndex, double x) {
    }

    @Override public void updateBigDecimal(int columnIndex, BigDecimal x) {
    }

    @Override public void updateString(int columnIndex, String x) {
    }

    @Override public void updateBytes(int columnIndex, byte[] x) {
    }

    @Override public void updateDate(int columnIndex, Date x) {
    }

    @Override public void updateTime(int columnIndex, Time x) {
    }

    @Override public void updateTimestamp(int columnIndex, Timestamp x) {
    }

    @Override public void updateAsciiStream(int columnIndex, InputStream x,
        int length) {
    }

    @Override public void updateBinaryStream(int columnIndex, InputStream x,
        int length) {
    }

    @Override public void updateCharacterStream(int columnIndex, Reader x,
        int length) {
    }

    @Override public void updateObject(int columnIndex, Object x,
        int scaleOrLength) {
    }

    @Override public void updateObject(int columnIndex, Object x) {
    }

    @Override public void updateNull(String columnLabel) {
    }

    @Override public void updateBoolean(String columnLabel, boolean x) {
    }

    @Override public void updateByte(String columnLabel, byte x) {
    }

    @Override public void updateShort(String columnLabel, short x) {
    }

    @Override public void updateInt(String columnLabel, int x) {
    }

    @Override public void updateLong(String columnLabel, long x) {
    }

    @Override public void updateFloat(String columnLabel, float x) {
    }

    @Override public void updateDouble(String columnLabel, double x) {
    }

    @Override public void updateBigDecimal(String columnLabel, BigDecimal x) {
    }

    @Override public void updateString(String columnLabel, String x) {
    }

    @Override public void updateBytes(String columnLabel, byte[] x) {
    }

    @Override public void updateDate(String columnLabel, Date x) {
    }

    @Override public void updateTime(String columnLabel, Time x) {
    }

    @Override public void updateTimestamp(String columnLabel, Timestamp x) {
    }

    @Override public void updateAsciiStream(String columnLabel, InputStream x,
        int length) {
    }

    @Override public void updateBinaryStream(String columnLabel, InputStream x,
        int length) {
    }

    @Override public void updateCharacterStream(String columnLabel,
        Reader reader, int length) {
    }

    @Override public void updateObject(String columnLabel, Object x,
        int scaleOrLength) {
    }

    @Override public void updateObject(String columnLabel, Object x) {
    }

    @Override public void insertRow() {
    }

    @Override public void updateRow() {
    }

    @Override public void deleteRow() {
    }

    @Override public void refreshRow() {
    }

    @Override public void cancelRowUpdates() {
    }

    @Override public void moveToInsertRow() {
    }

    @Override public void moveToCurrentRow() {
    }

    @Override public Statement getStatement() {
      return null;
    }

    @Override public Object getObject(int columnIndex,
        Map<String, Class<?>> map) {
      return null;
    }

    @Override public Ref getRef(int columnIndex) {
      return null;
    }

    @Override public Blob getBlob(int columnIndex) {
      return null;
    }

    @Override public Clob getClob(int columnIndex) {
      return null;
    }

    @Override public Array getArray(int columnIndex) {
      return null;
    }

    @Override public Object getObject(String columnLabel,
        Map<String, Class<?>> map) {
      return null;
    }

    @Override public Ref getRef(String columnLabel) {
      return null;
    }

    @Override public Blob getBlob(String columnLabel) {
      return null;
    }

    @Override public Clob getClob(String columnLabel) {
      return null;
    }

    @Override public Array getArray(String columnLabel) {
      return null;
    }

    @Override public Date getDate(int columnIndex, Calendar cal) {
      return null;
    }

    @Override public Date getDate(String columnLabel, Calendar cal) {
      return null;
    }

    @Override public Time getTime(int columnIndex, Calendar cal) {
      return null;
    }

    @Override public Time getTime(String columnLabel, Calendar cal) {
      return null;
    }

    @Override public Timestamp getTimestamp(int columnIndex, Calendar cal) {
      return null;
    }

    @Override public Timestamp getTimestamp(String columnLabel, Calendar cal) {
      return null;
    }

    @Override public URL getURL(int columnIndex) {
      return null;
    }

    @Override public URL getURL(String columnLabel) {
      return null;
    }

    @Override public void updateRef(int columnIndex, Ref x) {
    }

    @Override public void updateRef(String columnLabel, Ref x) {
    }

    @Override public void updateBlob(int columnIndex, Blob x) {
    }

    @Override public void updateBlob(String columnLabel, Blob x) {
    }

    @Override public void updateClob(int columnIndex, Clob x) {
    }

    @Override public void updateClob(String columnLabel, Clob x) {
    }

    @Override public void updateArray(int columnIndex, Array x) {
    }

    @Override public void updateArray(String columnLabel, Array x) {
    }

    @Override public RowId getRowId(int columnIndex) {
      return null;
    }

    @Override public RowId getRowId(String columnLabel) {
      return null;
    }

    @Override public void updateRowId(int columnIndex, RowId x) {
    }

    @Override public void updateRowId(String columnLabel, RowId x) {
    }

    @Override public int getHoldability() {
      return CLOSE_CURSORS_AT_COMMIT;
    }

    @Override public boolean isClosed() {
      return false;
    }

    @Override public void updateNString(int columnIndex, String nString) {
    }

    @Override public void updateNString(String columnLabel, String nString) {
    }

    @Override public void updateNClob(int columnIndex, NClob nClob) {
    }

    @Override public void updateNClob(String columnLabel, NClob nClob) {
    }

    @Override public NClob getNClob(int columnIndex) {
      return null;
    }

    @Override public NClob getNClob(String columnLabel) {
      return null;
    }

    @Override public SQLXML getSQLXML(int columnIndex) {
      return null;
    }

    @Override public SQLXML getSQLXML(String columnLabel) {
      return null;
    }

    @Override public void updateSQLXML(int columnIndex, SQLXML xmlObject) {
    }

    @Override public void updateSQLXML(String columnLabel, SQLXML xmlObject) {
    }

    @Override public String getNString(int columnIndex) {
      return "";
    }

    @Override public String getNString(String columnLabel) {
      return "";
    }

    @Override public Reader getNCharacterStream(int columnIndex) {
      return null;
    }

    @Override public Reader getNCharacterStream(String columnLabel) {
      return null;
    }

    @Override public void updateNCharacterStream(int columnIndex, Reader x,
        long length) {
    }

    @Override public void updateNCharacterStream(String columnLabel,
        Reader reader, long length) {
    }

    @Override public void updateAsciiStream(int columnIndex, InputStream x,
        long length) {
    }

    @Override public void updateBinaryStream(int columnIndex, InputStream x,
        long length) {
    }

    @Override public void updateCharacterStream(int columnIndex, Reader x,
        long length) {
    }

    @Override public void updateAsciiStream(String columnLabel, InputStream x,
        long length) {
    }

    @Override public void updateBinaryStream(String columnLabel, InputStream x,
        long length) {
    }

    @Override public void updateCharacterStream(String columnLabel,
        Reader reader, long length) {
    }

    @Override public void updateBlob(int columnIndex, InputStream inputStream,
        long length) {
    }

    @Override public void updateBlob(String columnLabel,
        InputStream inputStream, long length) {
    }

    @Override public void updateClob(int columnIndex, Reader reader,
        long length) {
    }

    @Override public void updateClob(String columnLabel, Reader reader,
        long length) {
    }

    @Override public void updateNClob(int columnIndex, Reader reader,
        long length) {
    }

    @Override public void updateNClob(String columnLabel, Reader reader,
        long length) {
    }

    @Override public void updateNCharacterStream(int columnIndex, Reader x) {
    }

    @Override public void updateNCharacterStream(String columnLabel,
        Reader reader) {
    }

    @Override public void updateAsciiStream(int columnIndex, InputStream x) {
    }

    @Override public void updateBinaryStream(int columnIndex, InputStream x) {
    }

    @Override public void updateCharacterStream(int columnIndex, Reader x) {
    }

    @Override public void updateAsciiStream(String columnLabel, InputStream x) {
    }

    @Override public void updateBinaryStream(String columnLabel,
        InputStream x) {
    }

    @Override public void updateCharacterStream(String columnLabel,
        Reader reader) {
    }

    @Override public void updateBlob(int columnIndex, InputStream inputStream) {
    }

    @Override public void updateBlob(String columnLabel,
        InputStream inputStream) {
    }

    @Override public void updateClob(int columnIndex, Reader reader) {
    }

    @Override public void updateClob(String columnLabel, Reader reader) {
    }

    @Override public void updateNClob(int columnIndex, Reader reader) {
    }

    @Override public void updateNClob(String columnLabel, Reader reader) {
    }

    @Override public <T> T getObject(int columnIndex, Class<T> type) {
      return null;
    }

    @Override public <T> T getObject(String columnLabel, Class<T> type) {
      return null;
    }

    @Override public <T> T unwrap(Class<T> iface) {
      return null;
    }

    @Override public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }

  /** Implementation of {@link java.sql.ResultSetMetaData} that implements every
   * method but mostly does nothing. Use it as a base class. */
  private static class NullResultSetMetaData implements ResultSetMetaData {
    @Override public int getColumnCount() {
      return 0;
    }

    @Override public boolean isAutoIncrement(int column) {
      return false;
    }

    @Override public boolean isCaseSensitive(int column) {
      return false;
    }

    @Override public boolean isSearchable(int column) {
      return false;
    }

    @Override public boolean isCurrency(int column) {
      return false;
    }

    @Override public int isNullable(int column) {
      return columnNullableUnknown;
    }

    @Override public boolean isSigned(int column) {
      return false;
    }

    @Override public int getColumnDisplaySize(int column) {
      return 0;
    }

    @Override public String getColumnLabel(int column) {
      return "";
    }

    @Override public String getColumnName(int column) {
      return "";
    }

    @Override public String getSchemaName(int column) {
      return "";
    }

    @Override public int getPrecision(int column) {
      return 0;
    }

    @Override public int getScale(int column) {
      return 0;
    }

    @Override public String getTableName(int column) {
      return "";
    }

    @Override public String getCatalogName(int column) {
      return "";
    }

    @Override public int getColumnType(int column) {
      return 0;
    }

    @Override public String getColumnTypeName(int column) {
      return "";
    }

    @Override public boolean isReadOnly(int column) {
      return false;
    }

    @Override public boolean isWritable(int column) {
      return false;
    }

    @Override public boolean isDefinitelyWritable(int column) {
      return false;
    }

    @Override public String getColumnClassName(int column) {
      return "";
    }

    @Override public <T> T unwrap(Class<T> iface) {
      return null;
    }

    @Override public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}

// End JdbcUtils.java
