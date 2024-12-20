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

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.Connection;
import java.util.List;

/** Connection factory that tries several factories, returning a connection
 * from the first that is able to connect. */
class ChainingConnectionFactory implements Quidem.ConnectionFactory {
  private final List<Quidem.ConnectionFactory> factories;

  ChainingConnectionFactory(
      Iterable<? extends Quidem.ConnectionFactory> factories) {
    this.factories = ImmutableList.copyOf(factories);
  }

  @Override public @Nullable Connection connect(String name, boolean reference)
      throws Exception {
    for (Quidem.ConnectionFactory factory : factories) {
      Connection c = factory.connect(name, reference);
      if (c != null) {
        return c;
      }
    }
    return null;
  }
}

// End ChainingConnectionFactory.java
