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

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringTokenizer;
import java.util.function.Function;

/**
 * Runs a SQL script.
 */
public class Quidem {
  private static final Ordering<String[]> ORDERING =
      Ordering.natural().nullsLast().lexicographical()
          .onResultOf(input -> Arrays.asList(input));

  public static final boolean DEBUG =
      "true".equals(System.getProperties().getProperty("quidem.debug"));

  /** Default value for {@link #setStackLimit(int)}. */
  private static final int DEFAULT_MAX_STACK_LENGTH = 16384;

  /** The empty environment. Returns null for all variables. */
  public static final Function<String, Object> EMPTY_ENV =
      name -> null;

  /** The empty environment. Returns null for all database names. */
  public static final ConnectionFactory EMPTY_CONNECTION_FACTORY =
      new ChainingConnectionFactory(ImmutableList.of());

  /** A command handler that defines no commands. */
  public static final CommandHandler EMPTY_COMMAND_HANDLER =
      (lines, content, line) -> null;

  /** A property handler that does nothing. */
  public static final PropertyHandler EMPTY_PROPERTY_HANDLER =
      (propertyName, value) -> { };

  /** The default value of FEEDBACK in SQL*Plus (the minimum number of rows to
   * print "n rows selected.") is 6. */
  private static final int ORACLE_FEEDBACK = 6;

  private final BufferedReader reader;
  private final PrintWriter writer;
  /** Holds a stack of values for each property. */
  private final Map<String, List<Object>> map =
      new HashMap<String, List<Object>>();
  private final Config config;
  /** Result set from SQL statement just executed. */
  private ResultSet resultSet;
  private Throwable resultSetException;
  private final List<String> lines = new ArrayList<String>();
  private String pushedLine;
  private final StringBuilder buf = new StringBuilder();
  private Connection connection;
  private Connection refConnection;
  private boolean execute = true;
  private boolean skip = false;
  private final Function<String, Object> env;
  private SqlCommand previousSqlCommand;

  /** Creates a Quidem interpreter with an empty environment and empty
   * connection factory. */
  public Quidem(Reader reader, Writer writer) {
    this(configBuilder().withReader(reader).withWriter(writer).build());
  }

  /** @deprecated Use {@link #Quidem(Config)} and
   * {@link ConfigBuilder} */
  @Deprecated // will be removed before 0.10
  public Quidem(Reader reader, Writer writer, Function<String, Object> env,
      ConnectionFactory connectionFactory) {
    this(configBuilder()
        .withReader(reader)
        .withWriter(writer)
        .withConnectionFactory(connectionFactory)
        .withEnv(env)
        .build());
  }

  /** Creates a Quidem interpreter. */
  public Quidem(Config config) {
    this.config = config;
    final Reader rawReader = config.reader();
    if (rawReader instanceof BufferedReader) {
      this.reader = (BufferedReader) rawReader;
    } else {
      this.reader = new BufferedReader(rawReader);
    }
    final Writer rawWriter = config.writer();
    if (rawWriter instanceof PrintWriter) {
      this.writer = (PrintWriter) rawWriter;
    } else {
      this.writer = new PrintWriter(rawWriter);
    }
    final List<Object> list = new ArrayList<Object>();
    list.add(BuiltInOutputFormat.CSV);
    this.map.put(Property.OUTPUTFORMAT.propertyName(), list);
    this.env = new TopEnv(config.env());
  }

  /** Creates a {@link ConfigBuilder} with the default settings. */
  public static ConfigBuilder configBuilder() {
    return new ConfigBuilder(new StringReader(""), new StringWriter(),
        EMPTY_CONNECTION_FACTORY, EMPTY_COMMAND_HANDLER,
        EMPTY_PROPERTY_HANDLER, EMPTY_ENV, DEFAULT_MAX_STACK_LENGTH);
  }

  /** Creates a {@link ConfigBuilder} that contains a copy of the current
   * configuration. */
  private ConfigBuilder copyConfigBuilder() {
    return configBuilder()
        .withReader(config.reader())
        .withWriter(config.writer())
        .withConnectionFactory(config.connectionFactory())
        .withCommandHandler(config.commandHandler())
        .withPropertyHandler(config.propertyHandler())
        .withEnv(config.env());
  }

  /** @deprecated Use
   * {@link ConfigBuilder#withConnectionFactory(ConnectionFactory)} */
  @Deprecated // will be removed before 0.10
  public Quidem withConnectionFactory(ConnectionFactory connectionFactory) {
    return new Quidem(copyConfigBuilder()
        .withConnectionFactory(connectionFactory).build());
  }

  /** @deprecated Use
   * {@link ConfigBuilder#withPropertyHandler(PropertyHandler)} */
  @Deprecated // will be removed before 0.10
  public Quidem withPropertyHandler(PropertyHandler propertyHandler) {
    return new Quidem(copyConfigBuilder()
        .withPropertyHandler(propertyHandler).build());
  }

  /** Entry point from the operating system command line.
   *
   * <p>Calls {@link System#exit(int)} with the following status codes:
   * <ul>
   *   <li>0: success</li>
   *   <li>1: invalid arguments</li>
   *   <li>2: help</li>
   * </ul>
   *
   * @param args Command-line arguments
   */
  public static void main(String[] args) {
    final PrintWriter out = new PrintWriter(System.out);
    final PrintWriter err = new PrintWriter(System.err);
    final int code = Launcher.main2(out, err, Arrays.asList(args));
    System.exit(code);
  }

  private void close() throws SQLException {
    if (connection != null) {
      Connection c = connection;
      connection = null;
      c.close();
    }
    if (refConnection != null) {
      Connection c = refConnection;
      refConnection = null;
      c.close();
    }
  }

  /** Executes the commands from the input, writing to the output,
   * then closing both input and output. */
  public void execute() {
    try {
      Command command = new Parser().parse();
      try {
        command.execute(new ContextImpl(), execute);
        close();
      } catch (Exception e) {
        throw new RuntimeException(
            "Error while executing command " + command, e);
      } catch (AssertionError e) {
        throw new RuntimeException(
            "Error while executing command " + command, e);
      }
    } finally {
      try {
        reader.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
      writer.close();
      try {
        close();
      } catch (SQLException e) {
        // ignore
      }
    }
  }

  private void use(String connectionName) throws Exception {
    if (connection != null) {
      connection.close();
    }
    if (refConnection != null) {
      refConnection.close();
    }
    final ConnectionFactory connectionFactory =
        config.connectionFactory();
    connection = connectionFactory.connect(connectionName, false);
    refConnection = connectionFactory.connect(connectionName, true);
  }

  protected void echo(Iterable<String> lines) {
    for (String line : lines) {
      try {
        writer.println(line);
      } catch (Exception e) {
        throw new RuntimeException("Error while writing output", e);
      }
    }
  }

  private void update(String sql, boolean execute, boolean output,
      Command.ResultChecker checker, Command.Context x) throws Exception {
    if (execute) {
      if (connection == null) {
        throw new RuntimeException("no connection");
      }
      final Statement statement = connection.createStatement();
      if (resultSet != null) {
        resultSet.close();
      }
      try {
        if (DEBUG) {
          System.out.println("execute: " + this);
        }
        resultSet = null;
        resultSetException = null;
        final int updateCount = statement.executeUpdate(sql);
        writer.println("(" + updateCount
            + ((updateCount == 1) ? " row" : " rows")
            + " modified)");
      } catch (SQLException e) {
        resultSetException = e;
      } catch (Throwable e) {
        System.out.println("Warning: JDBC driver threw non-SQLException");
        resultSetException = e;
      } finally {
        statement.close();
      }

      checker.checkResultSet(x, resultSetException);
      writer.println();

      resultSet = null;
      resultSetException = null;
    } else {
      echo(checker.getOutput(x));
    }
    echo(lines);
  }

  private void checkResult(boolean execute, boolean output,
      Command.ResultChecker checker, Command.Context x)
      throws Exception {
    if (execute) {
      if (connection == null) {
        throw new RuntimeException("no connection");
      }
      final SqlCommand sqlCommand = x.previousSqlCommand();
      final Statement statement = connection.createStatement();
      if (resultSet != null) {
        resultSet.close();
      }
      try {
        final List<String> headerLines = new ArrayList<String>();
        final List<String> bodyLines = new ArrayList<String>();
        final List<String> footerLines = new ArrayList<String>();
        try {
          if (DEBUG) {
            System.out.println("execute: " + this);
          }
          resultSet = null;
          resultSetException = null;
          resultSet = statement.executeQuery(sqlCommand.sql);
          if (resultSet != null) {
            final OutputFormat format =
                (OutputFormat) env.apply(Property.OUTPUTFORMAT.propertyName());
            format.format(resultSet, headerLines, bodyLines, footerLines,
                sqlCommand.sort);
          }
        } catch (SQLException e) {
          resultSetException = e;
        } catch (Throwable e) {
          System.out.println("Warning: JDBC driver threw non-SQLException");
          resultSetException = e;
        }
        if (resultSetException == null && resultSet != null) {
          // Construct the original body.
          // Strip the header and footer from the actual output.
          // We assume original and actual header have the same line count.
          // Ditto footers.
          final List<String> expectedLines = checker.getOutput(x);
          final List<String> lines = new ArrayList<String>(expectedLines);
          final List<String> actualLines =
              ImmutableList.<String>builder()
                  .addAll(headerLines)
                  .addAll(bodyLines)
                  .addAll(footerLines)
                  .build();
          for (String line : headerLines) {
            if (!lines.isEmpty()) {
              lines.remove(0);
            }
          }
          for (String line : footerLines) {
            if (!lines.isEmpty()) {
              lines.remove(lines.size() - 1);
            }
          }

          // Print the actual header.
          for (String line : headerLines) {
            if (output) {
              writer.println(line);
            }
          }
          // Print all lines that occurred in the actual output ("bodyLines"),
          // but in their original order ("lines").
          for (String line : lines) {
            if (sqlCommand.sort) {
              if (bodyLines.remove(line)) {
                if (output) {
                  writer.println(line);
                }
              }
            } else {
              if (!bodyLines.isEmpty()
                  && bodyLines.get(0).equals(line)) {
                bodyLines.remove(0);
                if (output) {
                  writer.println(line);
                }
              }
            }
          }
          // Print lines that occurred in the actual output but not original.
          for (String line : bodyLines) {
            if (output) {
              writer.println(line);
            }
          }
          // Print the actual footer.
          for (String line : footerLines) {
            if (output) {
              writer.println(line);
            }
          }
          resultSet.close();
          if (!output) {
            if (!actualLines.equals(expectedLines)) {
              final StringWriter buf = new StringWriter();
              final PrintWriter w = new PrintWriter(buf);
              w.println("Reference query returned different results.");
              w.println("expected:");
              for (String line : expectedLines) {
                w.println(line);
              }
              w.println("actual:");
              for (String line : actualLines) {
                w.println(line);
              }
              w.close();
              throw new IllegalArgumentException(buf.toString());
            }
          }
        }

        checker.checkResultSet(x, resultSetException);

        if (resultSet == null && resultSetException == null) {
          throw new AssertionError("neither resultSet nor exception set");
        }
        resultSet = null;
        resultSetException = null;
      } finally {
        statement.close();
      }
    } else {
      if (output) {
        echo(checker.getOutput(x));
      }
    }
    echo(lines);
  }

  Command of(List<Command> commands) {
    return commands.size() == 1
        ? commands.get(0)
        : new CompositeCommand(ImmutableList.copyOf(commands));
  }

  private static String pad(String s, int width, boolean right) {
    if (s == null) {
      s = "";
    }
    final int x = width - s.length();
    if (x <= 0) {
      return s;
    }
    final StringBuilder buf = new StringBuilder();
    if (right) {
      buf.append(chars(' ', x)).append(s);
    } else {
      buf.append(s).append(chars(' ', x));
    }
    return buf.toString();
  }

  <E> Iterator<String> stringIterator(final Enumeration<E> enumeration) {
    return new Iterator<String>() {
      public boolean hasNext() {
        return enumeration.hasMoreElements();
      }

      public String next() {
        return enumeration.nextElement().toString();
      }

      public void remove() {
        throw new UnsupportedOperationException();
      }
    };
  }

  private static CharSequence chars(final char c, final int length) {
    return new CharSequence() {
      @Override public String toString() {
        return Strings.repeat("" + c, length);
      }

      public int length() {
        return length;
      }

      public char charAt(int index) {
        return c;
      }

      public CharSequence subSequence(int start, int end) {
        return Quidem.chars(c, end - start);
      }
    };
  }

  /** @deprecated Use {@link ConfigBuilder#withStackLimit(int)}. */
  @Deprecated // will be removed before 0.10
  public void setStackLimit(int stackLimit) {
    throw new UnsupportedOperationException("no longer supported");
  }

  private boolean getBoolean(List<String> names) {
    if (names.size() == 1) {
      if (names.get(0).equals("true")) {
        return true;
      }
      if (names.get(0).equals("false")) {
        return false;
      }
    }
    Function<String, Object> e = env;
    for (int i = 0; i < names.size(); i++) {
      String name = names.get(i);
      final Object value = e.apply(name);
      if (value instanceof Function) {
        //noinspection unchecked
        e = (Function<String, Object>) value;
      } else if (i == names.size() - 1) {
        if (value instanceof Boolean) {
          return (Boolean) value;
        }
        return value != null
            && value.toString().equalsIgnoreCase("true");
      }
    }
    return false;
  }

  /** Returns whether a SQL query is likely to produce results always in the
   * same order.
   *
   * <p>If Quidem believes that the order is deterministic, it does not sort
   * the rows before comparing them.
   *
   * <p>The result is just a guess. Quidem does not understand the finer points
   * of SQL semantics.
   *
   * @param sql SQL query
   * @return Whether the order is likely to be deterministic
   */
  public boolean isProbablyDeterministic(String sql) {
    final String upperSql = sql.toUpperCase();
    if (!upperSql.contains("ORDER BY")) {
      return false;
    }
    final int i = upperSql.lastIndexOf("ORDER BY");
    final String tail = upperSql.substring(i);
    final int closeCount = tail.length() - tail.replace(")", "").length();
    final int openCount = tail.length() - tail.replace("(", "").length();
    if (closeCount > openCount) {
      // The last ORDER BY occurs within parentheses. It is either in a
      // sub-query or in a windowed aggregate. Neither of these make the
      // ordering deterministic.
      return false;
    }
    return true;
  }

  /** Parser. */
  private class Parser {
    final List<Command> commands = new ArrayList<Command>();

    Command parse() {
      for (;;) {
        Command command;
        try {
          command = nextCommand();
        } catch (IOException e) {
          throw new RuntimeException("Error while reading next command", e);
        }
        if (command == null) {
          break;
        }
        commands.add(command);
      }
      return of(commands);
    }

    private Command nextCommand() throws IOException {
      lines.clear();
      ImmutableList<String> content = ImmutableList.of();
      for (;;) {
        String line = nextLine();
        if (line == null) {
          return null;
        }
        if (line.startsWith("#") || line.isEmpty()) {
          return new CommentCommand(lines);
        }
        if (line.startsWith("!")) {
          line = line.substring(1);
          while (line.startsWith(" ")) {
            line = line.substring(1);
          }
          if (line.startsWith("use")) {
            String[] parts = line.split(" ");
            return new UseCommand(lines, parts[1]);
          }
          if (line.startsWith("ok")) {
            return new OkCommand(lines, content);
          }
          if (line.startsWith("verify")) {
            // "content" may or may not be empty. We ignore it.
            // This allows people to switch between '!ok' and '!verify'.
            return new VerifyCommand(lines);
          }
          if (line.startsWith("update")) {
            return new UpdateCommand(lines, content);
          }
          if (line.startsWith("plan")) {
            return new ExplainCommand(lines, content);
          }
          if (line.startsWith("type")) {
            return new TypeCommand(lines, content);
          }
          if (line.startsWith("error")) {
            return new ErrorCommand(lines, content);
          }
          if (line.startsWith("skip")) {
            return new SkipCommand(lines);
          }
          if (line.startsWith("pop")) {
            String[] parts = line.split(" ");
            String propertyName = parts[1];
            Property property;
            if (propertyName.equals("outputformat")) {
              property = Property.OUTPUTFORMAT;
            } else {
              property = Property.OTHER;
            }
            return new PopCommand(lines, property, propertyName);
          }
          if (line.startsWith("set ") || line.startsWith("push ")) {
            String[] parts = line.split(" ");
            String propertyName = parts[1];
            String valueString = parts[2];
            Object value;
            Property property;
            if (propertyName.equals("outputformat")) {
              property = Property.OUTPUTFORMAT;
              value = BuiltInOutputFormat.valueOf(parts[2].toUpperCase());
            } else {
              property = Property.OTHER;
              if (valueString.equals("null")) {
                value = null;
              } else if (valueString.equals("true")) {
                value = Boolean.TRUE;
              } else if (valueString.equals("false")) {
                value = Boolean.FALSE;
              } else if (valueString.matches("-?[0-9]+")) {
                value = new BigDecimal(valueString);
              } else {
                value = valueString;
              }
            }
            return line.startsWith("push ")
                ? new PushCommand(lines, property, propertyName, value)
                : new SetCommand(lines, property, propertyName, value);
          }
          if (line.startsWith("show ")) {
            String[] parts = line.split(" ");
            String propertyName = parts[1];
            Property property;
            if (propertyName.equals("outputformat")) {
              property = Property.OUTPUTFORMAT;
            } else {
              property = Property.OTHER;
            }
            return new ShowCommand(lines, property, propertyName);
          }

          if (line.matches("if \\([A-Za-z-][A-Za-z_0-9.]*\\) \\{")) {
            List<String> ifLines = ImmutableList.copyOf(lines);
            lines.clear();
            Command command = new Parser().parse();
            String variable =
                line.substring("if (".length(),
                    line.length() - ") {".length());
            List<String> variables =
                ImmutableList.copyOf(
                    stringIterator(new StringTokenizer(variable, ".")));
            return new IfCommand(ifLines, lines, command, variables);
          }
          if (line.equals("}")) {
            return null;
          }
          final Command command =
              config.commandHandler().parseCommand(lines, content, line);
          if (command != null) {
            return command;
          }
          throw new RuntimeException("Unknown command: " + line);
        }
        buf.setLength(0);
        boolean last = false;
        for (;;) {
          if (line.endsWith(";")) {
            last = true;
            line = line.substring(0, line.length() - 1);
          }
          buf.append(line);
          if (last) {
            break;
          }
          buf.append("\n");
          line = nextLine();
          if (line == null) {
            throw new RuntimeException(
                "end of file reached before end of SQL command");
          }
          if (line.startsWith("!") || line.startsWith("#")) {
            pushLine();
            break;
          }
        }
        content = ImmutableList.copyOf(lines);
        lines.clear();
        if (last) {
          String sql = buf.toString();
          final boolean sort = !isProbablyDeterministic(sql);
          return new SqlCommand(content, sql, sort);
        }
      }
    }

    private void pushLine() {
      if (pushedLine != null) {
        throw new AssertionError("cannot push two lines");
      }
      if (lines.size() == 0) {
        throw new AssertionError("no line has been read");
      }
      pushedLine = lines.get(lines.size() - 1);
      lines.remove(lines.size() - 1);
    }

    private String nextLine() throws IOException {
      String line;
      if (pushedLine != null) {
        line = pushedLine;
        pushedLine = null;
      } else {
        line = reader.readLine();
        if (line == null) {
          return null;
        }
      }
      lines.add(line);
      return line;
    }
  }

  /** Called from {@link BuiltInOutputFormat} methods. */
  static void format(ResultSet resultSet, List<String> headerLines,
      List<String> bodyLines, List<String> footerLines, boolean sort,
      BuiltInOutputFormat format) throws SQLException {
    final boolean mysql = format == BuiltInOutputFormat.MYSQL;
    final ResultSetMetaData metaData = resultSet.getMetaData();
    final int n = metaData.getColumnCount();
    final int[] widths = new int[n];
    final List<String[]> rows = new ArrayList<>();
    final boolean[] rights = new boolean[n];

    for (int i = 0; i < n; i++) {
      widths[i] = metaData.getColumnLabel(i + 1).length();
    }
    while (resultSet.next()) {
      String[] row = new String[n];
      for (int i = 0; i < n; i++) {
        String value = resultSet.getString(i + 1);
        widths[i] = Math.max(widths[i], value == null ? 0 : value.length());
        row[i] = value;
      }
      rows.add(row);
    }
    for (int i = 0; i < widths.length; i++) {
      switch (metaData.getColumnType(i + 1)) {
      case Types.TINYINT:
      case Types.SMALLINT:
      case Types.INTEGER:
      case Types.BIGINT:
      case Types.FLOAT:
      case Types.REAL:
      case Types.DOUBLE:
      case Types.NUMERIC:
      case Types.DECIMAL:
        rights[i] = true;
      }
    }

    if (sort) {
      Collections.sort(rows, ORDERING);
    }

    switch (format) {
    case ORACLE:
      if (rows.isEmpty()) {
        footerLines.add("");
        footerLines.add("no rows selected");
        footerLines.add("");
        return;
      }
    }

    // Compute "+-----+---+" (if mysql)
    // or       "-----+---" (if postgres)
    // or       "---- -" (if oracle)
    final StringBuilder buf = new StringBuilder();
    for (int i = 0; i < n; i++) {
      switch (format) {
      case ORACLE:
        buf.append(i > 0 ? " " : "");
        buf.append(chars('-', widths[i]));
        break;
      default:
        buf.append(format == BuiltInOutputFormat.MYSQL || i > 0 ? "+" : "");
        buf.append(chars('-', widths[i] + 2));
      }
    }
    buf.append(mysql ? "+" : "");
    String hyphens = flush(buf);

    switch (format) {
    case MYSQL:
      headerLines.add(hyphens);
      break;
    }

    // Print "| FOO | B |" (mysql)
    // or    "  FOO | B" (postgres)
    // or    "  FOO B" (oracle)
    for (int i = 0; i < n; i++) {
      final String label = metaData.getColumnLabel(i + 1);
      switch (format) {
      case ORACLE:
        buf.append(i > 0 ? " " : "");
        buf.append(i < n - 1 ? pad(label, widths[i], false) : label);
        break;
      case MYSQL:
        buf.append(i > 0 ? " | " : "| ");
        buf.append(pad(label, widths[i], false));
        break;
      case PSQL:
      default:
        buf.append(i > 0 ? " | " : " ");
        buf.append(i < n - 1 ? pad(label, widths[i], false) : label);
        break;
      }
    }
    buf.append(mysql ? " |" : "");
    headerLines.add(flush(buf));
    headerLines.add(hyphens);
    for (String[] row : rows) {
      switch (format) {
      case MYSQL:
        for (int i = 0; i < n; i++) {
          buf.append(i > 0 ? " | " : "| ")
              .append(pad(row[i], widths[i], rights[i]));
        }
        buf.append(" |");
        break;

      case ORACLE:
        for (int i = 0; i < n; i++) {
          buf.append(i > 0 ? " " : "");
          // don't pad the last field if it is left-justified
          final String s = i == n - 1 && !rights[i]
              ? row[i]
              : pad(row[i], widths[i], rights[i]);
          buf.append(s);
        }
        // Trim trailing spaces
        while (buf.length() > 0
            && buf.substring(buf.length() - 1).equals(" ")) {
          buf.setLength(buf.length() - 1);
        }
        break;

      case PSQL:
      default:
        for (int i = 0; i < n; i++) {
          buf.append(i > 0 ? " | " : " ");
          // don't pad the last field if it is left-justified
          final String s = i == n - 1 && !rights[i]
              ? row[i]
              : pad(row[i], widths[i], rights[i]);
          buf.append(s);
        }
        // Trim trailing spaces
        while (buf.length() > 0
               && buf.substring(buf.length() - 1).equals(" ")) {
          buf.setLength(buf.length() - 1);
        }
        break;
      }
      bodyLines.add(flush(buf));
    }
    switch (format) {
    case MYSQL:
      footerLines.add(hyphens);
      // fall through

    case PSQL:
      footerLines.add(
          rows.size() == 1 ? "(1 row)" : "(" + rows.size() + " rows)");
      break;

    case ORACLE:
      if (rows.size() >= ORACLE_FEEDBACK) {
        footerLines.add("");
        footerLines.add(rows.size() + " rows selected.");
      }
    }
    footerLines.add("");
  }

  /** Returns the contents of a StringBuilder and clears it for the next use. */
  private static String flush(StringBuilder buf) {
    final String s = buf.toString();
    buf.setLength(0);
    return s;
  }

  /** Base class for implementations of Command that have one piece of source
   * code. */
  abstract static class SimpleCommand extends AbstractCommand {
    protected final ImmutableList<String> lines;

    SimpleCommand(List<String> lines) {
      this.lines = ImmutableList.copyOf(lines);
    }
  }

  /** Command that sets the current connection. */
  static class UseCommand extends SimpleCommand {
    private final String name;

    UseCommand(List<String> lines, String name) {
      super(lines);
      this.name = name;
    }

    public void execute(Context x, boolean execute) throws Exception {
      x.echo(lines);
      x.use(name);
    }
  }

  /** Command that executes a SQL statement and checks its result. */
  abstract static class CheckResultCommand extends SimpleCommand
      implements Command.ResultChecker {
    protected final boolean output;

    CheckResultCommand(List<String> lines, boolean output) {
      super(lines);
      this.output = output;
    }

    @Override public String describe(Context x) {
      return commandName() + " [sql: " + x.previousSqlCommand().sql + "]";
    }

    public void execute(Context x, boolean execute) throws Exception {
      x.checkResult(execute, output, this);
      x.echo(lines);
    }

    public void checkResultSet(Context x, Throwable resultSetException) {
      if (resultSetException != null) {
        x.stack(resultSetException, x.writer());
      }
    }
  }

  /** Command that executes a SQL statement and checks its result. */
  static class OkCommand extends CheckResultCommand {
    protected final ImmutableList<String> output;

    OkCommand(List<String> lines, ImmutableList<String> output) {
      super(lines, true);
      this.output = output;
    }

    public List<String> getOutput(Context x) {
      return output;
    }
  }

  /** Command that executes a SQL statement and compares the result with a
   * reference database. */
  static class VerifyCommand extends CheckResultCommand {
    VerifyCommand(List<String> lines) {
      super(lines, false);
    }

    public List<String> getOutput(Context x) throws Exception {
      if (x.refConnection() == null) {
        throw new IllegalArgumentException("no reference connection");
      }
      final SqlCommand sqlCommand = x.previousSqlCommand();
      final Statement statement = x.refConnection().createStatement();
      final ResultSet resultSet = statement.executeQuery(sqlCommand.sql);
      try {
        final OutputFormat format =
            (OutputFormat) x.env().apply(Property.OUTPUTFORMAT.propertyName());
        final List<String> headerLines = new ArrayList<String>();
        final List<String> bodyLines = new ArrayList<String>();
        final List<String> footerLines = new ArrayList<String>();
        format.format(resultSet, headerLines, bodyLines, footerLines,
            sqlCommand.sort);
        return ImmutableList.<String>builder()
            .addAll(headerLines)
            .addAll(bodyLines)
            .addAll(footerLines)
            .build();
      } catch (SQLException e) {
        throw new IllegalArgumentException("reference threw", e);
      }
    }
  }

  /** Command that executes a SQL DML command and checks its count. */
  static class UpdateCommand extends SimpleCommand
      implements Command.ResultChecker {
    protected final ImmutableList<String> output;

    UpdateCommand(List<String> lines, ImmutableList<String> output) {
      super(lines);
      this.output = output;
    }

    @Override public String describe(Context x) {
      return commandName() + "[sql: " + x.previousSqlCommand().sql + "]";
    }

    public void execute(Context x, boolean execute) throws Exception {
      final SqlCommand sqlCommand = x.previousSqlCommand();
      x.update(sqlCommand.sql, execute, true, this);
      x.echo(lines);
    }

    public List<String> getOutput(Context x) {
      throw new UnsupportedOperationException();
    }

    public void checkResultSet(Context x, Throwable resultSetException) {
      if (resultSetException != null) {
        x.stack(resultSetException, x.writer());
      }
    }
  }

  /** Command that executes a SQL statement and checks that it throws a given
   * error. */
  static class ErrorCommand extends OkCommand {
    ErrorCommand(List<String> lines, ImmutableList<String> output) {
      super(lines, output);
    }

    @Override public void checkResultSet(Context x,
        Throwable resultSetException) {
      if (resultSetException == null) {
        x.writer().println("Expected error, but SQL command did not give one");
        return;
      }
      if (!output.isEmpty()) {
        final StringWriter sw = new StringWriter();
        x.stack(resultSetException, sw);
        final String actual = squash(sw.toString());
        final String expected = squash(concat(output, false));
        if (actual.contains(expected)) {
          // They gave an expected error, and the actual error does not match.
          // Print the actual error. This will cause a diff.
          for (String line : output) {
            x.writer().println(line);
          }
          return;
        }
      }
      super.checkResultSet(x, resultSetException);
    }

    private String squash(String s) {
      return s.replace("\r\n", "\n") // convert line endings to linux
          .replaceAll("[ \t]+", " ") // convert tabs & multiple spaces to spaces
          .replaceAll("\n ", "\n") // remove spaces at start of lines
          .replaceAll("^ ", "") // or at start of string
          .replaceAll(" \n", "\n") // remove spaces at end of lines
          .replaceAll(" $", "\n"); // or at end of string
    }

    private String concat(List<String> lines, boolean trailing) {
      final StringBuilder buf = new StringBuilder();
      for (String line : lines) {
        buf.append(line).append("\n");
      }
      if (!trailing && buf.length() > 0) {
        buf.setLength(buf.length() - 1);
      }
      return buf.toString();
    }
  }

  /** Command that prints the plan for the current query. */
  static class ExplainCommand extends SimpleCommand {
    private final ImmutableList<String> content;

    ExplainCommand(List<String> lines, ImmutableList<String> content) {
      super(lines);
      this.content = content;
    }

    public String describe(Context x) {
      return commandName() + " [sql: " + x.previousSqlCommand().sql + "]";
    }

    public void execute(Context x, boolean execute) throws Exception {
      if (execute) {
        final SqlCommand sqlCommand = x.previousSqlCommand();
        final Statement statement = x.connection().createStatement();
        try {
          final ResultSet resultSet =
              statement.executeQuery("explain plan for " + sqlCommand.sql);
          try {
            final StringBuilder buf = new StringBuilder();
            while (resultSet.next()) {
              final String line = resultSet.getString(1);
              buf.append(line);
              if (!line.endsWith("\n")) {
                buf.append("\n");
              }
            }
            if (buf.length() == 0) {
              throw new AssertionError("explain returned 0 records");
            }
            x.writer().print(buf);
            x.writer().flush();
          } finally {
            resultSet.close();
          }
        } finally {
          statement.close();
        }
      } else {
        x.echo(content);
      }
      x.echo(lines);
    }
  }

  /** Command that prints the row type for the current query. */
  static class TypeCommand extends SimpleCommand {
    private final ImmutableList<String> content;

    TypeCommand(List<String> lines, ImmutableList<String> content) {
      super(lines);
      this.content = content;
    }

    @Override public String describe(Context x) {
      return commandName() + "[sql: " + x.previousSqlCommand().sql + "]";
    }

    public void execute(Context x, boolean execute) throws Exception {
      if (execute) {
        final SqlCommand sqlCommand = x.previousSqlCommand();
        final PreparedStatement statement =
            x.connection().prepareStatement(sqlCommand.sql);
        try {
          final ResultSetMetaData metaData = statement.getMetaData();
          final StringBuilder buf = new StringBuilder();
          for (int i = 1, n = metaData.getColumnCount(); i <= n; i++) {
            final String label = metaData.getColumnLabel(i);
            final String typeName = metaData.getColumnTypeName(i);
            buf.append(label)
                .append(' ')
                .append(typeName);
            final int precision = metaData.getPrecision(i);
            if (precision > 0) {
              buf.append("(")
                  .append(precision);
              final int scale = metaData.getScale(i);
              if (scale > 0) {
                buf.append(", ")
                    .append(scale);
              }
              buf.append(")");
            }
            final int nullable = metaData.isNullable(i);
            if (nullable == DatabaseMetaData.columnNoNulls) {
              buf.append(" NOT NULL");
            }
            buf.append("\n");
          }
          x.writer().print(buf);
          x.writer().flush();
        } finally {
          statement.close();
        }
      } else {
        x.echo(content);
      }
      x.echo(lines);
    }
  }

  /** Command that executes a SQL statement. */
  public static class SqlCommand extends SimpleCommand {
    /** The query string. */
    public final String sql;
    /** Whether to sort result set before printing. */
    public final boolean sort;

    protected SqlCommand(List<String> lines, String sql, boolean sort) {
      super(lines);
      this.sql = Objects.requireNonNull(sql);
      this.sort = sort;
    }

    public String describe(Context x) {
      return commandName() + "[sql: " + sql + ", sort:" + sort + "]";
    }

    public void execute(Context x, boolean execute) throws Exception {
      if (execute) {
        ((ContextImpl) x).setPreviousSqlCommand(this);
      }
      x.echo(lines);
    }
  }

  /** Creates a connection for a given name.
   *
   * <p>It is kind of a directory service.
   *
   * <p>Caller must close the connection. */
  public interface ConnectionFactory {
    /** Creates a connection to the named database or reference database.
     *
     * <p>Returns null if the database is not known
     * (except {@link UnsupportedConnectionFactory}.
     *
     * @param name Name of the database
     * @param reference Whether we require a real connection or a reference
     *                  connection
     */
    Connection connect(String name, boolean reference) throws Exception;
  }

  /** Property whose value may be set. */
  enum Property {
    OUTPUTFORMAT,
    OTHER;

    String propertyName() {
      return name().toLowerCase();
    }
  }

  /** Command that assigns a value to a property.
   *
   * @see CheckResultCommand
   * @see ExplainCommand
   * @see UpdateCommand
   */
  class SetCommand extends SimpleCommand {
    private final Property property;
    private final String propertyName;
    private final Object value;

    SetCommand(List<String> lines, Property property, String propertyName,
        Object value) {
      super(lines);
      this.property = Objects.requireNonNull(property);
      this.propertyName = Objects.requireNonNull(propertyName);
      Preconditions.checkArgument(property == Property.OTHER
          || propertyName.equals(property.propertyName()));
      this.value = value;
      Preconditions.checkArgument(value == null
          || value instanceof Boolean
          || value instanceof BigDecimal
          || value instanceof String
          || value instanceof BuiltInOutputFormat);
    }

    public void execute(Context x, boolean execute) throws Exception {
      x.echo(lines);
      List<Object> list = map.get(propertyName);
      if (list == null) {
        list = new ArrayList<Object>();
        map.put(propertyName, list);
      }
      if (list.isEmpty() || this instanceof PushCommand) {
        list.add(value);
      } else {
        list.set(list.size() - 1, value);
      }
      config.propertyHandler().onSet(propertyName, value);
    }
  }

  /** As {@link SetCommand}, but saves value so that it can be restored using
   * {@link PopCommand}. */
  class PushCommand extends SetCommand {
    PushCommand(List<String> lines, Property property, String propertyName,
        Object value) {
      super(lines, property, propertyName, value);
    }
  }

  /** As {@link SetCommand}, but saves value so that it can be restored using
   * {@link PopCommand}. */
  class PopCommand extends SimpleCommand {
    private final Property property;
    private final String propertyName;

    PopCommand(List<String> lines, Property property, String propertyName) {
      super(lines);
      this.property = Objects.requireNonNull(property);
      this.propertyName = Objects.requireNonNull(propertyName);
      Preconditions.checkArgument(property == Property.OTHER
          || propertyName.equals(property.propertyName()));
    }

    public void execute(Context x, boolean execute) throws Exception {
      x.echo(lines);
      List<Object> list = map.get(propertyName);
      if (list == null || list.isEmpty()) {
        writer.println("Cannot pop " + propertyName + ": stack is empty");
      } else {
        list.remove(list.size() - 1);
      }
      final Object newValue = env.apply(propertyName);
      config.propertyHandler().onSet(propertyName, newValue);
    }
  }

  /** Command that prints the current value of a property. */
  class ShowCommand extends SimpleCommand {
    private final Property property;
    private final String propertyName;

    ShowCommand(List<String> lines, Property property,
        String propertyName) {
      super(lines);
      this.property = Objects.requireNonNull(property);
      this.propertyName = Objects.requireNonNull(propertyName);
      Preconditions.checkArgument(property == Property.OTHER
          || propertyName.equals(property.propertyName()));
    }

    public void execute(Context x, boolean execute) throws Exception {
      Object value = env.apply(propertyName);
      writer.println(propertyName + " " + value);
      x.echo(lines);
    }
  }

  /** Command that executes a comment. (Does nothing.) */
  class CommentCommand extends SimpleCommand {
    CommentCommand(List<String> lines) {
      super(lines);
    }

    public void execute(Context x, boolean execute) throws Exception {
      x.echo(lines);
    }
  }

  /** Command that disables execution of a block. */
  class IfCommand extends AbstractCommand {
    private final List<String> ifLines;
    private final List<String> endLines;
    private final Command command;
    private final List<String> variables;

    IfCommand(List<String> ifLines, List<String> endLines,
        Command command, List<String> variables) {
      this.variables = ImmutableList.copyOf(variables);
      this.ifLines = ImmutableList.copyOf(ifLines);
      this.endLines = ImmutableList.copyOf(endLines);
      this.command = command;
    }

    public void execute(Context x, boolean execute) throws Exception {
      x.echo(ifLines);
      // Switch to a mode where we don't execute, just echo.
      boolean oldExecute = Quidem.this.execute;
      boolean newExecute;
      if (skip) {
        // If "skip" is set, stay in current (disabled) mode.
        newExecute = oldExecute;
      } else {
        // If "enable" is true, stay in the current mode.
        newExecute = getBoolean(variables);
      }
      command.execute(x, newExecute);
      x.echo(endLines);
    }
  }

  /** Command that switches to a mode where we skip executing the rest of the
   * input. The input is still printed. */
  class SkipCommand extends SimpleCommand {
    SkipCommand(List<String> lines) {
      super(lines);
    }

    public void execute(Context x, boolean execute) throws Exception {
      x.echo(lines);
      // Switch to a mode where we don't execute, just echo.
      // Set "skip" so we don't leave that mode.
      skip = true;
      Quidem.this.execute = false;
    }
  }

  /** Command that executes a list of commands. */
  static class CompositeCommand extends AbstractCommand {
    private final List<Command> commands;

    CompositeCommand(List<Command> commands) {
      this.commands = commands;
    }

    public void execute(Context x, boolean execute) throws Exception {
      // We handle all RuntimeExceptions, all Exceptions, and a limited number
      // of Errors. If we don't understand an Error (e.g. OutOfMemoryError)
      // then we print it out, then abort.
      for (Command command : commands) {
        boolean abort = false;
        Throwable e = null;
        try {
          command.execute(x, execute && x.execute());
        } catch (RuntimeException e0) {
          e = e0;
        } catch (Exception e0) {
          e = e0;
        } catch (AssertionError e0) {
          // We handle a limited number of errors.
          e = e0;
        } catch (Throwable e0) {
          e = e0;
          abort = true;
        }
        if (e != null) {
          command.execute(x, false); // echo the command
          x.writer().println("Error while executing command "
              + command.describe(x));
          x.stack(e, x.writer());
          if (abort) {
            throw (Error) e;
          }
        }
      }
    }
  }

  /** Top of the environment stack. If a property is not defined here (with a
   * non-empty value stack), we look into the environment provided by the
   * caller. */
  private class TopEnv implements Function<String, Object> {
    private final Function<String, Object> env;

    TopEnv(Function<String, Object> env) {
      this.env = env;
    }

    @Override public Object apply(String s) {
      final List<Object> list = map.get(s);
      if (list == null || list.isEmpty()) {
        return env.apply(s);
      }
      return list.get(list.size() - 1);
    }
  }

  /** Called whenever a property's value is changed. */
  public interface PropertyHandler {
    void onSet(String propertyName, Object value);
  }

  /** The information needed to start Quidem. */
  public interface Config {
    Reader reader();
    Writer writer();
    ConnectionFactory connectionFactory();
    CommandHandler commandHandler();
    PropertyHandler propertyHandler();
    Function<String, Object> env();

    /**
     * Returns the maximum number of characters of an error stack to be printed.
     *
     * <p>If negative, does not limit the stack size.
     *
     * <p>The default is {@link #DEFAULT_MAX_STACK_LENGTH}.
     *
     * <p>Useful because it prevents {@code diff} from running out of memory if
     * the error stack is very large. It is preferable to produce a result where
     * you can see the first N characters of each stack trace than to produce
     * no result at all.
     */
    int stackLimit();
  }

  /** Builds a {@link Config}. */
  public static class ConfigBuilder {
    private final Reader reader;
    private final Writer writer;
    private final ConnectionFactory connectionFactory;
    private final CommandHandler commandHandler;
    private final PropertyHandler propertyHandler;
    private final Function<String, Object> env;
    private final int stackLimit;

    private ConfigBuilder(Reader reader, Writer writer,
        ConnectionFactory connectionFactory, CommandHandler commandHandler,
        PropertyHandler propertyHandler, Function<String, Object> env,
        int stackLimit) {
      this.reader = Objects.requireNonNull(reader);
      this.writer = Objects.requireNonNull(writer);
      this.connectionFactory = Objects.requireNonNull(connectionFactory);
      this.commandHandler = commandHandler;
      this.propertyHandler = Objects.requireNonNull(propertyHandler);
      this.env = Objects.requireNonNull(env);
      this.stackLimit = stackLimit;
    }

    /** Returns a {@link Config}. */
    public Config build() {
      return new Config() {
        public Reader reader() {
          return reader;
        }

        public Writer writer() {
          return writer;
        }

        public ConnectionFactory connectionFactory() {
          return connectionFactory;
        }

        public CommandHandler commandHandler() {
          return commandHandler;
        }

        public PropertyHandler propertyHandler() {
          return propertyHandler;
        }

        public Function<String, Object> env() {
          return env;
        }

        public int stackLimit() {
          return stackLimit;
        }
      };
    }

    /** Sets {@link Config#reader}. */
    public ConfigBuilder withReader(Reader reader) {
      return new ConfigBuilder(reader, writer, connectionFactory,
          commandHandler, propertyHandler, env, stackLimit);
    }

    /** Sets {@link Config#writer}. */
    public ConfigBuilder withWriter(Writer writer) {
      return new ConfigBuilder(reader, writer, connectionFactory,
          commandHandler, propertyHandler, env, stackLimit);
    }

    /** Sets {@link Config#propertyHandler}. */
    public ConfigBuilder withPropertyHandler(PropertyHandler propertyHandler) {
      return new ConfigBuilder(reader, writer, connectionFactory,
          commandHandler, propertyHandler, env, stackLimit);
    }

    /** Sets {@link Config#env}. */
    public ConfigBuilder withEnv(Function<String, Object> env) {
      return new ConfigBuilder(reader, writer, connectionFactory,
          commandHandler, propertyHandler, env, stackLimit);
    }

    /** Sets {@link Config#connectionFactory}. */
    public ConfigBuilder withConnectionFactory(
        ConnectionFactory connectionFactory) {
      return new ConfigBuilder(reader, writer, connectionFactory,
          commandHandler, propertyHandler, env, stackLimit);
    }

    /** Sets {@link Config#commandHandler}. */
    public ConfigBuilder withCommandHandler(
        CommandHandler commandHandler) {
      return new ConfigBuilder(reader, writer, connectionFactory,
          commandHandler, propertyHandler, env, stackLimit);
    }

    /** Sets {@link Config#stackLimit}. */
    public ConfigBuilder withStackLimit(int stackLimit) {
      return new ConfigBuilder(reader, writer, connectionFactory,
          commandHandler, propertyHandler, env, stackLimit);
    }
  }

  /** Implementation of {@link Command.Context}. Most methods call through to a
   * corresponding method in {@link Quidem}. */
  private class ContextImpl implements Command.Context {
    public PrintWriter writer() {
      return writer;
    }

    public Connection connection() {
      return connection;
    }

    public Connection refConnection() {
      return refConnection;
    }

    public Function<String, Object> env() {
      return env;
    }

    public void use(String connectionName) throws Exception {
      Quidem.this.use(connectionName);
    }

    public void checkResult(boolean execute, boolean output,
        Command.ResultChecker checker) throws Exception {
      Quidem.this.checkResult(execute, output, checker, this);
    }

    public void update(String sql, boolean execute, boolean output,
        Command.ResultChecker checker) throws Exception {
      Quidem.this.update(sql, execute, output, checker, this);
    }

    public void echo(List<String> lines) {
      Quidem.this.echo(lines);
    }

    public void stack(Throwable e, Writer w) {
      final int stackLimit = config.stackLimit();
      if (stackLimit >= 0) {
        w = new LimitWriter(w, stackLimit);
      }
      final PrintWriter pw;
      if (w instanceof PrintWriter) {
        pw = (PrintWriter) w;
      } else {
        pw = new PrintWriter(w);
      }
      e.printStackTrace(pw);
      if (stackLimit >= 0) {
        assert w instanceof LimitWriter;
        ((LimitWriter) w).ellipsis(" (stack truncated)\n");
      }
    }

    public SqlCommand previousSqlCommand() {
      if (previousSqlCommand != null) {
        return previousSqlCommand;
      }
      throw new AssertionError("no previous SQL command");
    }

    public boolean execute() {
      return Quidem.this.execute;
    }

    private void setPreviousSqlCommand(SqlCommand sqlCommand) {
      Quidem.this.previousSqlCommand = sqlCommand;
    }
  }
}

// End Quidem.java
