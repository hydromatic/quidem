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
package net.hydromatic.quidem.util;

import com.google.common.base.Charsets;
import com.google.common.io.Files;

import org.apache.calcite.util.Sources;
import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.fail;

import static java.util.Objects.requireNonNull;

/** Utilities for writing Quidem tests. */
public class TestUtils {
  private TestUtils() {
  }

  /** Returns the root directory of the source tree. */
  public static File getBaseDir(Class<?> klass) {
    // Algorithm:
    // 1) Find location of TestUtil.class
    // 2) Climb via getParentFile() until we detect pom.xml
    // 3) It means we've got BASE/testkit/pom.xml, and we need to get BASE
    final URL resource = klass.getResource(klass.getSimpleName() + ".class");
    final File classFile =
        Sources.of(requireNonNull(resource, "resource")).file();

    File file = classFile.getAbsoluteFile();
    for (int i = 0; i < 42; i++) {
      if (isProjectDir(file)) {
        // Ok, file == BASE/testkit/
        break;
      }
      file = file.getParentFile();
    }
    if (!isProjectDir(file)) {
      fail("Could not find pom.xml, build.gradle.kts or gradle.properties. "
          + "Started with " + classFile.getAbsolutePath()
          + ", the current path is " + file.getAbsolutePath());
    }
    return file;
  }

  private static boolean isProjectDir(File dir) {
    return new File(dir, "pom.xml").isFile()
        || new File(dir, "build.gradle.kts").isFile()
        || new File(dir, "gradle.properties").isFile();
  }

  /** Returns a matcher that concatenates an array of strings into a multi-line
   * string. */
  public static Matcher<String> isLines(String... lines) {
    final StringBuilder b = new StringBuilder();
    for (String line : lines) {
      b.append(line).append("\n");
    }
    return is(b.toString());
  }

  /** Returns a matcher that checks the string contents of a file. */
  public static Matcher<? super File> hasContents(Matcher<String> matcher) {
    return new CustomTypeSafeMatcher<File>("file contents") {
      @Override protected void describeMismatchSafely(File file,
          Description mismatchDescription) {
        mismatchDescription.appendText("file has contents [")
            .appendText(fileContents(file))
            .appendText("]");
      }

      @Override protected boolean matchesSafely(File file) {
        return matcher.matches(fileContents(file));
      }

      String fileContents(File file) {
        try {
          return Files.asCharSource(file, Charsets.ISO_8859_1).read();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    };
  }

  /** Supplies a sequence of unique file names in a temporary directory that
   * will be deleted when the JVM finishes. */
  public static class FileFont {
    private final File file;
    private final AtomicInteger i = new AtomicInteger();

    /** Creates a FileFont. */
    public FileFont(String dirName) {
      try {
        Path p = java.nio.file.Files.createTempDirectory(dirName);
        file = p.toFile();
        file.deleteOnExit();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    /** Generates a unique file in the temporary directory.
     *
     * <p>If you call {@code file("foo", ".iq")}
     * the file name might be something like
     * "{@code /tmp/quidem-record-test123/foo_3.iq}". */
    public File file(String name, String suffix) {
      return new File(file, name + '_' + i.getAndIncrement() + suffix);
    }
  }
}

// End TestUtils.java
