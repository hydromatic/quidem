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
package net.hydromatic.quidem;

import net.hydromatic.quidem.record.Config;
import net.hydromatic.quidem.record.JdbcUtils;
import net.hydromatic.quidem.record.Mode;
import net.hydromatic.quidem.record.Recorder;
import net.hydromatic.quidem.record.Recorders;
import net.hydromatic.scott.data.hsqldb.ScottHsqldb;
import net.hydromatic.steelwheels.data.hsqldb.SteelwheelsHsqldb;

import com.google.common.base.Suppliers;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static net.hydromatic.quidem.TestUtils.hasContents;
import static net.hydromatic.quidem.TestUtils.isLines;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.junit.jupiter.api.Assertions.fail;

import static java.util.Arrays.fill;

/** Tests the recorder. */
public class RecordTest {

  static final Supplier<TestUtils.FileFont> TEMP_SUPPLIER =
      Suppliers.memoize(() -> new TestUtils.FileFont("quidem-record-test"));

  static Quidem.ConnectionFactory getScottHsqldb() {
    return ConnectionFactories.simple("scott", ScottHsqldb.URI,
        ScottHsqldb.USER, ScottHsqldb.PASSWORD);
  }

  static Quidem.ConnectionFactory getSteelwheelsHsqldb() {
    return ConnectionFactories.simple("steelwheels", SteelwheelsHsqldb.URI,
        SteelwheelsHsqldb.USER, SteelwheelsHsqldb.PASSWORD);
  }

  /** Records a file containing one query. */
  @Test void testRecord() {
    final File file = TEMP_SUPPLIER.get().file("testRecord", ".iq");
    checkRecord(file, Mode.RECORD, getScottHsqldb());

    final String[] lines = {
        "# StartTest: empCount",
        "!use scott",
        "select count(*) from emp;",
        "C1:BIGINT",
        "14",
        "!ok",
        "# EndTest: empCount"
    };
    assertThat(file, hasContents(isLines(lines)));
  }

  @Test void testRecordSeveral() {
    final File file = TEMP_SUPPLIER.get().file("testRecordSeveral", ".iq");
    final Quidem.ConnectionFactory connectionFactory =
        ConnectionFactories.chain(getScottHsqldb(), getSteelwheelsHsqldb());
    final String[] lines = {
        "# StartTest: empCount",
        "!use scott",
        "select count(*) from emp;",
        "C1:BIGINT",
        "14",
        "!ok",
        "# EndTest: empCount",
        "# StartTest: managers",
        "!use scott",
        "select *",
        "from emp",
        "where job in ('MANAGER', 'PRESIDENT')",
        "order by empno;",
        "EMPNO:SMALLINT,ENAME:VARCHAR,JOB:VARCHAR,MGR:SMALLINT,HIREDATE:DATE,SAL:DECIMAL,COMM:DECIMAL,DEPTNO:TINYINT",
        "7566,JONES,MANAGER,7839,1981-02-04,2975.00,,20",
        "7698,BLAKE,MANAGER,7839,1981-01-05,2850.00,,30",
        "7782,CLARK,MANAGER,7839,1981-06-09,2450.00,,10",
        "7839,KING,PRESIDENT,,1981-11-17,5000.00,,10",
        "!ok",
        "# EndTest: managers",
        "# StartTest: productCount",
        "!use steelwheels",
        "select count(*) as c",
        "from \"products\";",
        "C:BIGINT",
        "110",
        "!ok",
        "# EndTest: productCount"
    };
    checkRecordSeveral(file, Mode.PASS_THROUGH, connectionFactory);
    assertThat(file.exists(), is(false));
    checkRecordSeveral(file, Mode.RECORD, connectionFactory);
    assertThat(file.exists(), is(true));
    assertThat(file, hasContents(isLines(lines)));
    checkRecordSeveral(file, Mode.PLAY, connectionFactory);
  }

  private static void checkRecord(File file, Mode mode,
      Quidem.ConnectionFactory connectionFactory) {
    Config config = Recorders.config()
        .withFile(file)
        .withMode(mode)
        .withConnectionFactory(connectionFactory);
    try (Recorder recorder = Recorders.create(config)) {
      recorder.executeQuery("scott", "empCount", "select count(*) from emp",
          isInt(14));
    }
  }

  private static void checkRecordSeveral(File file, Mode mode,
      Quidem.ConnectionFactory connectionFactory) {
    Config config = Recorders.config()
        .withFile(file)
        .withMode(mode)
        .withConnectionFactory(connectionFactory);
    try (Recorder recorder = Recorders.create(config)) {
      recorder.executeQuery("scott", "empCount", "select count(*) from emp",
          isInt(14));
      recorder.executeQuery("scott", "managers",
          "select *\n"
              + "from emp\n"
              + "where job in ('MANAGER', 'PRESIDENT')\n"
              + "order by empno",
          result -> {
            try {
              final ResultSetMetaData metaData = result.getMetaData();
              assertThat(metaData.getColumnCount(), is(8));
              assertThat(metaData.getColumnName(1), is("EMPNO"));
              assertThat(metaData.getColumnTypeName(1), is("SMALLINT"));
              assertThat(metaData.getColumnType(1), is(Types.SMALLINT));
              assertThat(metaData.getColumnName(2), is("ENAME"));
              assertThat(metaData.getColumnTypeName(2), is("VARCHAR"));
              assertThat(metaData.getColumnType(2), is(Types.VARCHAR));
              assertThat(metaData.getColumnName(3), is("JOB"));
              assertThat(metaData.getColumnTypeName(3), is("VARCHAR"));
              assertThat(metaData.getColumnType(3), is(Types.VARCHAR));
              assertThat(metaData.getColumnName(4), is("MGR"));
              assertThat(metaData.getColumnTypeName(4), is("SMALLINT"));
              assertThat(metaData.getColumnType(4), is(Types.SMALLINT));
              assertThat(metaData.getColumnName(5), is("HIREDATE"));
              assertThat(metaData.getColumnTypeName(5), is("DATE"));
              assertThat(metaData.getColumnType(5), is(Types.DATE));
              assertThat(metaData.getColumnName(6), is("SAL"));
              assertThat(metaData.getColumnTypeName(6), is("DECIMAL"));
              assertThat(metaData.getColumnType(6), is(Types.DECIMAL));
              assertThat(metaData.getColumnName(7), is("COMM"));
              assertThat(metaData.getColumnTypeName(7), is("DECIMAL"));
              assertThat(metaData.getColumnType(7), is(Types.DECIMAL));
              assertThat(metaData.getColumnName(8), is("DEPTNO"));
              assertThat(metaData.getColumnTypeName(8), is("TINYINT"));
              assertThat(metaData.getColumnType(8), is(Types.TINYINT));
              assertThat(result.next(), is(true));
              assertThat(result.getInt(1), is(7566)); // EMPNO
              assertThat(result.getString(2), is("JONES")); // ENAME
              assertThat(result.getDate(5),
                  hasToString("1981-02-04")); // HIREDATE
              assertThat(result.getBigDecimal(6),
                  hasToString("2975.00")); // SAL
              assertThat(result.getBigDecimal(7), nullValue()); // COMM
            } catch (SQLException e) {
              fail(e);
            }
          });
      recorder.executeQuery("steelwheels", "productCount",
          "select count(*) as c\n"
              + "from \"products\"",
          isInt(110));
    }
  }

  /** Tests what happens if there is no file. */
  @Test void testNoFile() {
    for (Mode mode : Mode.values()) {
      switch (mode) {
      case PLAY:
      case RECORD:
        // PLAY and RECORD require a file
        try (Recorder recorder =
                 Recorders.create(Recorders.config().withMode(mode))) {
          fail("expected error, got " + recorder);
        } catch (IllegalStateException e) {
          assertThat(e.getMessage(),
              is("mode '" + mode + "' requires a file"));
        }
        break;

      case PASS_THROUGH:
        // PASS_THROUGH does not require a file
        try (Recorder recorder =
                 Recorders.create(Recorders.config().withMode(mode))) {
          assertThat(recorder, notNullValue());
        }
        break;

      default:
        throw new AssertionError(mode);
      }
    }
  }

  /** Creates a recording with two queries, tries to execute a query that is
   * missing. */
  @Test void testPlaySeveral() {
    final File file = TEMP_SUPPLIER.get().file("testPlaySeveral", ".iq");
    final String[] lines = {
        "# StartTest: one-scott",
        "!use scott",
        "select 1;",
        "C1:BIGINT",
        "1",
        "!ok",
        "# EndTest: one-scott",
        "# StartTest: one-steelwheels",
        "!use steelwheels",
        "select 1;",
        "C1:BIGINT",
        "100",
        "!ok",
        "# EndTest: one-steelwheels",
        "# StartTest: three",
        "!use scott",
        "select 3;",
        "C1:BIGINT",
        "3",
        "!ok",
        "# EndTest: three"
    };
    try (FileWriter w = new FileWriter(file);
         PrintWriter pw = new PrintWriter(w)) {
      for (String line : lines) {
        pw.println(line);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    final Quidem.ConnectionFactory connectionFactory =
        ConnectionFactories.chain(getScottHsqldb(), getSteelwheelsHsqldb());
    Config config = Recorders.config()
        .withFile(file)
        .withMode(Mode.PLAY)
        .withConnectionFactory(connectionFactory);
    try (Recorder recorder = Recorders.create(config)) {
      recorder.executeQuery("scott", "one", "select 1", isInt(1));
      recorder.executeQuery("scott", "three", "select 3", isInt(3));
      recorder.executeQuery("steelwheels", "one", "select 1", isInt(100));
      try {
        recorder.executeQuery("scott", "one", "select 2",
            resultSet -> fail("should not be called"));
        fail("should not reach this point");
      } catch (IllegalArgumentException e) {
        assertThat(e.getMessage(), is("sql [select 2] is not in recording"));
      }
    }
  }

  /** Returns a consumer that checks that a {@link java.sql.ResultSet} has
   * one integer column, one row, and the value in that row is {@code value}. */
  private static Consumer<ResultSet> isInt(int value) {
    return result -> {
      try {
        assertThat(result.getMetaData().getColumnCount(), is(1));
        assertThat(result.next(), is(true));
        assertThat(result.getInt(1), is(value));
        assertThat(result.next(), is(false));
      } catch (SQLException e) {
        fail(e);
      }
    };
  }

  /** Tests a query where one of the columns contains null, empty, and non-empty
   * strings. It is challenging to find an encoding that can distinguish between
   * null and empty string values. */
  @Test void testNullAndEmptyString() {
    final Quidem.ConnectionFactory connectionFactory =
        ConnectionFactories.chain(getScottHsqldb(), getSteelwheelsHsqldb());
    final String sql = "with t as\n"
        + "  (select ename, substr(ename, 1, mod(mgr, 5) - 1) as e\n"
        + "    from emp)\n"
        + "select ename, e, e is null as n, char_length(e) as len\n"
        + "from t";
    final String[] strings =
        recordAndPlay("testNullAndEmptyString", connectionFactory, "scott",
            sql);
    assertThat(strings[0], is(strings[1]));
  }

  /** Runs a query twice - once in RECORD mode, once in PLAY mode - and returns
   * the results from both. */
  private static String[] recordAndPlay(String testName,
      Quidem.ConnectionFactory connectionFactory, String db, String sql) {
    final File file = TEMP_SUPPLIER.get().file(testName, ".iq");
    Config config = Recorders.config()
        .withFile(file)
        .withConnectionFactory(connectionFactory);
    final StringBuilder b = new StringBuilder();
    try (Recorder recorder = Recorders.create(config.withMode(Mode.RECORD))) {
      recorder.executeQuery(db, "a query", sql, resultSet -> {
        try {
          JdbcUtils.write(b, resultSet);
        } catch (SQLException e) {
          fail(
              String.format("error while serializing result set: sql [%s]",
                  sql), e);
        }
      });
    }
    final StringBuilder b2 = new StringBuilder();
    try (Recorder recorder = Recorders.create(config.withMode(Mode.PLAY))) {
      recorder.executeQuery(db, "a query", sql, resultSet -> {
        try {
          JdbcUtils.write(b2, resultSet);
        } catch (SQLException e) {
          fail(
              String.format("error while serializing result set: sql [%s]",
                  sql), e);
        }
      });
    }
    return new String[]{b.toString(), b2.toString()};
  }

  /** Tests {@link JdbcUtils#parse(java.lang.String[], java.lang.String)},
   * which parses a comma-separated line. */
  @Test void testParse() {
    final String[] fields = new String[3];

    // Simple case
    fill(fields, "xxx");
    JdbcUtils.parse(fields, "wx,y,z");
    assertThat(fields[0], is("wx"));
    assertThat(fields[1], is("y"));
    assertThat(fields[2], is("z"));

    // Empty at end
    fill(fields, "xxx");
    JdbcUtils.parse(fields, "x,y,");
    assertThat(fields[0], is("x"));
    assertThat(fields[1], is("y"));
    assertThat(fields[2], nullValue());

    // Too few commas
    fill(fields, "xxx");
    JdbcUtils.parse(fields, "x,y");
    assertThat(fields[0], is("x"));
    assertThat(fields[1], is("y"));
    assertThat(fields[2], nullValue());

    // Too few commas
    fill(fields, "xxx");
    JdbcUtils.parse(fields, "x");
    assertThat(fields[0], is("x"));
    assertThat(fields[1], nullValue());
    assertThat(fields[2], nullValue());

    // Empty field in middle
    fill(fields, "xxx");
    JdbcUtils.parse(fields, "x,,z");
    assertThat(fields[0], is("x"));
    assertThat(fields[1], nullValue());
    assertThat(fields[2], is("z"));

    // Empty field at start
    fill(fields, "xxx");
    JdbcUtils.parse(fields, ",y,z");
    assertThat(fields[0], nullValue());
    assertThat(fields[1], is("y"));
    assertThat(fields[2], is("z"));

    // Empty field at start and middle
    fill(fields, "xxx");
    JdbcUtils.parse(fields, ",,z");
    assertThat(fields[0], nullValue());
    assertThat(fields[1], nullValue());
    assertThat(fields[2], is("z"));

    // Empty line
    fill(fields, "xxx");
    JdbcUtils.parse(fields, "");
    assertThat(fields[0], nullValue());
    assertThat(fields[1], nullValue());
    assertThat(fields[2], nullValue());

    // Quoted first field
    fill(fields, "xxx");
    JdbcUtils.parse(fields, "'x,a',y,z");
    assertThat(fields[0], is("x,a"));
    assertThat(fields[1], is("y"));
    assertThat(fields[2], is("z"));

    // Single-quote in quoted field
    fill(fields, "xxx");
    JdbcUtils.parse(fields, "'x''a','y''b','z''c'");
    assertThat(fields[0], is("x'a"));
    assertThat(fields[1], is("y'b"));
    assertThat(fields[2], is("z'c"));

    // Single-quote at end of quoted field
    fill(fields, "xxx");
    JdbcUtils.parse(fields, "'''x''a''','''y''b''','''z''c'''");
    assertThat(fields[0], is("'x'a'"));
    assertThat(fields[1], is("'y'b'"));
    assertThat(fields[2], is("'z'c'"));

    // Quoted middle field
    fill(fields, "xxx");
    JdbcUtils.parse(fields, "x,'y,a',z");
    assertThat(fields[0], is("x"));
    assertThat(fields[1], is("y,a"));
    assertThat(fields[2], is("z"));

    // Quoted last field
    fill(fields, "xxx");
    JdbcUtils.parse(fields, "x,y,'z,a'");
    assertThat(fields[0], is("x"));
    assertThat(fields[1], is("y"));
    assertThat(fields[2], is("z,a"));

    // Quoted last field, too few fields
    fill(fields, "xxx");
    JdbcUtils.parse(fields, "x,'y,a'");
    assertThat(fields[0], is("x"));
    assertThat(fields[1], is("y,a"));
    assertThat(fields[2], nullValue());

    // Line ends following an escaped single-quote
    fill(fields, "xxx");
    try {
      JdbcUtils.parse(fields, "x,y,'z''");
      fail("expected error");
    } catch (IllegalArgumentException e) {
      assertThat(e.getMessage(), is("missing \"'\" following escaped \"'\""));
    }

    // Single-quote is not end of field
    fill(fields, "xxx");
    try {
      JdbcUtils.parse(fields, "x,'y,a'b,z");
      fail("expected error");
    } catch (IllegalArgumentException e) {
      assertThat(e.getMessage(),
          is("quoted string must be followed by comma or line ending"));
    }

    // Single field
    final String[] fields1 = new String[1];
    fill(fields1, "xxx");
    JdbcUtils.parse(fields1, "123");
    assertThat(fields1[0], is("123"));
  }
}

// End RecordTest.java
