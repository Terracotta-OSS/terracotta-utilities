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
 * Asserts that a provided {@link Throwable} holds a {@code cause} of a given type.
 */
public class ThrowableCauseMatcher<T extends Throwable> extends TypeSafeDiagnosingMatcher<T> {

  private final Matcher<? extends Throwable> causeMatcher;

  @SuppressWarnings("WeakerAccess")
  public ThrowableCauseMatcher(Matcher<? extends Throwable> causeMatcher) {
    this.causeMatcher = causeMatcher;
  }

  @Override
  protected boolean matchesSafely(T item, Description mismatchDescription) {
    Throwable cause = item.getCause();
    if (!causeMatcher.matches(cause)) {
      causeMatcher.describeMismatch(cause, mismatchDescription);
      return false;
    }
    return true;
  }

  @Override
  public void describeTo(Description description) {
    description.appendText("with cause ").appendDescriptionOf(causeMatcher);
  }

  /**
   * Returns a matcher asserting a {@code Throwable} has a cause successfully passing the provided matcher.
   * @param matcher the cause matcher
   * @param <T> the cause matcher type
   * @return a new {@code Matcher}
   */
  @SuppressWarnings("WeakerAccess")
  public static <T extends Throwable> Matcher<T> causedBy(Matcher<? extends Throwable> matcher) {
    return new ThrowableCauseMatcher<>(matcher);
  }
}
