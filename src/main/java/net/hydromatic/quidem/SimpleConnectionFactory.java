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

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static java.util.Objects.requireNonNull;

/** Connection factory that recognizes a single name,
 * and does not offer a reference connection.
 *
 * <p>The first attempt to create a connection will verify the connection and,
 * if it is not valid, populate it. */
class SimpleConnectionFactory implements Quidem.ConnectionFactory {
  private final String name;
  private final String url;
  private final String user;
  private final String password;
  private final Predicate<Connection> verifier;
  private final Consumer<Connection> loader;
  private final ReentrantLock lock = new ReentrantLock();
  private int verifyCount = 0;

  SimpleConnectionFactory(String name, String url, String user,
      String password, Predicate<Connection> verifier,
      Consumer<Connection> loader) {
    this.name = requireNonNull(name);
    this.url = requireNonNull(url);
    this.user = requireNonNull(user);
    this.password = requireNonNull(password);
    this.verifier = requireNonNull(verifier);
    this.loader = requireNonNull(loader);
  }

  @Override public @Nullable Connection connect(String name, boolean reference)
      throws Exception {
    if (reference || !name.equals(this.name)) {
      return null;
    }
    final Connection connection =
        DriverManager.getConnection(url, user, password);
    try {
      // Critical section while we verify and populate the database. The first
      // thread to enter this section will verify and, if necessary, populate.
      //
      // Later threads assume that the database is correctly populated. If
      // populate failed, those threads will seem to succeed, but the first
      // thread will have given an error.
      lock.lock();
      if (verifyCount++ == 0) {
        // Database has not been verified. Verify it, and if it's not valid,
        // populate it.
        final boolean valid = verifier.test(connection);
        if (!valid) {
          // Database is not valid. Try to load it.
          loader.accept(connection);
        }
      }
      return connection;
    } finally {
      lock.unlock();
    }
  }
}

// End SimpleConnectionFactory.java
