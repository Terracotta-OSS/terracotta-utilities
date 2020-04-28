/*
 * Copyright 2020 Terracotta, Inc., a Software AG company.
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
package org.terracotta.utilities.test.matchers;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;

/**
 * A matcher of exceptions thrown by executable 'tasks'.
 */
public class ThrowsMatcher extends TypeSafeDiagnosingMatcher<ThrowsMatcher.Task> {

  private final Matcher<? extends Throwable> matcher;

  /**
   * Constructs a {@link Matcher} of tasks that throw a {@code Throwable} matching {@code matcher}.
   *
   * @param matcher a throwable matcher
   */
  public ThrowsMatcher(Matcher<? extends Throwable> matcher) {
    this.matcher = matcher;
  }

  @Override
  protected boolean matchesSafely(Task task, Description mismatch) {
    try {
      task.run();
      mismatch.appendText("the task completed normally");
      return false;
    } catch (Throwable t) {
      if (matcher.matches(t)) {
        return true;
      } else {
        matcher.describeMismatch(t, mismatch.appendText("the thrown "));
        return false;
      }
    }
  }

  @Override
  public void describeTo(Description description) {
    description.appendText("a task that throws ").appendDescriptionOf(matcher);
  }

  /**
   * Returns a {@link Matcher} of tasks that throw a {@code Throwable} matching {@code matcher}.
   *
   * @param matcher a throwable matcher
   * @return a task throwing matcher
   */
  public static Matcher<Task> threw(Matcher<? extends Throwable> matcher) {
    return new ThrowsMatcher(matcher);
  }

  /**
   * An executable task that throws {@link Throwable}.
   */
  @FunctionalInterface
  public interface Task {
    void run() throws Throwable;
  }
}

