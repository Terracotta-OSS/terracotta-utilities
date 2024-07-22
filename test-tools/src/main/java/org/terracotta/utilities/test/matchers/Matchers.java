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
package org.terracotta.utilities.test.matchers;

import org.hamcrest.Matcher;
import org.terracotta.utilities.test.TerracottaAssert;

import java.util.Optional;

public class Matchers {

  /**
   * Returns a matcher asserting a {@code Throwable} has a cause successfully passing the provided matcher.
   * <p>
   * Use in conjunction with {@link TerracottaAssert#assertThrows(TerracottaAssert.Task, Matcher)}
   *
   * @param matcher the cause matcher
   * @param <T> the cause matcher type
   * @return a new {@code Matcher}
   */
  public static <T extends Throwable> Matcher<T> causedBy(Matcher<? extends Throwable> matcher) {
    return ThrowableCauseMatcher.causedBy(matcher);
  }

  @SuppressWarnings("varargs") @SafeVarargs
  public static <T> Matcher<Iterable<? extends T>> containsSequencesInOrder(Matcher<Iterable<? extends T>>... sequences) {
    return SequenceMatcher.containsSequencesInOrder(sequences);
  }

  public static Matcher<byte[]> byteArrayWithSize(int size) {
    return PrimitiveArrayMatching.byteArrayWithSize(size);
  }

  public static Matcher<byte[]> byteArrayContaining(byte ... values) {
    return PrimitiveArrayMatching.byteArrayContaining(values);
  }

  public static <T> Matcher<Optional<T>> optionalThat(Matcher<? super T> matcher) {
    return OptionalMatcher.optionalThat(matcher);
  }
}
