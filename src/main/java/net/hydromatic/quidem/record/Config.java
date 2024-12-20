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

import net.hydromatic.quidem.Quidem;

import java.io.File;

/** Configuration for recordings.
 *
 * <p>Created via {@link Recorders#config()}.
 */
public interface Config {
  Config withFile(File file);
  Config withMode(Mode mode);
  Config withConnectionFactory(Quidem.ConnectionFactory connectionFactory);
}

// End Config.java
