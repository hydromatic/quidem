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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.LineNumberReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Parses command-line arguments.
 */
class Launcher {
  private static final String[] USAGE_LINES = {
      "Usage: quidem argument... inFile outFile",
      "",
      "Arguments:",
      "  --help",
      "           Print usage",
      "  --db name url user password",
      "           Add a database to the connection factory",
      "  --var name value",
      "           Assign a value to a variable",
      "  --factory className",
      "           Define a connection factory (must implement interface",
      "        " + Quidem.ConnectionFactory.class.getCanonicalName() + ")",
      "  --command-handler className",
      "           Define a command-handler (must implement interface",
      "        " + CommandHandler.class.getCanonicalName() + ")",
  };

  private final List<String> args;
  private final PrintWriter out;

  Launcher(List<String> args, PrintWriter out) {
    this.args = args;
    this.out = out;
  }

  /** Creates a launcher, parses command line arguments, and runs Quidem.
   *
   * <p>Similar to a {@code main} method, but never calls
   * {@link System#exit(int)}.
   *
   * @param out Writer to which to print output
   * @param args Command-line arguments
   *
   * @return Operating system error code (0 = success, 1 = invalid arguments,
   * 2 = other error)
   */
  static int main2(PrintWriter out, PrintWriter err, List<String> args) {
    try {
      final Launcher launcher = new Launcher(args, out);
      final Quidem quidem;
      try {
        quidem = launcher.parse();
      } catch (ParseException e) {
        return e.code;
      }
      quidem.execute();
      return 0;
    } catch (Throwable e) {
      out.flush();
      e.printStackTrace(err);
      return 2;
    } finally {
      out.flush();
      err.flush();
    }
  }

  /** Parses the command line arguments, and returns a {@link Quidem} instance.
   *
   * @throws ParseException if command line arguments were invalid or usage
   * was requested
   */
  public Quidem parse() throws ParseException {
    final List<Quidem.ConnectionFactory> factories = new ArrayList<>();
    final List<CommandHandler> commandHandlers = new ArrayList<>();
    final Map<String, String> envMap = new LinkedHashMap<>();
    int i;
    for (i = 0; i < args.size();) {
      String arg = args.get(i);
      if (arg.equals("--help")) {
        usage();
        throw new ParseException(0);
      }
      if (arg.equals("--db")) {
        if (i + 4 >= args.size()) {
          throw error("Insufficient arguments for --db");
        }
        final String name = args.get(i + 1);
        final String url = args.get(i + 2);
        final String user = args.get(i + 3);
        final String password = args.get(i + 4);
        factories.add(ConnectionFactories.simple(name, url, user, password));
        i += 5;
        continue;
      }
      if (arg.equals("--var")) {
        if (i + 3 >= args.size()) {
          throw error("Insufficient arguments for --var");
        }
        final String name = args.get(i + 1);
        final String value = args.get(i + 2);
        envMap.put(name, value);
        i += 3;
        continue;
      }
      if (arg.equals("--factory")) {
        if (i + 1 >= args.size()) {
          throw error("Insufficient arguments for --factory");
        }
        final String className = args.get(i + 1);
        final Class<?> factoryClass;
        try {
          factoryClass = Class.forName(className);
        } catch (ClassNotFoundException e) {
          throw error("Factory class " + className + " not found");
        }
        Quidem.ConnectionFactory factory;
        try {
          factory = (Quidem.ConnectionFactory) factoryClass.newInstance();
        } catch (InstantiationException e) {
          throw error("Error instantiating factory class " + className);
        } catch (IllegalAccessException e) {
          throw error("Error instantiating factory class " + className);
        } catch (ClassCastException e) {
          throw error("Error instantiating factory class " + className);
        }
        factories.add(factory);
        i += 2;
        continue;
      }
      if (arg.equals("--command-handler")) {
        if (i + 1 >= args.size()) {
          throw error("Insufficient arguments for --command-handler");
        }
        final String className = args.get(i + 1);
        final Class<?> factoryClass;
        try {
          factoryClass = Class.forName(className);
        } catch (ClassNotFoundException e) {
          throw error("Factory class " + className + " not found");
        }
        CommandHandler commandHandler;
        try {
          commandHandler = (CommandHandler) factoryClass.newInstance();
        } catch (InstantiationException e) {
          throw error("Error instantiating command-handler class " + className);
        } catch (IllegalAccessException e) {
          throw error("Error instantiating command-handler class " + className);
        } catch (ClassCastException e) {
          throw error("Error instantiating command-handler class " + className);
        }
        commandHandlers.add(commandHandler);
        i += 2;
        continue;
      }
      break;
    }
    if (i + 2 > args.size()) {
      throw error("Insufficient arguments: need inFile and outFile");
    }
    final File inFile = new File(args.get(i));
    final File outFile = new File(args.get(i + 1));
    final Reader reader;
    try {
      reader = new LineNumberReader(new FileReader(inFile));
    } catch (FileNotFoundException e) {
      throw new RuntimeException("Error opening input " + inFile, e);
    }
    final Writer writer;
    try {
      writer = new FileWriter(outFile);
    } catch (IOException e) {
      throw new RuntimeException("Error opening output " + outFile, e);
    }

    factories.add(ConnectionFactories.unsupported());
    final Quidem.ConnectionFactory connectionFactory =
        ConnectionFactories.chain(factories);
    final ChainingCommandHandler commandHandler =
        new ChainingCommandHandler(commandHandlers);

    final Function<String, Object> env = envMap::get;
    final Quidem.Config config = Quidem.configBuilder()
        .withReader(reader)
        .withWriter(writer)
        .withEnv(env)
        .withConnectionFactory(connectionFactory)
        .withCommandHandler(commandHandler)
        .build();
    return new Quidem(config);
  }

  private ParseException error(String error) {
    out.println(error);
    out.println();
    usage();
    return new ParseException(1);
  }

  private void usage() {
    for (String line : USAGE_LINES) {
      out.println(line);
    }
  }

  static class ParseException extends Exception {
    private final int code;

    ParseException(int code) {
      super();
      this.code = code;
    }
  }
}

// End Launcher.java
