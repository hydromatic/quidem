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

import com.google.common.collect.ImmutableList;

import java.sql.Connection;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Utilities for {@link net.hydromatic.quidem.Quidem.ConnectionFactory}. */
public abstract class ConnectionFactories {
  private ConnectionFactories() {
  }

  /** Creates a connection factory that always throws. */
  public static Quidem.ConnectionFactory unsupported() {
    return new UnsupportedConnectionFactory();
  }

  /** Creates a connection factory that uses simple JDBC credentials. */
  public static Quidem.ConnectionFactory simple(String name, String url,
      String user, String password) {
    return simple(name, url, user, password, connection -> true,
        connection -> { });
  }

  /** Creates a connection factory that uses simple JDBC credentials
   * and verifies/loads connections. */
  public static Quidem.ConnectionFactory simple(String name, String url,
      String user, String password, Predicate<Connection> verifier,
      Consumer<Connection> loader) {
    return new SimpleConnectionFactory(name, url, user, password, verifier,
        loader);
  }

  /** Creates a connection factory that tries each of a list of factories in
   * turn. */
  public static Quidem.ConnectionFactory chain(
      Iterable<? extends Quidem.ConnectionFactory> factories) {
    return new ChainingConnectionFactory(
        ImmutableList.copyOf(factories));
  }

  /** Creates a connection factory that tries each of an array of factories in
   * turn. */
  public static Quidem.ConnectionFactory chain(
      Quidem.ConnectionFactory... connectionFactories) {
    return chain(ImmutableList.copyOf(connectionFactories));
  }

  /** Creates a connection factory that returns {@code null} for any requested
   * database. */
  public static Quidem.ConnectionFactory empty() {
    return new ChainingConnectionFactory(ImmutableList.of());
  }
}

// End ConnectionFactories.java
