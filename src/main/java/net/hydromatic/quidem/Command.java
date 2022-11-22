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

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.PrintWriter;
import java.io.Writer;
import java.sql.Connection;
import java.util.List;
import java.util.function.Function;

/** Command.
 *
 * @see AbstractCommand
 * @see CommandHandler
 */
public interface Command {
  /** Returns a string describing this command.
   *
   * <p>For example: "OkCommand [sql: select * from emp]"
   * or "SkipCommand".
   *
   * @param x Execution context
   */
  String describe(Context x);

  /** Executes this command.
   *
   * @param x Execution context
   * @param execute Whether to execute (false if execution is disabled, say by
   * an 'if')
   *
   * @throws Exception if command fails
   */
  void execute(Context x, boolean execute) throws Exception;

  /** Merges this command with the previous command.
   * Returns null if merging is not possible. */
  @Nullable default Command merge(Command previousCommand) {
    return null;
  }

  /** Execution context for a command. */
  interface Context {
    PrintWriter writer();

    Connection connection();

    Connection refConnection();

    Function<String, Object> env();

    void use(String connectionName) throws Exception;

    void checkResult(boolean execute, boolean output, ResultChecker checker)
        throws Exception;

    void update(String sql, boolean execute, boolean output,
        ResultChecker checker) throws Exception;

    void stack(Throwable resultSetException, Writer writer);

    void echo(List<String> lines);

    Quidem.SqlCommand previousSqlCommand();

    boolean execute();
  }

  interface ResultChecker {
    List<String> getOutput(Context x) throws Exception;
    void checkResultSet(Context x, Throwable resultSetException);
  }
}

// End Command.java
