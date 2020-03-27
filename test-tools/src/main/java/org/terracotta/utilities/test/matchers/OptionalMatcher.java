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

import org.hamcrest.FeatureMatcher;
import org.hamcrest.Matcher;

import java.util.Optional;

public class OptionalMatcher<T> extends FeatureMatcher<Optional<T>, T> {

  public OptionalMatcher(Matcher<? super T> subMatcher) {
    super(subMatcher, "an optional with value", "value");
  }

  @Override
  protected T featureValueOf(Optional<T> actual) {
    return actual.orElseThrow(AssertionError::new);
  }

  public static <T> Matcher<Optional<T>> optionalThat(Matcher<? super T> matcher) {
    return new OptionalMatcher<>(matcher);
  }
}
