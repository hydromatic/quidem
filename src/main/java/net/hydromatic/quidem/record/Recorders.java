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

import net.hydromatic.quidem.ConnectionFactories;
import net.hydromatic.quidem.Quidem;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/** Utilities for recording and playback. */
public abstract class Recorders {
  // utility class
  private Recorders() {
  }

  /** Creates an immutable empty configuration. */
  public static Config config() {
    return ConfigImpl.EMPTY;
  }

  /** Creates a Recorder. */
  public static Recorder create(Config config_) {
    final ConfigImpl config = (ConfigImpl) config_;
    switch (config.mode) {
    case RECORD:
      return new RecordingRecorder(config);
    case PLAY:
      return new PlayingRecorder(config);
    case PASS_THROUGH:
      return new PassThroughRecorder(config);
    default:
      throw new AssertionError(config.mode);
    }
  }

  /** Implementation of {@link Config}. */
  private static class ConfigImpl implements Config {
    static final ConfigImpl EMPTY =
        new ConfigImpl(null, Mode.PLAY, ConnectionFactories.unsupported());

    final @Nullable File file;
    final Mode mode;
    final Quidem.ConnectionFactory connectionFactory;

    private ConfigImpl(@Nullable File file, Mode mode,
        Quidem.ConnectionFactory connectionFactory) {
      this.file = file;
      this.mode = requireNonNull(mode);
      this.connectionFactory = requireNonNull(connectionFactory);
    }

    @Override public Config withFile(File file) {
      return new ConfigImpl(file, mode, connectionFactory);
    }

    @Override public Config withMode(Mode mode) {
      return new ConfigImpl(file, mode, connectionFactory);
    }

    @Override public Config withConnectionFactory(
        Quidem.ConnectionFactory connectionFactory) {
      return new ConfigImpl(file, mode, connectionFactory);
    }
  }

  /** Abstract implementation of {@link Recorder}. */
  private abstract static class RecorderImpl implements Recorder {
    protected final ConfigImpl config;

    RecorderImpl(ConfigImpl config) {
      this.config = requireNonNull(config, "config");
    }

    @Override public void close() {
    }
  }

  /** Recorder that has a file. */
  private abstract static class RecorderWithFile extends RecorderImpl {
    protected final File file;

    RecorderWithFile(ConfigImpl config) {
      super(config);
      if (config.file == null) {
        throw new IllegalStateException(
            format("mode '%s' requires a file", config.mode));
      }
      this.file = requireNonNull(config.file);
    }
  }

  /** Recorder in PLAY mode. */
  private static class PlayingRecorder extends RecorderWithFile {
    final SortedMap<String, Section> sectionsByName;
    final ImmutableMap<StringPair, Section> sectionsBySql;

    PlayingRecorder(ConfigImpl config) {
      super(config);
      final ImmutableSortedMap.Builder<String, Section> nameBuilder =
          ImmutableSortedMap.naturalOrder();
      final ImmutableMap.Builder<StringPair, Section> sqlBuilder =
          ImmutableMap.builder();
      populate(file, section -> {
        nameBuilder.put(section.name, section);
        sqlBuilder.put(new StringPair(section.db, section.sql), section);
      });
      sectionsByName = nameBuilder.build();
      sectionsBySql = sqlBuilder.build();
    }

    private void populate(File file, Consumer<Section> consumer) {
      try (FileReader fr = new FileReader(file);
           BufferedReader br = new BufferedReader(fr)) {
        final SectionBuilder sectionBuilder = new SectionBuilder();
        sectionBuilder.parse(br, consumer);
      } catch (IOException e) {
        throw new RuntimeException(
            format("file parsing file '%s'", file), e);
      }
    }

    @Override public void executeQuery(String db, String name, String sql,
        Consumer<ResultSet> consumer) {
      final Section section = sectionsBySql.get(new StringPair(db, sql));
      if (section == null) {
        throw new IllegalArgumentException(
            format("sql [%s] is not in recording", sql));
      }
      section.toResultSet(consumer);
    }
  }

  /** Recorder in RECORD mode. */
  private static class RecordingRecorder extends RecorderWithFile {
    final SortedMap<String, Section> sections = new TreeMap<>();

    RecordingRecorder(ConfigImpl config) {
      super(config);
    }

    @Override public void executeQuery(String db, String name, String sql,
        Consumer<ResultSet> consumer) {
      try (Connection connection =
               config.connectionFactory.connect(db, false)) {
        if (connection == null) {
          throw new IllegalStateException("unknown connection " + db);
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
          final StringBuilder b = new StringBuilder();

          // Generate the string fragment representing the result set
          JdbcUtils.write(b, resultSet);
          final String resultSetString = b.toString();
          b.setLength(0);

          // Put the segment into the table to be written on close, and unparse
          // the result set string into the consumer's result set.
          final Section section =
              new Section(-1, name, db, sql, resultSetString);
          sections.put(name, section);
          section.toResultSet(consumer);
        }
      } catch (Exception e) {
        throw new RuntimeException(format("while executing query [%s]", sql),
            e);
      }
    }

    @Override public void close() {
      try (FileOutputStream fos = new FileOutputStream(file);
           OutputStreamWriter osw =
               new OutputStreamWriter(fos, Charsets.ISO_8859_1);
           BufferedWriter w = new BufferedWriter(osw);
           PrintWriter pw = new PrintWriter(w)) {
        sections.forEach((name, segment) -> segment.send(pw));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  /** Recorder in PASS_TROUGH mode. */
  private static class PassThroughRecorder extends RecorderImpl {
    PassThroughRecorder(ConfigImpl config) {
      super(config);
    }

    @Override public void executeQuery(String db, String name, String sql,
        Consumer<ResultSet> consumer) {
      try (Connection connection =
               config.connectionFactory.connect(db, false)) {
        if (connection == null) {
          throw new IllegalStateException("unknown connection " + db);
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
          consumer.accept(resultSet);
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  /** Builder for {@link Section}. */
  private static class SectionBuilder {
    int offset; // offset within file
    int sectionStart;
    String name;
    String db;
    String sql;
    String result;

    Section build() {
      return new Section(offset, name, db, sql, result);
    }

    private void clear() {
      name = db = sql = result = null;
      sectionStart = -1;
    }

    void end(Consumer<Section> consumer) {
      consumer.accept(build());
      clear();
    }

    public void parse(BufferedReader br, Consumer<Section> consumer)
        throws IOException {
      // File structure:
      //  preamble
      //  # StartTest: T1
      //  !use db1
      //  query1;
      //  result1
      //  !ok
      //  # EndTest: T1
      //  # StartTest: T2
      //  !use db2
      //  query2;
      //  result2
      //  !ok
      //  # EndTest: T2
      //  postamble
      final StringBuilder b = new StringBuilder();
      for (;;) {
        String line = br.readLine();
        if (line == null) {
          break;
        }
        offset += line.length() + 1;
        if (line.startsWith("# EndTest: ")) {
          final String sectionName =
              line.substring("# EndTest: ".length()).trim();
          if (!Objects.equals(sectionName, name)) {
            throw new IllegalArgumentException(
                format("end '%s' does not match start '%s'",
                    sectionName, name));
          }
          end(consumer);
          continue;
        }
        if (line.startsWith("# StartTest: ")) {
          if (name != null) {
            end(consumer);
          }
          name = line.substring("# StartTest: ".length()).trim();
          continue;
        }
        if (line.startsWith("!use ")) {
          db = line.substring("!use ".length()).trim();
          continue;
        }
        if (line.endsWith(";")) {
          // Append the line except for the ';'
          b.append(line, 0, line.length() - 1);
          sql = b.toString();
          b.setLength(0);
          continue;
        }
        if (line.equals("!ok")) {
          result = b.toString();
          b.setLength(0);
          continue;
        }
        // We are in the middle of a query or a result. Append the line.
        b.append(line);
        b.append("\n");
      }
    }
  }

  /** The information that defines a test: query and result. */
  private static class Section {
    /** Offset within the file. */
    final int offset;
    /** Section name. */
    final String name;
    /** Database name. */
    final String db;
    /** SQL query. */
    final String sql;
    /** Query result string. */
    final String result;

    private Section(int offset, String name, String db, String sql,
        String result) {
      this.offset = offset;
      this.name = name;
      this.db = db;
      this.sql = sql;
      this.result = result;
    }

    public void send(PrintWriter pw) {
      // Generate the string fragment representing the test header,
      // SQL query, result set, and test footer.
      pw.print("# StartTest: " + name + "\n"
          + "!use " + db + "\n"
          + sql + ";\n"
          + result
          + "!ok\n"
          + "# EndTest: " + name + "\n");
    }

    public void toResultSet(Consumer<ResultSet> consumer) {
      try {
        JdbcUtils.read(result, consumer);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  /** Immutable pair of non-null strings. */
  static class StringPair {
    final String left;
    final String right;

    StringPair(String left, String right) {
      this.left = left;
      this.right = right;
    }

    @Override public String toString() {
      return left + ":" + right;
    }

    @Override public int hashCode() {
      return left.hashCode() * 37 + right.hashCode();
    }

    @Override public boolean equals(Object obj) {
      return obj == this
          || obj instanceof StringPair
          && left.equals(((StringPair) obj).left)
          && right.equals(((StringPair) obj).right);
    }
  }
}

// End Recorders.java
