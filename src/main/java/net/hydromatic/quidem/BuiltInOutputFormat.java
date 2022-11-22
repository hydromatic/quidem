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

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Schemes for converting the output of a SQL statement into text. */
enum BuiltInOutputFormat implements OutputFormat {
  /**
   * CSV output format.
   */
  CSV {
    @Override public void format(ResultSet resultSet, List<String> headerLines,
        List<String> bodyLines, List<String> footerLines, boolean sort)
        throws Exception {
      final ResultSetMetaData metaData = resultSet.getMetaData();
      final int n = metaData.getColumnCount();
      final StringBuilder buf = new StringBuilder();
      for (int i = 0; i < n; i++) {
        if (i > 0) {
          buf.append(", ");
        }
        buf.append(metaData.getColumnLabel(i + 1));
      }
      headerLines.add(buf.toString());
      buf.setLength(0);
      final List<String> lines = new ArrayList<>();
      while (resultSet.next()) {
        for (int i = 0; i < n; i++) {
          if (i > 0) {
            buf.append(", ");
          }
          buf.append(resultSet.getString(i + 1));
        }
        lines.add(buf.toString());
        buf.setLength(0);
      }
      if (sort) {
        Collections.sort(lines);
      }
      bodyLines.addAll(lines);
    }
  },

  /** MySQL output format.
   *
   * <p>Example:
   *
   * <blockquote><pre>
   *   +-------+--------+--------+-------------+
   *   | ename | deptno | gender | first_value |
   *   +-------+--------+--------+-------------+
   *   | Jane  |     10 | F      | Jane        |
   *   | Bob   |     10 | M      | Jane        |
   *   +-------+--------+--------+-------------+
   *   (2 rows)
   * </pre></blockquote>
   */
  MYSQL {
    @Override public void format(ResultSet resultSet, List<String> headerLines,
        List<String> bodyLines, List<String> footerLines, boolean sort)
        throws Exception {
      Quidem.format(resultSet, headerLines, bodyLines, footerLines, sort, this);
    }
  },

  /**
   * Oracle output format.
   *
   * <p>Example 1 (0 rows):
   *
   * <blockquote><pre>
   * no rows selected
   * </pre></blockquote>
   *
   * <p>Example 2 (fewer than 6 rows):
   *
   * <blockquote><pre>
   *   ename deptno gender first_value
   *   ----- ------ ------ -----------
   *   Jane      10 F      Jane
   *   Bob       10 M      Jane
   * </pre></blockquote>
   *
   * <p>Example 3 (6 or more rows):
   *
   * <blockquote><pre>
   *   ename deptno gender first_value
   *   ----- ------ ------ -----------
   *   Jane      10 F      Jane
   *   Bob       10 M      Jane
   *   Alpha     10 M      Jane
   *   Charlie   10 F      Jane
   *   Delta     10 M      Jane
   *   Echo      10 F      Jane
   * &nbsp;
   *   7 rows selected.
   * </pre></blockquote>
   */
  ORACLE {
    @Override public void format(ResultSet resultSet, List<String> headerLines,
        List<String> bodyLines, List<String> footerLines, boolean sort)
        throws Exception {
      Quidem.format(resultSet, headerLines, bodyLines, footerLines, sort, this);
    }
  },

  /**
   * PostgreSQL output format.
   *
   * <p>Example:
   *
   * <blockquote>
   *   <pre>
   *  ename | deptno | gender | first_value
   * -------+--------+--------+-------------
   *  Jane  |     10 | F      | Jane
   *  Bob   |     10 | M      | Jane
   * (2 rows)
   *   </pre>
   * </blockquote>
   */
  PSQL {
    @Override public void format(ResultSet resultSet, List<String> headerLines,
        List<String> bodyLines, List<String> footerLines, boolean sort)
        throws Exception {
      Quidem.format(resultSet, headerLines, bodyLines, footerLines, sort, this);
    }
  }
}

// End BuiltInOutputFormat.java
