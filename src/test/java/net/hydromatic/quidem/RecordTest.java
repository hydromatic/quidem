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

import net.hydromatic.foodmart.data.hsqldb.FoodmartHsqldb;
import net.hydromatic.quidem.record.Config;
import net.hydromatic.quidem.record.JdbcUtils;
import net.hydromatic.quidem.record.Mode;
import net.hydromatic.quidem.record.Recorder;
import net.hydromatic.quidem.record.Recorders;
import net.hydromatic.quidem.util.TestUtils.FileFont;
import net.hydromatic.scott.data.hsqldb.ScottHsqldb;
import net.hydromatic.steelwheels.data.hsqldb.SteelwheelsHsqldb;

import com.google.common.base.Suppliers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static net.hydromatic.quidem.util.TestUtils.hasContents;
import static net.hydromatic.quidem.util.TestUtils.isLines;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import static java.lang.String.format;
import static java.util.Arrays.fill;

/** Tests the recorder. */
public class RecordTest {

  /** A supplier of {@link FileFont} objects (each of which is a supplier of
   * temporary files). */
  @SuppressWarnings("FunctionalExpressionCanBeFolded")
  static final Supplier<FileFont> TEMP_SUPPLIER =
      Suppliers.memoize(() -> new FileFont("quidem-record-test"))::get;

  /** Whether Postgres is enabled.
   *
   * <p>In committed code, this should be false. Set it to true in a local
   * environment where you have a Postgres database available, and additional
   * tests will be executed.
   *
   * <p>The following steps can be used to set up Postgres on Ubuntu:
   * <blockquote><pre>
   *   $ sudo apt install postgresql
   *   $ sudo -u postgres psql template1
   *   # alter user postgres password 'postgres';
   * </pre></blockquote>
   */
  private static final boolean POSTGRES_ENABLED = false;
  private static final String POSTGRES_URI = "jdbc:postgresql:template1";
  private static final String POSTGRES_USER = "postgres";
  private static final String POSTGRES_PASSWORD = "postgres";

  private static final PrintStream OUT = System.out;

  static Quidem.ConnectionFactory getFoodmartHsqldb() {
    return ConnectionFactories.simple("foodmart", FoodmartHsqldb.URI,
        FoodmartHsqldb.USER, FoodmartHsqldb.PASSWORD);
  }

  static Quidem.ConnectionFactory getScottHsqldb() {
    return ConnectionFactories.simple("scott", ScottHsqldb.URI,
        ScottHsqldb.USER, ScottHsqldb.PASSWORD);
  }

  static Quidem.ConnectionFactory getSteelwheelsHsqldb() {
    return ConnectionFactories.simple("steelwheels", SteelwheelsHsqldb.URI,
        SteelwheelsHsqldb.USER, SteelwheelsHsqldb.PASSWORD);
  }

  static Quidem.ConnectionFactory getFoodmartPostgres() {
    assumeTrue(POSTGRES_ENABLED, "postgres is enabled");
    return ConnectionFactories.simple("foodmart", POSTGRES_URI,
        POSTGRES_USER, POSTGRES_PASSWORD,
        connection -> verify(connection, "product", 1_560),
        connection ->
            populate(getFoodmartHsqldb().supplier("foodmart"), connection,
                "foodmart"));
  }

  static Quidem.ConnectionFactory getScottPostgres() {
    assumeTrue(POSTGRES_ENABLED, "postgres is enabled");
    return ConnectionFactories.simple("scott", POSTGRES_URI,
        POSTGRES_USER, POSTGRES_PASSWORD,
        connection -> verify(connection, "emp", 14),
        connection ->
            populate(getScottHsqldb().supplier("scott"), connection, "SCOTT"));
  }

  static Quidem.ConnectionFactory getSteelwheelsPostgres() {
    assumeTrue(POSTGRES_ENABLED, "postgres is enabled");
    return ConnectionFactories.simple("steelwheels", POSTGRES_URI,
        POSTGRES_USER, POSTGRES_PASSWORD,
        connection -> verify(connection, "customers", 126),
        connection ->
            populate(getSteelwheelsHsqldb().supplier("steelwheels"),
                connection, "steelwheels"));
  }

  /** Verifies a connection by checking that a particular table exists and
   * contains the expected number of rows.
   *
   * <p>For example a "scott" database is probably OK if there is an "emp"
   * table that contains 14 rows. */
  private static boolean verify(Connection connection, String tableName,
      int expectedRowCount) {
    final String sql = format("select count(*) from \"%s\"", tableName);
    try (Statement statement = connection.createStatement();
         ResultSet resultSet = statement.executeQuery(sql)) {
      if (resultSet.next()) {
        final int rowCount = resultSet.getInt(1);
        return rowCount == expectedRowCount;
      }
      return false;
    } catch (SQLException e) {
      OUT.println(e.getMessage());
      return false;
    }
  }

  /** Populates a "scott" database from the HSQLDB source. */
  private static void populateScott0(Connection connection) {
    Quidem.ConnectionFactory connectionFactory = getScottHsqldb();
    String[] tables = {
        "DROP TABLE IF EXISTS dept",
        "DROP TABLE IF EXISTS emp",
        "DROP TABLE IF EXISTS bonus",
        "DROP TABLE IF EXISTS salgrade",
        "CREATE TABLE dept(\"DEPTNO\" TINYINT NOT NULL,\"DNAME\" VARCHAR(14),\"LOC\" VARCHAR(13))",
        "CREATE TABLE emp(\"EMPNO\" SMALLINT NOT NULL,\"ENAME\" VARCHAR(10),job VARCHAR(9),\"MGR\" SMALLINT,\"HIREDATE\" DATE,\"SAL\" DECIMAL(7,2),\"COMM\" DECIMAL(7,2),\"DEPTNO\" TINYINT)",
        "CREATE TABLE bonus(\"ENAME\" VARCHAR(10),\"JOB\" VARCHAR(9),\"SAL\" DECIMAL(7,2),\"COMM\" DECIMAL(7,2))",
        "CREATE TABLE salgrade(\"GRADE\" INTEGER,\"LOSAL\" DECIMAL(7,2),\"HISAL\" DECIMAL(7,2))",
        "INSERT INTO dept VALUES(10,'ACCOUNTING','NEW YORK')",
        "INSERT INTO dept VALUES(20,'RESEARCH','DALLAS')",
        "INSERT INTO dept VALUES(30,'SALES','CHICAGO')",
        "INSERT INTO dept VALUES(40,'OPERATIONS','BOSTON')",
        "INSERT INTO emp VALUES(7369,'SMITH','CLERK',7902,'1980-12-17',800.00,NULL,20)",
        "INSERT INTO emp VALUES(7499,'ALLEN','SALESMAN',7698,'1981-02-20',1600.00,300.00,30)",
        "INSERT INTO emp VALUES(7521,'WARD','SALESMAN',7698,'1981-02-22',1250.00,500.00,30)",
        "INSERT INTO emp VALUES(7566,'JONES','MANAGER',7839,'1981-02-04',2975.00,NULL,20)",
        "INSERT INTO emp VALUES(7654,'MARTIN','SALESMAN',7698,'1981-09-28',1250.00,1400.00,30)",
        "INSERT INTO emp VALUES(7698,'BLAKE','MANAGER',7839,'1981-01-05',2850.00,NULL,30)",
        "INSERT INTO emp VALUES(7782,'CLARK','MANAGER',7839,'1981-06-09',2450.00,NULL,10)",
        "INSERT INTO emp VALUES(7788,'SCOTT','ANALYST',7566,'1987-04-19',3000.00,NULL,20)",
        "INSERT INTO emp VALUES(7839,'KING','PRESIDENT',NULL,'1981-11-17',5000.00,NULL,10)",
        "INSERT INTO emp VALUES(7844,'TURNER','SALESMAN',7698,'1981-09-08',1500.00,0.00,30)",
        "INSERT INTO emp VALUES(7876,'ADAMS','CLERK',7788,'1987-05-23',1100.00,NULL,20)",
        "INSERT INTO emp VALUES(7900,'JAMES','CLERK',7698,'1981-12-03',950.00,NULL,30)",
        "INSERT INTO emp VALUES(7902,'FORD','ANALYST',7566,'1981-12-03',3000.00,NULL,20)",
        "INSERT INTO emp VALUES(7934,'MILLER','CLERK',7782,'1982-01-23',1300.00,NULL,10)",
        "INSERT INTO salgrade VALUES(1,700.00,1200.00)",
        "INSERT INTO salgrade VALUES(2,1201.00,1400.00)",
        "INSERT INTO salgrade VALUES(3,1401.00,2000.00)",
        "INSERT INTO salgrade VALUES(4,2001.00,3000.00)",
        "INSERT INTO salgrade VALUES(5,3001.00,9999.00)",
    };
    try (Connection sourceConnection =
             connectionFactory.connect("scott", false);
         Statement statement = connection.createStatement()) {
      for (String table : tables) {
        table = table.replaceAll("TINYINT", "INT");
        statement.executeUpdate(table);
        System.out.println(table);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** Populates a "scott" database from the HSQLDB source. */
  private static void populate(Supplier<Connection> sourceConnectionSupplier,
      Connection connection, String schema) {
    try (Connection sourceConnection = sourceConnectionSupplier.get();
         Statement statement = connection.createStatement()) {
      final DatabaseMetaData metaData = sourceConnection.getMetaData();
      OUT.println("xxxxx");
      try (ResultSet tables = metaData.getTables(null, schema, null, null)) {
        while (tables.next()) {
          final String tableName = tables.getString("TABLE_NAME");
          OUT.println(tableName);
          OUT.flush();
          final StringBuilder drop = new StringBuilder();
          drop.append(format("DROP TABLE IF EXISTS %s", tableName));
          final StringBuilder create = new StringBuilder();
          create.append(format("CREATE TABLE %s (", tableName));
          final StringBuilder insert = new StringBuilder();
          insert.append(format("INSERT INTO %s VALUES (", tableName));
          final StringBuilder select = new StringBuilder();
          select.append(
              format("SELECT * FROM \"%s\".\"%s\"", schema, tableName));
          final StringBuilder check = new StringBuilder();
          check.append(
              format("SELECT 'Table %s has ' || count(*) || ' rows.' FROM %s",
                  tableName, tableName));
          int columnCount = 0;
          try (ResultSet columns =
                   metaData.getColumns(null, schema, tableName, null)) {
            while (columns.next()) {
              if (columnCount++ > 0) {
                create.append(", ");
                insert.append(", ");
              }
              final String typeName =
                  columns.getString("TYPE_NAME")
                      .replaceAll("TINYINT", "INT")
                      .replaceAll("double", "double precision")
                      .replaceAll("DOUBLE", "DOUBLE PRECISION");
              final String columnName = columns.getString("COLUMN_NAME");
              create.append(columnName).append(' ').append(typeName);
              insert.append('?');
            }
            create.append(')');
            insert.append(')');
          }
          statement.executeUpdate(drop.toString());
          statement.executeUpdate(create.toString());
          try (Statement sourceStatement = sourceConnection.createStatement();
               ResultSet rows = sourceStatement.executeQuery(select.toString());
               PreparedStatement prepared =
                   connection.prepareStatement(insert.toString())) {
            while (rows.next()) {
              for (int c = 0; c < columnCount; c++) {
                prepared.setObject(c + 1, rows.getObject(c + 1));
              }
              prepared.executeUpdate();
            }
          }

          // After each table, commit and print the row count.
//          connection.commit();
          try (ResultSet checkRows = statement.executeQuery(check.toString())) {
            if (checkRows.next()) {
              OUT.println(checkRows.getString(1));
            } else {
              OUT.println("Empty: " + tableName);
            }
          }
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
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
              format("error while serializing result set: sql [%s]",
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
              format("error while serializing result set: sql [%s]",
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

  /** Tests queries running against connections to a PostgreSQL database.
   * The databases are initialized (by copying from HSQLDB) the first time
   * they are used.
   *
   * <p>Because it calls {@link #getFoodmartPostgres()}, the test only runs if
   * Postgres is enabled. */
  @ResourceLock("postgres")
  @Test void testPostgres() {
    checkPostgres(Mode.RECORD);
  }

  /** As {@link #testPostgres()} but in pass-through mode. */
  @ResourceLock("postgres")
  @Test void testPostgresPassThrough() {
    checkPostgres(Mode.PASS_THROUGH);
  }

  /** Tests queries running against connections to a PostgreSQL database.
   * The databases are initialized (by copying from HSQLDB) the first time
   * they are used.
   *
   * <p>Because it calls {@link #getFoodmartPostgres()}, the test only runs if
   * Postgres is enabled. */
  void checkPostgres(Mode mode) {
    final File file = TEMP_SUPPLIER.get().file("testPostgres", ".iq");
    final Quidem.ConnectionFactory connectionFactory =
        ConnectionFactories.chain(getFoodmartPostgres(), getScottPostgres(),
            getSteelwheelsPostgres());
    Config config = Recorders.config()
        .withFile(file)
        .withMode(mode)
        .withConnectionFactory(connectionFactory);
    try (Recorder recorder = Recorders.create(config)) {
      // Executes a query against 'scott' that returns one row, one column.
      recorder.executeQuery("scott", "deptCount", "select count(*) from dept",
          isInt(4));
      // Executes a query that returns several rows, one column.
      recorder.executeQuery("scott", "empJobs", "select distinct job from emp",
          resultSet -> {
            try {
              Set<String> jobs = new TreeSet<>();
              while (resultSet.next()) {
                jobs.add(resultSet.getString(1));
              }
              assertThat(jobs,
                  hasToString("[ANALYST, CLERK, MANAGER, PRESIDENT, "
                      + "SALESMAN]"));
            } catch (SQLException e) {
              throw new RuntimeException(e);
            }
          });
      // Executes a query against 'foodmart'.
      recorder.executeQuery("foodmart", "productCount",
          "select count(*) from \"product\"", isInt(1_560));
      // Executes a query against 'steelwheels'.
      recorder.executeQuery("steelwheels", "customerCount",
          "select count(*) from \"customers\"", isInt(126));
    }
    final String[] lines = {
        "# StartTest: customerCount",
        "!use steelwheels",
        "select count(*) from \"customers\";",
        "count:BIGINT",
        "126",
        "!ok",
        "# EndTest: customerCount",
        "# StartTest: deptCount",
        "!use scott",
        "select count(*) from dept;",
        "count:BIGINT",
        "4",
        "!ok",
        "# EndTest: deptCount",
        "# StartTest: empJobs",
        "!use scott",
        "select distinct job from emp;",
        "job:VARCHAR",
        "CLERK",
        "PRESIDENT",
        "MANAGER",
        "SALESMAN",
        "ANALYST",
        "!ok",
        "# EndTest: empJobs",
        "# StartTest: productCount",
        "!use foodmart",
        "select count(*) from \"product\";",
        "count:BIGINT",
        "1560",
        "!ok",
        "# EndTest: productCount"
    };
    if (mode == Mode.RECORD) {
      assertThat(file, hasContents(isLines(lines)));
    }
  }
}

// End RecordTest.java
