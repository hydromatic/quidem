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

import java.sql.ResultSet;
import java.util.function.Consumer;

/** Object that can execute queries and return their response.
 *
 * <p>Depending on its {@link Mode}, the recorder executes each query on a
 * backing database, generating a recording file as it goes, or answers each
 * query by consulting an existing recording.
 *
 * <p>The modes allow you to run compliance tests in environments where the
 * backend database is not available.
 *
 * <p>Created via {@link Recorders#config()}.
 */
public interface Recorder extends AutoCloseable {
  /** Executes a query and calls {@code consumer} with the {@link ResultSet}
   * containing the results of the query. */
  void executeQuery(String db, String name, String sql,
      Consumer<ResultSet> consumer);

  /** {@inheritDoc}
   *
   * <p>Unlike the method in the base class,
   * never throws an unchecked exception.
   */
  @Override void close();
}

// End Config.java
