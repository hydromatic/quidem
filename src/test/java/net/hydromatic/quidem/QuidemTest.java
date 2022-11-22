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

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;

import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.StringRegularExpression.matchesRegex;

/**
 * Script-based tests for {@link Quidem}.
 */
public class QuidemTest {
  @Test void testBasic() {
    final String input = "!use scott\n"
        + "select count(*) as c from scott.emp;\n"
        + "!ok\n"
        + "!set outputformat mysql\n"
        + "select count(*) as c from scott.emp;\n"
        + "!ok\n"
        + "!plan\n"
        + "\n";
    final String output = "!use scott\n"
        + "select count(*) as c from scott.emp;\n"
        + "C\n"
        + "14\n"
        + "!ok\n"
        + "!set outputformat mysql\n"
        + "select count(*) as c from scott.emp;\n"
        + "+----+\n"
        + "| C  |\n"
        + "+----+\n"
        + "| 14 |\n"
        + "+----+\n"
        + "(1 row)\n"
        + "\n"
        + "!ok\n"
        + "isDistinctSelect=[false]\n"
        + "isGrouped=[false]\n"
        + "isAggregated=[true]\n"
        + "columns=[\n"
        + "  COUNT  arg=[   OpTypes.ASTERISK \n"
        + " nullable\n"
        + "\n"
        + "]\n"
        + "[range variable 1\n"
        + "  join type=INNER\n"
        + "  table=EMP\n"
        + "  cardinality=14\n"
        + "  access=FULL SCAN\n"
        + "  join condition = [index=SYS_IDX_10095\n"
        + "  ]\n"
        + "  ]]\n"
        + "PARAMETERS=[]\n"
        + "SUBQUERIES[]\n"
        + "!plan\n"
        + "\n";
    final String output2 = output.replaceAll("SYS_IDX_10095", "SYS_IDX_10096");
    assertThatQuidem(input).output(anyOf(is(output), is(output2)));
  }

  @Test void testError() {
    final String input = "!use scott\n"
        + "select blah from blah;\n"
        + "!ok\n"
        + "\n";
    final String output = "!use scott\n"
        + "select blah from blah;\n"
        + "java.sql.SQLSyntaxErrorException: user lacks privilege or object not found: BLAH";
    assertThatQuidem(input).output(containsString(output));
  }

  @Test void testErrorTruncated() {
    final String input = "!use scott\n"
        + "select blah from blah;\n"
        + "!ok\n"
        + "\n";
    final String output = "!use scott\n"
        + "select blah from blah;\n"
        + "java.sql.S (stack truncated)";
    assertThatQuidem(input).limit(10).output(containsString(output));
  }

  @Test void testErrorNotTruncated() {
    final String input = "!use scott\n"
        + "select blah from blah;\n"
        + "!ok\n"
        + "\n";
    final String output = "!use scott\n"
        + "select blah from blah;\n"
        + "java.sql.SQLSyntaxErrorException: user lacks privilege or object not found: BLAH";
    assertThatQuidem(input).limit(1000).output(containsString(output));
  }

  @Test void testExpectError() {
    final String input = "!use scott\n"
        + "select blah from blah;\n"
        + "user lacks privilege or object not found: BLAH\n"
        + "!error\n"
        + "\n";
    String output = "(?s)!use scott\n"
        + "select blah from blah;\n"
        + "user lacks privilege or object not found: BLAH\n"
        + "!error\n"
        + "\n";
    assertThatQuidem(input).output(matchesRegex(output));
  }

  /** The error does not even need to be a full line. */
  @Test void testExpectErrorPartialLine() {
    final String input = "!use scott\n"
        + "select blah from blah;\n"
        + "lacks privilege\n"
        + "!error\n"
        + "\n";
    final String output = "(?s)!use scott\n"
        + "select blah from blah;\n"
        + "lacks privilege\n"
        + "!error\n"
        + "\n";
    assertThatQuidem(input).output(matchesRegex(output));
  }

  @Test void testExpectErrorNoExpected() {
    final String input = "!use scott\n"
        + "select blah from blah;\n"
        + "!error\n"
        + "\n";
    final String output = "(?s)!use scott\n"
        + "select blah from blah;\n"
        + "java.sql.SQLSyntaxErrorException: user lacks privilege or object not found: BLAH\n"
        + "\tat org.hsqldb.jdbc.JDBCUtil.sqlException\\(Unknown Source\\)\n"
        + "\tat org.hsqldb.jdbc.JDBCUtil.sqlException\\(Unknown Source\\)\n"
        + "\tat org.hsqldb.jdbc.JDBCStatement.fetchResult\\(Unknown Source\\)\n"
        + ".*"
        + "!error\n"
        + "\n";
    assertThatQuidem(input).output(matchesRegex(output));
  }

  @Test void testExpectErrorPermissiveTabs() {
    // Quidem matches even though there are differences in tabs, multiple
    // spaces, spaces at the start of lines, and different line endings.
    // Quidem converts line endings to linux.
    final String input = "!use scott\n"
        + "select blah from blah;\n"
        + "java.sql.SQLSyntaxErrorException: user lacks privilege or object not found: BLAH \n"
        + "\tat org.hsqldb.jdbc.JDBCUtil.sqlException(Unknown Source)\n"
        + "  at  org.hsqldb.jdbc.JDBCUtil.sqlException(Unknown Source)\r\n"
        + "at org.hsqldb.jdbc.JDBCStatement.fetchResult(Unknown Source)  \n"
        + "!error\n"
        + "\n";
    final String output = "(?s)!use scott\n"
        + "select blah from blah;\n"
        + "java.sql.SQLSyntaxErrorException: user lacks privilege or object not found: BLAH \n"
        + "\tat org.hsqldb.jdbc.JDBCUtil.sqlException\\(Unknown Source\\)\n"
        + "  at  org.hsqldb.jdbc.JDBCUtil.sqlException\\(Unknown Source\\)\n"
        + "at org.hsqldb.jdbc.JDBCStatement.fetchResult\\(Unknown Source\\)  \n"
        + "!error\n"
        + "\n";
    assertThatQuidem(input).output(matchesRegex(output));
  }

  @Test void testExpectErrorDifferent() {
    final String input = "!use scott\n"
        + "select blah from blah;\n"
        + "user lacks bizz buzz\n"
        + "!error\n"
        + "\n";
    final String output = "(?s)!use scott\n"
        + "select blah from blah;\n"
        + "java.sql.SQLSyntaxErrorException: user lacks privilege or object not found: BLAH\n"
        + "\tat org.hsqldb.jdbc.JDBCUtil.sqlException\\(Unknown Source\\)\n"
        + ".*"
        + " more\n"
        + "!error\n"
        + "\n";
    assertThatQuidem(input).output(matchesRegex(output));
  }

  @Test void testExpectErrorAtRuntime() {
    final String input = "!use baz\n"
        + "select * from scott.emp;\n"
        + "java.sql.SQLException: bang!\n"
        + "!error\n"
        + "\n";
    final String output = "!use baz\n"
        + "select * from scott.emp;\n"
        + "java.sql.SQLException: bang!\n"
        + "!error\n"
        + "\n";
    assertThatQuidem(input)
        .transform(b -> b.withConnectionFactory(new FooFactory()))
        .output(containsString(output));
  }

  @Test void testPlan() {
    final String input = "!use scott\n"
        + "values (1), (2);\n"
        + "!plan\n"
        + "\n";
    final String output = "(?s)!use scott\n"
        + "values \\(1\\), \\(2\\);\n"
        + "isDistinctSelect=.*"
        + "!plan\n"
        + "\n";
    assertThatQuidem(input).output(matchesRegex(output));
  }

  @Test void testPlanAfterOk() {
    final String input = "!use scott\n"
        + "values (1), (2);\n"
        + "!ok\n"
        + "!plan\n"
        + "\n";
    final String output = "(?s)!use scott\n"
        + "values \\(1\\), \\(2\\);\n"
        + "C1\n"
        + "1\n"
        + "2\n"
        + "!ok\n"
        + "isDistinctSelect=.*"
        + "!plan\n"
        + "\n";
    assertThatQuidem(input).output(matchesRegex(output));
  }

  /** It is OK to have consecutive '!plan' calls and no '!ok'.
   * (Previously there was a "result already open" error.) */
  @Test void testPlanPlan() {
    final String input = "!use scott\n"
        + "values (1), (2);\n"
        + "!plan\n"
        + "values (3), (4);\n"
        + "!plan\n"
        + "!ok\n"
        + "\n";
    final String output = "(?s)!use scott\n"
        + "values \\(1\\), \\(2\\);\n"
        + "isDistinctSelect=.*\n"
        + "!plan\n"
        + "values \\(3\\), \\(4\\);\n"
        + "isDistinctSelect=.*\n"
        + "!plan\n"
        + "C1\n"
        + "3\n"
        + "4\n"
        + "!ok\n"
        + "\n";
    assertThatQuidem(input).output(matchesRegex(output));
  }

  /** Content inside a '!ok' command, that needs to be matched. */
  @Test void testOkContent() {
    final String input = "!use scott\n"
        + "values (1), (2);\n"
        + "baz\n"
        + "!ok\n"
        + "\n";
    final String output = "!use scott\n"
        + "values (1), (2);\n"
        + "C1\n"
        + "1\n"
        + "2\n"
        + "!ok\n"
        + "\n";
    assertThatQuidem(input).output(containsString(output));
  }

  /** If the statement contains 'order by', result is not re-ordered to match
   * the input string. */
  @Test void testOkOrderBy() {
    // In (2, 1), out (1, 2). Test gives a diff (correctly).
    final String input = "!use scott\n"
        + "select * from (values (1), (2)) as t(c) order by 1;\n"
        + "C\n"
        + "2\n"
        + "1\n"
        + "!ok\n"
        + "\n";
    final String output = "!use scott\n"
        + "select * from (values (1), (2)) as t(c) order by 1;\n"
        + "C\n"
        + "1\n"
        + "2\n"
        + "!ok\n"
        + "\n";
    assertThatQuidem(input).output(containsString(output));

    // In (1, 2), out (1, 2). Test passes.
    final String input1 = "!use scott\n"
        + "select * from (values (1), (2)) as t(c) order by 1;\n"
        + "C\n"
        + "1\n"
        + "2\n"
        + "!ok\n"
        + "\n";
    final String output1 = "!use scott\n"
        + "select * from (values (1), (2)) as t(c) order by 1;\n"
        + "C\n"
        + "1\n"
        + "2\n"
        + "!ok\n"
        + "\n";
    assertThatQuidem(input1).output(containsString(output1));
  }

  /** As {@link #testOkOrderBy()} but for MySQL. */
  @Test void testOkOrderByMySQL() {
    // In (2, 1), out (1, 2). Test gives a diff (correctly).
    final String input = "!use scott\n"
        + "!set outputformat mysql\n"
        + "select * from (values (1), (2)) as t(c) order by 1;\n"
        + "+---+\n"
        + "| C |\n"
        + "+---+\n"
        + "| 2 |\n"
        + "| 1 |\n"
        + "+---+\n"
        + "(2 rows)\n"
        + "\n"
        + "!ok\n"
        + "\n";
    final String output = "!use scott\n"
        + "!set outputformat mysql\n"
        + "select * from (values (1), (2)) as t(c) order by 1;\n"
        + "+---+\n"
        + "| C |\n"
        + "+---+\n"
        + "| 1 |\n"
        + "| 2 |\n"
        + "+---+\n"
        + "(2 rows)\n"
        + "\n"
        + "!ok\n"
        + "\n";
    assertThatQuidem(input).output(containsString(output));

    // In (1, 2), out (1, 2). Test passes.
    final String input1 = "!use scott\n"
        + "!set outputformat mysql\n"
        + "select * from (values (1), (2)) as t(c) order by 1;\n"
        + "+---+\n"
        + "| C |\n"
        + "+---+\n"
        + "| 1 |\n"
        + "| 2 |\n"
        + "+---+\n"
        + "(2 rows)\n"
        + "\n"
        + "!ok\n"
        + "\n";
    final String output1 = "!use scott\n"
        + "!set outputformat mysql\n"
        + "select * from (values (1), (2)) as t(c) order by 1;\n"
        + "+---+\n"
        + "| C |\n"
        + "+---+\n"
        + "| 1 |\n"
        + "| 2 |\n"
        + "+---+\n"
        + "(2 rows)\n"
        + "\n"
        + "!ok\n"
        + "\n";
    assertThatQuidem(input1).output(containsString(output1));
  }

  /** If the statement does not contain 'order by', result is re-ordered to
   * match the input string. */
  @Test void testOkNoOrderBy() {
    // In (2, 1), out (2, 1). Result would be correct in either order, but
    // we output in the original order, so as not to cause a diff.
    final String input = "!use scott\n"
        + "select * from (values (1), (2)) as t(c);\n"
        + "C\n"
        + "2\n"
        + "1\n"
        + "!ok\n"
        + "\n";
    final String output = "!use scott\n"
        + "select * from (values (1), (2)) as t(c);\n"
        + "C\n"
        + "2\n"
        + "1\n"
        + "!ok\n"
        + "\n";
    assertThatQuidem(input).output(containsString(output));

    // In (1, 2), out (1, 2).
    final String input1 = "!use scott\n"
        + "select * from (values (1), (2)) as t(c);\n"
        + "C\n"
        + "1\n"
        + "2\n"
        + "!ok\n"
        + "\n";
    final String output1 = "!use scott\n"
        + "select * from (values (1), (2)) as t(c);\n"
        + "C\n"
        + "1\n"
        + "2\n"
        + "!ok\n"
        + "\n";
    assertThatQuidem(input1).output(containsString(output1));
  }

  /** Content inside a '!plan' command, that needs to be matched. */
  @Test void testPlanContent() {
    final String input = "!use scott\n"
        + "values (1), (2);\n"
        + "foo\n"
        + "!plan\n"
        + "baz\n"
        + "!ok\n"
        + "\n";
    final String output = "(?s)!use scott\n"
        + "values \\(1\\), \\(2\\);\n"
        + "isDistinctSelect=.*\n"
        + "!plan\n"
        + "C1\n"
        + "1\n"
        + "2\n"
        + "!ok\n"
        + "\n";
    assertThatQuidem(input).output(matchesRegex(output));
  }

  @Test void testIfFalse() {
    final String input = "!use scott\n"
        + "!if (false) {\n"
        + "values (1), (2);\n"
        + "anything\n"
        + "you like\n"
        + "!plan\n"
        + "!}\n"
        + "\n";
    final String output = "!use scott\n"
        + "!if (false) {\n"
        + "values (1), (2);\n"
        + "anything\n"
        + "you like\n"
        + "!plan\n"
        + "!}\n"
        + "\n";
    assertThatQuidem(input).output(containsString(output));
  }

  @Test void testIfTrue() {
    final String input = "!use scott\n"
        + "!if (true) {\n"
        + "values (1), (2);\n"
        + "anything\n"
        + "you like\n"
        + "!ok\n"
        + "!}\n"
        + "\n";
    final String output = "!use scott\n"
        + "!if (true) {\n"
        + "values (1), (2);\n"
        + "C1\n"
        + "1\n"
        + "2\n"
        + "!ok\n"
        + "!}\n"
        + "\n";
    assertThatQuidem(input).output(containsString(output));
  }

  /** Test case for
   * <a href="https://github.com/julianhyde/quidem/issues/8">[QUIDEM-8]
   * Allow variable in 'if'</a>. */
  @Test void testIfVariable() {
    final String input = "!use scott\n"
        + "!if (affirmative) {\n"
        + "values (1), (2);\n"
        + "anything\n"
        + "you like\n"
        + "!ok\n"
        + "!}\n"
        + "!if (negative) {\n"
        + "values (1), (2);\n"
        + "anything\n"
        + "you like\n"
        + "!ok\n"
        + "!}\n"
        + "!if (unset) {\n"
        + "values (1), (2);\n"
        + "anything\n"
        + "you like\n"
        + "!ok\n"
        + "!}\n"
        + "\n";
    final String output = "!use scott\n"
        + "!if (affirmative) {\n"
        + "values (1), (2);\n"
        + "C1\n"
        + "1\n"
        + "2\n"
        + "!ok\n"
        + "!}\n"
        + "!if (negative) {\n"
        + "values (1), (2);\n"
        + "anything\n"
        + "you like\n"
        + "!ok\n"
        + "!}\n"
        + "!if (unset) {\n"
        + "values (1), (2);\n"
        + "anything\n"
        + "you like\n"
        + "!ok\n"
        + "!}\n"
        + "\n";
    assertThatQuidem(input).output(containsString(output));
  }

  /** Test case for
   * <a href="https://github.com/julianhyde/quidem/issues/11">[QUIDEM-11]
   * Nested variables</a>. */
  @Test void testIfVariableNested() {
    final String input = "!use scott\n"
        + "!if (sun.self.self.hot) {\n"
        + "values (1), (2);\n"
        + "anything\n"
        + "you like\n"
        + "!ok\n"
        + "!}\n"
        + "!if (sun.cold) {\n"
        + "values (1), (2);\n"
        + "anything\n"
        + "you like\n"
        + "!ok\n"
        + "!}\n"
        + "!if (sun.unset.foo.baz) {\n"
        + "values (1), (2);\n"
        + "anything\n"
        + "you like\n"
        + "!ok\n"
        + "!}\n"
        + "\n";
    final String output = "!use scott\n"
        + "!if (sun.self.self.hot) {\n"
        + "values (1), (2);\n"
        + "C1\n"
        + "1\n"
        + "2\n"
        + "!ok\n"
        + "!}\n"
        + "!if (sun.cold) {\n"
        + "values (1), (2);\n"
        + "anything\n"
        + "you like\n"
        + "!ok\n"
        + "!}\n"
        + "!if (sun.unset.foo.baz) {\n"
        + "values (1), (2);\n"
        + "anything\n"
        + "you like\n"
        + "!ok\n"
        + "!}\n"
        + "\n";
    assertThatQuidem(input).output(containsString(output));
  }

  @Test void testSkip() {
    final String input = "!use scott\n"
        + "!skip\n"
        + "values (1);\n"
        + "anything\n"
        + "!ok\n"
        + "values (1);\n"
        + "you like\n"
        + "!error\n"
        + "\n";
    final String output = "!use scott\n"
        + "!skip\n"
        + "values (1);\n"
        + "anything\n"
        + "!ok\n"
        + "values (1);\n"
        + "you like\n"
        + "!error\n"
        + "\n";
    assertThatQuidem(input).output(containsString(output));
  }

  @Test void testSqlIfFalsePlan() {
    final String input = "!use scott\n"
        + "values 1;\n"
        + "!if (false) {\n"
        + "anything\n"
        + "you like\n"
        + "!ok\n"
        + "!}\n"
        + "something\n"
        + "!type\n"
        + "\n";
    final String output = "!use scott\n"
        + "values 1;\n"
        + "!if (false) {\n"
        + "anything\n"
        + "you like\n"
        + "!ok\n"
        + "!}\n"
        + "C1 INTEGER(32)\n"
        + "!type\n"
        + "\n";
    assertThatQuidem(input).output(containsString(output));
  }

  @Test void testJustify() {
    final String input = "!use scott\n"
        + "select true as b00000,\n"
        + "  cast(1 as tinyint) as t000,\n"
        + "  cast(1 as integer) as i000,\n"
        + "  cast(1 as float) as f00000,\n"
        + "  cast(1 as double) as d00000,\n"
        + "  cast(1 as varchar(3)) as v003\n"
        + "from (values (1));\n"
        + "!set outputformat mysql\n"
        + "!ok\n"
        + "!set outputformat psql\n"
        + "!ok\n"
        + "!set outputformat oracle\n"
        + "!ok\n"
        + "!set outputformat csv\n"
        + "!ok\n"
        + "\n";
    final String output = "!use scott\n"
        + "select true as b00000,\n"
        + "  cast(1 as tinyint) as t000,\n"
        + "  cast(1 as integer) as i000,\n"
        + "  cast(1 as float) as f00000,\n"
        + "  cast(1 as double) as d00000,\n"
        + "  cast(1 as varchar(3)) as v003\n"
        + "from (values (1));\n"
        + "!set outputformat mysql\n"
        + "+--------+------+------+--------+--------+------+\n"
        + "| B00000 | T000 | I000 | F00000 | D00000 | V003 |\n"
        + "+--------+------+------+--------+--------+------+\n"
        + "| TRUE   |    1 |    1 |  1.0E0 |  1.0E0 | 1    |\n"
        + "+--------+------+------+--------+--------+------+\n"
        + "(1 row)\n"
        + "\n"
        + "!ok\n"
        + "!set outputformat psql\n"
        + " B00000 | T000 | I000 | F00000 | D00000 | V003\n"
        + "--------+------+------+--------+--------+------\n"
        + " TRUE   |    1 |    1 |  1.0E0 |  1.0E0 | 1\n"
        + "(1 row)\n"
        + "\n"
        + "!ok\n"
        + "!set outputformat oracle\n"
        + "B00000 T000 I000 F00000 D00000 V003\n"
        + "------ ---- ---- ------ ------ ----\n"
        + "TRUE      1    1  1.0E0  1.0E0 1\n"
        + "\n"
        + "!ok\n"
        + "!set outputformat csv\n"
        + "B00000, T000, I000, F00000, D00000, V003\n"
        + "TRUE, 1, 1, 1.0E0, 1.0E0, 1\n"
        + "!ok\n"
        + "\n";
    assertThatQuidem(input).output(containsString(output));
  }

  @Test void testOracle() {
    final String input = "!use scott\n"
        + "!set outputformat oracle\n"
        + "select * from (values 1) where 1 = 0;\n"
        + "!ok\n"
        + "select * from (values 1);\n"
        + "!ok\n"
        + "select * from (values (1), (2), (3), (4), (5));\n"
        + "!ok\n"
        + "select * from (values (1), (2), (3), (4), (5), (6));\n"
        + "!ok\n"
        + "\n";
    final String output = "!use scott\n"
        + "!set outputformat oracle\n"
        + "select * from (values 1) where 1 = 0;\n"
        + "\n"
        + "no rows selected\n"
        + "\n"
        + "!ok\n"
        + "select * from (values 1);\n"
        + "C1\n"
        + "--\n"
        + " 1\n"
        + "\n"
        + "!ok\n"
        + "select * from (values (1), (2), (3), (4), (5));\n"
        + "C1\n"
        + "--\n"
        + " 1\n"
        + " 2\n"
        + " 3\n"
        + " 4\n"
        + " 5\n"
        + "\n"
        + "!ok\n"
        + "select * from (values (1), (2), (3), (4), (5), (6));\n"
        + "C1\n"
        + "--\n"
        + " 1\n"
        + " 2\n"
        + " 3\n"
        + " 4\n"
        + " 5\n"
        + " 6\n"
        + "\n"
        + "6 rows selected.\n"
        + "\n"
        + "!ok\n"
        + "\n";
    assertThatQuidem(input).output(containsString(output));
    assertThatQuidem(output).output(containsString(output));
  }

  @Test void testTrimTrailingSpacesOracle() {
    final String input = "!use scott\n"
        + "!set outputformat oracle\n"
        + "select empno, deptno, comm from scott.emp where empno < 7700;\n"
        + "!ok\n"
        + "\n";
    final String output = "!use scott\n"
        + "!set outputformat oracle\n"
        + "select empno, deptno, comm from scott.emp where empno < 7700;\n"
        + "EMPNO DEPTNO COMM\n"
        + "----- ------ -------\n"
        + " 7369     20\n"
        + " 7499     30  300.00\n"
        + " 7521     30  500.00\n"
        + " 7566     20\n"
        + " 7654     30 1400.00\n"
        + " 7698     30\n"
        + "\n"
        + "6 rows selected.\n"
        + "\n"
        + "!ok\n"
        + "\n";
    assertThatQuidem(input).output(containsString(output));
    assertThatQuidem(output).output(containsString(output));
  }

  @Test void testTrimTrailingSpacesPsql() {
    final String input = "!use scott\n"
        + "!set outputformat psql\n"
        + "select empno, deptno, comm from scott.emp where empno < 7700;\n"
        + "!ok\n"
        + "\n";
    final String output = "!use scott\n"
        + "!set outputformat psql\n"
        + "select empno, deptno, comm from scott.emp where empno < 7700;\n"
        + " EMPNO | DEPTNO | COMM\n"
        + "-------+--------+---------\n"
        + "  7369 |     20 |\n"
        + "  7499 |     30 |  300.00\n"
        + "  7521 |     30 |  500.00\n"
        + "  7566 |     20 |\n"
        + "  7654 |     30 | 1400.00\n"
        + "  7698 |     30 |\n"
        + "(6 rows)\n"
        + "\n"
        + "!ok\n"
        + "\n";
    assertThatQuidem(input).output(containsString(output));
    assertThatQuidem(output).output(containsString(output));
  }

  /** Test case for
   * <a href="https://github.com/julianhyde/quidem/issues/3">[QUIDEM-3]
   * Trailing spaces in psql output format</a>. */
  @Test void testColumnHeading() {
    // Note: There must not be trailing spaces after 'DEPTNO | B'
    final String input = "!use scott\n"
        + "!set outputformat psql\n"
        + "select deptno, deptno > 20 as b from scott.dept order by 1;\n"
        + "!ok\n"
        + "\n";
    final String output = "!use scott\n"
        + "!set outputformat psql\n"
        + "select deptno, deptno > 20 as b from scott.dept order by 1;\n"
        + " DEPTNO | B\n"
        + "--------+-------\n"
        + "     10 | FALSE\n"
        + "     20 | FALSE\n"
        + "     30 | TRUE\n"
        + "     40 | TRUE\n"
        + "(4 rows)\n"
        + "\n"
        + "!ok\n"
        + "\n";
    assertThatQuidem(input).output(containsString(output));
  }

  /** Tests the '!update' command against INSERT and DELETE statements,
   * and also checks an intervening '!plan'. */
  @Test void testUpdate() {
    final String input0 = "!use scott\n"
        + "create table scott.dept2 (deptno int,"
        + " dname varchar(10), location varchar(10));\n"
        + "!update\n"
        + "insert into scott.dept2 select * from scott.dept;\n"
        + "!update\n"
        + "\n";
    final String output0 = "!use scott\n"
        + "create table scott.dept2 (deptno int, dname varchar(10), location varchar(10));\n"
        + "(0 rows modified)\n"
        + "\n"
        + "!update\n"
        + "insert into scott.dept2 select * from scott.dept;\n"
        + "(4 rows modified)\n"
        + "\n"
        + "!update\n"
        + "\n";
    assertThatQuidem(input0).output(containsString(output0));

    final String input1 = "!use scott\n"
        + "insert into scott.dept2 values (50, 'DEV', 'SAN DIEGO');\n"
        + "!update\n"
        + "!plan\n"
        + "\n";
    final String output1 = "!use scott\n"
        + "insert into scott.dept2 values (50, 'DEV', 'SAN DIEGO');\n"
        + "(1 row modified)\n"
        + "\n"
        + "!update\n"
        + "INSERT VALUES[\n"
        + "\n"
        + "TABLE[DEPT2]\n"
        + "PARAMETERS=[]\n"
        + "SUBQUERIES[]]\n"
        + "!plan\n"
        + "\n";
    assertThatQuidem(input1).output(containsString(output1));

    // remove the row
    final String input2 = "!use scott\n"
        + "delete from scott.dept2 where deptno = 50;\n"
        + "!update\n"
        + "\n";
    final String output2 = "!use scott\n"
        + "delete from scott.dept2 where deptno = 50;\n"
        + "(1 row modified)\n"
        + "\n"
        + "!update\n"
        + "\n";
    assertThatQuidem(input2).output(containsString(output2));

    // no row to remove
    final String output3 = "!use scott\n"
        + "delete from scott.dept2 where deptno = 50;\n"
        + "(0 rows modified)\n"
        + "\n"
        + "!update\n"
        + "\n";
    assertThatQuidem(input2).output(containsString(output3));

    // for DML, using '!ok' works, but is not as pretty as '!update'
    final String input4 = "!use scott\n"
        + "delete from scott.dept2 where deptno = 50;\n"
        + "!ok\n"
        + "\n";
    final String output4 = "!use scott\n"
        + "delete from scott.dept2 where deptno = 50;\n"
        + "C1\n"
        + "!ok\n"
        + "\n";
    assertThatQuidem(input4).output(containsString(output4));
  }

  /** Tests the '!type' command. */
  @Test void testType() {
    final String input = "!use scott\n"
        + "select empno, deptno, sal from scott.emp where empno < 7400;\n"
        + "!ok\n"
        + "!type\n"
        + "\n";
    final String output = "!use scott\n"
        + "select empno, deptno, sal from scott.emp where empno < 7400;\n"
        + "EMPNO, DEPTNO, SAL\n"
        + "7369, 20, 800.00\n"
        + "!ok\n"
        + "EMPNO SMALLINT(16) NOT NULL\n"
        + "DEPTNO TINYINT(8)\n"
        + "SAL DECIMAL(7, 2)\n"
        + "!type\n"
        + "\n";
    assertThatQuidem(input).output(containsString(output));
  }

  /** Tests the '!verify' command. */
  @Test void testVerify() {
    final String input = "!use empty\n"
        + "select * from INFORMATION_SCHEMA.TABLES;\n"
        + "!verify\n"
        + "\n";
    final String output = "!use empty\n"
        + "select * from INFORMATION_SCHEMA.TABLES;\n"
        + "!verify\n"
        + "\n";
    assertThatQuidem(input).output(containsString(output));
  }

  /** Tests the '!verify' command where the reference database produces
   * different output. */
  @Test void testVerifyDiff() {
    // Database "empty" sorts nulls first;
    // its reference database sorts nulls last.
    final String input = "!use empty\n"
        + "select * from (values (1,null),(2,'a')) order by 2;\n"
        + "!verify\n"
        + "\n";
    final String output = "!use empty\n"
        + "select * from (values (1,null),(2,'a')) order by 2;\n"
        + "!verify\n"
        + "Error while executing command VerifyCommand [sql: select * from (values (1,null),(2,'a')) order by 2]\n"
        + "java.lang.IllegalArgumentException: Reference query returned different results.\n"
        + "expected:\n"
        + "C1, C2\n"
        + "2, a\n"
        + "1, null\n"
        + "actual:\n"
        + "C1, C2\n"
        + "1, null\n"
        + "2, a\n";
    assertThatQuidem(input).output(containsString(output));
  }

  /** Tests the '!verify' command with a database that has no reference. */
  @Test void testVerifyNoReference() {
    final String input = "!use scott\n"
        + "select * from scott.emp;\n"
        + "!verify\n"
        + "\n";
    final String output = "!use scott\n"
        + "select * from scott.emp;\n"
        + "!verify\n"
        + "Error while executing command VerifyCommand [sql: select * from scott.emp]\n"
        + "java.lang.IllegalArgumentException: no reference connection\n";
    assertThatQuidem(input).output(containsString(output));
  }

  @Test void testUsage() {
    assertThatMain("--help")
        .output(startsWith("Usage: quidem argument... inFile outFile"))
        .code(is(0));
  }

  @Test void testDbBad() {
    assertThatMain("--db", "name", "jdbc:url")
        .output(startsWith("Insufficient arguments for --db"))
        .code(is(1));
  }

  @Test void testDb() throws Exception {
    final File inFile =
        writeFile("!use fm\nselect * from scott.dept;\n!ok\n");
    final File outFile = File.createTempFile("outFile", ".iq");
    assertThatMain("--db", "fm", "jdbc:hsqldb:res:scott", "SA", "",
        inFile.getAbsolutePath(), outFile.getAbsolutePath())
        .output(is(""))
        .code(is(0));
    final String output = "!use fm\n"
        + "select * from scott.dept;\n"
        + "DEPTNO, DNAME, LOC\n"
        + "10, ACCOUNTING, NEW YORK\n"
        + "20, RESEARCH, DALLAS\n"
        + "30, SALES, CHICAGO\n"
        + "40, OPERATIONS, BOSTON\n"
        + "!ok\n";
    assertThat(toLinux(contents(outFile)),
        is(output));
    //noinspection ResultOfMethodCallIgnored
    inFile.delete();
    //noinspection ResultOfMethodCallIgnored
    outFile.delete();
  }

  private File writeFile(String contents) throws IOException {
    final File inFile = File.createTempFile("inFile", ".iq");
    final FileWriter fw = new FileWriter(inFile);
    fw.append(contents);
    fw.close();
    return inFile;
  }

  @Test void testFactoryBad() {
    final String output = "Factory class non.existent.ClassName not found";
    assertThatMain("--factory", "non.existent.ClassName")
        .output(startsWith(output))
        .code(is(1));
  }

  @Test void testFactoryBad2() {
    final String output = "Error instantiating factory class java.lang.String";
    assertThatMain("--factory", "java.lang.String")
        .output(startsWith(output))
        .code(is(1));
  }

  @Test void testHelp() throws Exception {
    final String in = "!use foo\n"
        + "values 1;\n"
        + "!ok\n";
    final File inFile = writeFile(in);
    final File outFile = File.createTempFile("outFile", ".iq");
    final String out = "Usage: quidem argument... inFile outFile\n"
        + "\n"
        + "Arguments:\n"
        + "  --help\n"
        + "           Print usage\n"
        + "  --db name url user password\n"
        + "           Add a database to the connection factory\n"
        + "  --var name value\n"
        + "           Assign a value to a variable\n"
        + "  --factory className\n"
        + "           Define a connection factory (must implement interface\n"
        + "        net.hydromatic.quidem.Quidem.ConnectionFactory)\n"
        + "  --command-handler className\n"
        + "           Define a command-handler (must implement interface\n"
        + "        net.hydromatic.quidem.CommandHandler)\n";
    assertThatMain("--help", inFile.getAbsolutePath(),
        outFile.getAbsolutePath())
        .output(is(out))
        .code(is(0));
    assertThat(toLinux(contents(outFile)), is(""));
    //noinspection ResultOfMethodCallIgnored
    inFile.delete();
    //noinspection ResultOfMethodCallIgnored
    outFile.delete();
  }

  @Test void testFactory() throws Exception {
    final File inFile =
        writeFile("!use foo\nvalues 1;\n!ok\n");
    final File outFile = File.createTempFile("outFile", ".iq");
    assertThatMain("--factory", FooFactory.class.getName(),
        inFile.getAbsolutePath(), outFile.getAbsolutePath())
        .output(is(""))
        .code(is(0));
    assertThat(toLinux(contents(outFile)),
        is("!use foo\nvalues 1;\nC1\n1\n!ok\n"));
    //noinspection ResultOfMethodCallIgnored
    inFile.delete();
    //noinspection ResultOfMethodCallIgnored
    outFile.delete();
  }

  @Test void testUnknownCommandFails() {
    final String in = "!use foo\nvalues 1;\n!ok\n!foo-command args";
    try {
      assertThatQuidem(in).output(containsString("xx"));
      throw new AssertionError("expected throw");
    } catch (RuntimeException e) {
      assertThat(e.getMessage(),
          is("Unknown command: foo-command args"));
    }
  }

  @Test void testCustomCommandHandler() {
    final String in0 = "!use foo\n"
        + "values 1;\n"
        + "!ok\n"
        + "!baz-command args";
    final Quidem.ConfigBuilder configBuilder =
        Quidem.configBuilder()
            .withConnectionFactory(new FooFactory())
            .withCommandHandler(new FooCommandHandler());
    try {
      new Fluent(in0, configBuilder).output(containsString("xx"));
      throw new AssertionError("expected throw");
    } catch (RuntimeException e) {
      assertThat(e.getMessage(),
          is("Unknown command: baz-command args"));
    }

    final String in = "!use foo\n"
        + "values 1;\n"
        + "!ok\n"
        + "!foo-command args";
    final String out = "!use foo\n"
        + "values 1;\n"
        + "C1\n"
        + "1\n"
        + "!ok\n"
        + "the line: foo-command args\n"
        + "the command: FooCommand\n"
        + "previous SQL command: SqlCommand[sql: values 1, sort:true]\n";
    new Fluent(in, configBuilder).output(containsString(out));
  }

  @Test void testCustomCommandHandlerMain() throws Exception {
    final String in = "!use foo\n"
        + "values 1;\n"
        + "!ok\n"
        + "!foo-command args\n";
    final File inFile = writeFile(in);
    final File outFile = File.createTempFile("outFile", ".iq");
    assertThatMain("--factory", FooFactory.class.getName(), "--command-handler",
        FooCommandHandler.class.getName(), inFile.getAbsolutePath(),
        outFile.getAbsolutePath())
        .output(is(""))
        .code(is(0));
    final String output = "!use foo\n"
        + "values 1;\n"
        + "C1\n"
        + "1\n"
        + "!ok\n"
        + "the line: foo-command args\n"
        + "the command: FooCommand\n"
        + "previous SQL command: SqlCommand[sql: values 1, sort:true]\n";
    assertThat(toLinux(contents(outFile)), is(output));
    //noinspection ResultOfMethodCallIgnored
    inFile.delete();
    //noinspection ResultOfMethodCallIgnored
    outFile.delete();
  }

  @Test void testVar() throws Exception {
    final File inFile =
        writeFile("!if (myVar) {\nblah;\n!ok\n!}\n");
    final File outFile = File.createTempFile("outFile", ".iq");
    assertThatMain("--var", "myVar", "true", inFile.getAbsolutePath(),
        outFile.getAbsolutePath())
        .output(is(""))
        .code(is(0));
    assertThat(toLinux(contents(outFile)),
        startsWith("!if (myVar) {\n"
            + "blah;\n"
            + "!ok\n"
            + "Error while executing command OkCommand [sql: blah]\n"
            + "java.lang.RuntimeException: no connection\n"));
    //noinspection ResultOfMethodCallIgnored
    inFile.delete();
    //noinspection ResultOfMethodCallIgnored
    outFile.delete();
  }

  @Test void testVarFalse() throws Exception {
    final File inFile =
        writeFile("!if (myVar) {\nblah;\n!ok\n!}\n");
    final File outFile = File.createTempFile("outFile", ".iq");
    assertThatMain("--var", "myVar", "false", inFile.getAbsolutePath(),
        outFile.getAbsolutePath())
        .output(is(""))
        .code(is(0));
    assertThat(toLinux(contents(outFile)),
        is("!if (myVar) {\nblah;\n!ok\n!}\n"));
    //noinspection ResultOfMethodCallIgnored
    inFile.delete();
    //noinspection ResultOfMethodCallIgnored
    outFile.delete();
  }

  @Test void testSetBoolean() {
    final String input = "!use scott\n"
        + "!show foo\n"
        + "!set foo true\n"
        + "!show foo\n"
        + "!if (foo) {\n"
        + "values 1;\n"
        + "!ok;\n"
        + "!}\n"
        + "!set foo false\n"
        + "!if (foo) {\n"
        + "values 2;\n"
        + "!ok;\n"
        + "!}\n"
        + "!push foo true\n"
        + "!push foo false\n"
        + "!push foo true\n"
        + "!show foo\n"
        + "!pop foo\n"
        + "!show foo\n"
        + "!pop foo\n"
        + "!show foo\n"
        + "!pop foo\n"
        + "!show foo\n"
        + "!pop foo\n"
        + "!show foo\n"
        + "!pop foo\n"
        + "!show foo\n";
    final String output = "!use scott\n"
        + "foo null\n"
        + "!show foo\n"
        + "!set foo true\n"
        + "foo true\n"
        + "!show foo\n"
        + "!if (foo) {\n"
        + "values 1;\n"
        + "C1\n"
        + "1\n"
        + "!ok;\n"
        + "!}\n"
        + "!set foo false\n"
        + "!if (foo) {\n"
        + "values 2;\n"
        + "!ok;\n"
        + "!}\n"
        + "!push foo true\n"
        + "!push foo false\n"
        + "!push foo true\n"
        + "foo true\n"
        + "!show foo\n"
        + "!pop foo\n"
        + "foo false\n"
        + "!show foo\n"
        + "!pop foo\n"
        + "foo true\n"
        + "!show foo\n"
        + "!pop foo\n"
        + "foo false\n"
        + "!show foo\n"
        + "!pop foo\n"
        + "foo null\n"
        + "!show foo\n"
        + "!pop foo\n"
        + "Cannot pop foo: stack is empty\n"
        + "foo null\n"
        + "!show foo\n";
    assertThatQuidem(input).output(containsString(output));
  }

  @Test void testSetInteger() {
    final String input = "!use scott\n"
        + "!show foo\n"
        + "!set foo -123\n"
        + "!show foo\n"
        + "!push foo 345\n"
        + "!push bar 0\n"
        + "!push foo hello\n"
        + "!show foo\n"
        + "!pop foo\n"
        + "!show foo\n"
        + "!pop foo\n"
        + "!show foo\n"
        + "!show foo\n"
        + "!pop foo\n"
        + "!show foo\n";
    final String output = "!use scott\n"
        + "foo null\n"
        + "!show foo\n"
        + "!set foo -123\n"
        + "foo -123\n"
        + "!show foo\n"
        + "!push foo 345\n"
        + "!push bar 0\n"
        + "!push foo hello\n"
        + "foo hello\n"
        + "!show foo\n"
        + "!pop foo\n"
        + "foo 345\n"
        + "!show foo\n"
        + "!pop foo\n"
        + "foo -123\n"
        + "!show foo\n"
        + "foo -123\n"
        + "!show foo\n"
        + "!pop foo\n"
        + "foo null\n"
        + "!show foo\n";
    assertThatQuidem(input).output(containsString(output));
  }

  /** Tests that the {@link net.hydromatic.quidem.Quidem.PropertyHandler} is
   * called whenever there is a set, push or pop. */
  @Test void testPropertyHandler() {
    final String input = "!use scott\n"
        + "!show foo\n"
        + "!set foo -123\n"
        + "!show foo\n"
        + "!push foo 345\n"
        + "!push bar 0\n"
        + "!push foo hello\n"
        + "!show foo\n"
        + "!pop foo\n"
        + "!show foo\n"
        + "!pop foo\n"
        + "!show foo\n"
        + "!show foo\n"
        + "!pop foo\n"
        + "!show foo\n";
    final String output = "!use scott\n"
        + "foo null\n"
        + "!show foo\n"
        + "!set foo -123\n"
        + "foo -123\n"
        + "!show foo\n"
        + "!push foo 345\n"
        + "!push bar 0\n"
        + "!push foo hello\n"
        + "foo hello\n"
        + "!show foo\n"
        + "!pop foo\n"
        + "foo 345\n"
        + "!show foo\n"
        + "!pop foo\n"
        + "foo -123\n"
        + "!show foo\n"
        + "foo -123\n"
        + "!show foo\n"
        + "!pop foo\n"
        + "foo null\n"
        + "!show foo\n";
    final StringBuilder b = new StringBuilder();
    final Quidem.PropertyHandler propertyHandler =
        (propertyName, value) -> b.append(propertyName).append('=')
            .append(value).append('\n');
    assertThatQuidem(input)
        .withPropertyHandler(propertyHandler)
        .output(containsString(output));
    final String propertyEvents = "foo=-123\n"
        + "foo=345\n"
        + "bar=0\n"
        + "foo=hello\n"
        + "foo=345\n"
        + "foo=-123\n"
        + "foo=null\n";
    assertThat(b.toString(), is(propertyEvents));
  }

  @Test void testLimitWriter() throws IOException {
    final StringWriter w = new StringWriter();
    LimitWriter limitWriter = new LimitWriter(w, 6);
    limitWriter.append("abcdefghiklmnopq");
    assertThat(w.toString(), is("abcdef"));

    // We already exceeded limit. Clearing the backing buffer does not help.
    w.getBuffer().setLength(0);
    limitWriter.append("xxxxx");
    limitWriter.append("yyyyy");
    assertThat(w.toString(), is(""));

    // Create a new writer to reset the count.
    limitWriter = new LimitWriter(w, 6);
    w.getBuffer().setLength(0);
    limitWriter.append("xxxxx");
    limitWriter.append("yyyyy");
    assertThat(w.toString(), is("xxxxxy"));

    limitWriter = new LimitWriter(w, 6);
    w.getBuffer().setLength(0);
    limitWriter.append("xxx");
    limitWriter.append('y');
    limitWriter.append('y');
    limitWriter.append('y');
    limitWriter.append('y');
    limitWriter.append('y');
    limitWriter.append('y');
    limitWriter.append('y');
    limitWriter.append("");
    limitWriter.append('z');
    limitWriter.append("zzzzzzzzz");
    assertThat(w.toString(), is("xxxyyy"));

    limitWriter = new LimitWriter(w, 6);
    w.getBuffer().setLength(1);
    assertThat(w.toString(), is("x"));
    w.getBuffer().setLength(2);
    assertThat(w.toString(), is("x\0"));
    limitWriter.write(new char[]{'a', 'a', 'a', 'a', 'a'}, 0, 3);
    assertThat(w.toString(), is("x\0aaa"));
  }

  static Main assertThatMain(String... args) {
    return new Main(ImmutableList.copyOf(args));
  }

  private static String contents(File file) throws IOException {
    final FileReader reader = new FileReader(file);
    final StringWriter sw = new StringWriter();
    final char[] buf = new char[1024];
    for (;;) {
      final int read = reader.read(buf, 0, 1024);
      if (read < 0) {
        break;
      }
      sw.write(buf, 0, read);
    }
    return sw.toString();
  }

  static Fluent assertThatQuidem(String input) {
    return new Fluent(input);
  }

  /** Creates a connection factory for use in tests. */
  private static Quidem.ConnectionFactory dummyConnectionFactory() {
    return (name, reference) -> {
      if (name.equals("scott")) {
        Class.forName("org.hsqldb.jdbcDriver");
        final Connection connection =
            DriverManager.getConnection("jdbc:hsqldb:res:scott", "SA",
                "");
        if (reference) {
          return null; // no reference connection available for empty
        }
        return connection;
      }
      if (name.startsWith("empty")) {
        Class.forName("org.hsqldb.jdbcDriver");
        if (reference) {
          name += "_ref";
        }
        final Connection connection =
            DriverManager.getConnection("jdbc:hsqldb:mem:" + name, "",
                "");
        if (reference) {
          final Statement statement = connection.createStatement();
          //noinspection SqlNoDataSourceInspection
          statement.executeQuery("SET DATABASE SQL NULLS FIRST FALSE");
          statement.close();
        }
        return connection;
      }
      throw new RuntimeException("unknown connection '" + name + "'");
    };
  }

  /** Creates an environment for use in tests. */
  private static Object dummyEnv(String input) {
    assert input != null;
    switch (input) {
    case "affirmative":
      return Boolean.TRUE;
    case "negative":
      return Boolean.FALSE;
    case "sun":
      return new Function<String, Object>() {
        public Object apply(String input) {
          assert input != null;
          switch (input) {
          case "hot":
            return Boolean.TRUE;
          case "cold":
            return Boolean.FALSE;
          case "self":
            return this;
          default:
            return null;
          }
        }
      };
    default:
      return null;
    }
  }

  public static String toLinux(String s) {
    return s.replaceAll("\r\n", "\n");
  }

  public static class FooFactory implements Quidem.ConnectionFactory {
    public Connection connect(String name, boolean reference) throws Exception {
      if (name.equals("foo")) {
        return makeConnection(false);
      }
      if (name.equals("baz")) {
        return makeConnection(true);
      }
      return null;
    }

    private Connection makeConnection(boolean wrapped) throws SQLException {
      final Connection connection =
          DriverManager.getConnection("jdbc:hsqldb:res:scott", "SA", "");
      if (wrapped) {
        return (Connection) Proxy.newProxyInstance(
            QuidemTest.class.getClassLoader(),
            new Class[]{Connection.class}, (proxy, method, args) -> {
              if (method.getName().equals("createStatement")
                  && args == null) {
                return createStatement(connection);
              }
              return method.invoke(connection, args);
            });
      }
      return connection;
    }

    private Statement createStatement(Connection connection)
        throws SQLException {
      final Statement statement = connection.createStatement();
      return (Statement) Proxy.newProxyInstance(
          QuidemTest.class.getClassLoader(),
          new Class[]{Statement.class}, (proxy, method, args) -> {
            if (method.getName().equals("executeQuery")) {
              return executeQuery(statement, (String) args[0]);
            }
            return method.invoke(statement, args);
          });
    }

    private ResultSet executeQuery(Statement statement, String sql)
        throws SQLException {
      final ResultSet resultSet = statement.executeQuery(sql);
      final AtomicInteger fetchCount = new AtomicInteger(0);
      return (ResultSet) Proxy.newProxyInstance(
          QuidemTest.class.getClassLoader(),
          new Class[]{ResultSet.class}, (proxy, method, args) -> {
            if (method.getName().equals("next")) {
              if (fetchCount.getAndIncrement() == 3) {
                throw new SQLException("bang!");
              }
            }
            return method.invoke(resultSet, args);
          });
    }
  }

  /** Implementation of {@link CommandHandler} for test purposes. */
  public static class FooCommandHandler implements CommandHandler {
    @Override public Command parseCommand(List<String> lines,
        List<String> content, final String line) {
      if (line.startsWith("foo")) {
        return new Command() {
          @Override public String describe(Context x) {
            return "FooCommand";
          }

          @Override public void execute(Context x, boolean execute) {
            x.writer().println("the line: " + line);
            x.writer().println("the command: " + describe(x));
            x.writer().println("previous SQL command: "
                + x.previousSqlCommand().describe(x));
          }
        };
      }
      return null;
    }
  }

  /** Fluent class that contains an input string and allows you to test the
   * output in various ways. */
  @SuppressWarnings("UnusedReturnValue")
  private static class Fluent {
    private final String input;
    private final Quidem.ConfigBuilder configBuilder;
    @SuppressWarnings("FunctionalExpressionCanBeFolded") // for Guava < 21
    private final Supplier<Run> run = Suppliers.memoize(this::run)::get;

    Fluent(String input) {
      this(input, Quidem.configBuilder()
          .withConnectionFactory(dummyConnectionFactory())
          .withEnv(QuidemTest::dummyEnv));
    }

    Fluent(String input, Quidem.ConfigBuilder configBuilder) {
      this.input = input;
      this.configBuilder = configBuilder;
    }

    private Run run() {
      final StringWriter writer = new StringWriter();
      final Quidem.Config config = configBuilder
          .withWriter(writer)
          .withReader(new StringReader(input))
          .build();
      final Quidem run = new Quidem(config);
      run.execute();
      writer.flush();
      String out = toLinux(writer.toString());
      return new Run(out);
    }

    public Fluent output(Matcher<String> matcher) {
      assertThat(run.get().output, matcher);
      return this;
    }

    public Fluent transform(UnaryOperator<Quidem.ConfigBuilder> transform) {
      return new Fluent(input, transform.apply(configBuilder));
    }

    public Fluent limit(final int i) {
      return transform(b -> b.withStackLimit(i));
    }

    public Fluent withPropertyHandler(
        final Quidem.PropertyHandler propertyHandler) {
      return transform(b -> b.withPropertyHandler(propertyHandler));
    }

    /** Output of a run of the program. */
    static class Run {
      final String output;

      Run(String output) {
        this.output = output;
      }
    }
  }

  /** Fluent runner that calls {@link Launcher#main2(PrintWriter, PrintWriter, List)}. */
  @SuppressWarnings("UnusedReturnValue")
  static class Main {
    private final ImmutableList<String> argList;
    @SuppressWarnings("FunctionalExpressionCanBeFolded") // for Guava < 21
    private final Supplier<Run> run = Suppliers.memoize(this::run)::get;

    Main(ImmutableList<String> argList) {
      this.argList = argList;
    }

    private Run run() {
      final StringWriter sw = new StringWriter();
      final PrintWriter pw = new PrintWriter(sw);
      final int code = Launcher.main2(pw, pw, argList);
      pw.close();
      return new Run(code, sw.toString());
    }

    Main code(Matcher<Integer> matcher) {
      assertThat(run.get().code, matcher);
      return this;
    }

    Main output(Matcher<String> matcher) {
      assertThat(run.get().output, matcher);
      return this;
    }

    /** Output of a run of the program. */
    static class Run {
      final int code;
      final String output;

      Run(int code, String output) {
        this.code = code;
        this.output = output;
      }
    }
  }
}

// End QuidemTest.java
