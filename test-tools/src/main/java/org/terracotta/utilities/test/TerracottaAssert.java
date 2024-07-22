/*
 * Copyright 2020 Terracotta, Inc., a Software AG company.
 * Copyright Super iPaaS Integration LLC, an IBM Company 2024
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terracotta.utilities.test;

import org.hamcrest.Matcher;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.terracotta.utilities.test.matchers.ThrowsMatcher.threw;

public final class TerracottaAssert {

  private TerracottaAssert() {
    //no instances please
  }

  /**
   * Asserts the supplied {@link Task} throws an exception matching the {@link Matcher} provided.
   * @param task the {@code Task} to execute
   * @param matcher the Hamcrest {@code Matcher} to apply to the {@code Throwable} thrown by {@code task}
   * @deprecated replaced by {@link org.terracotta.utilities.test.matchers.ThrowsMatcher#threw(Matcher)}
   */
  @Deprecated
  public static void assertThrows(Task<?> task, Matcher<Throwable> matcher) {
    assertThat(task::execute, threw(matcher));
  }

  /**
   * A task that returns a result and may throw a {@link Throwable}.  Similar to
   * {@link java.util.concurrent.Callable Callable}.
   * @param <R> the return type
   * @deprecated replaced by {@link org.terracotta.utilities.test.matchers.ThrowsMatcher.Task}
   */
  @FunctionalInterface @Deprecated
  public interface Task<R> {
    R execute() throws Throwable;
  }
}
