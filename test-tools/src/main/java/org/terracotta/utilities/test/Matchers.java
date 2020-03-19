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
package org.terracotta.utilities.test;

import org.hamcrest.Description;
import org.hamcrest.FeatureMatcher;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.hamcrest.collection.ArrayMatching;

import java.util.Optional;
import java.util.stream.StreamSupport;

import static java.util.Arrays.copyOfRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsEmptyIterable.emptyIterable;
import static org.hamcrest.core.IsEqual.equalTo;

public class Matchers {

  /**
   * Returns a matcher asserting a {@code Throwable} has a cause successfully passing the provided matcher.
   * <p>
   * Use in conjunction with {@link #assertThrows(Task, Matcher)}.
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
    if (sequences.length == 0) {
      return emptyIterable();
    } else if (sequences.length == 1) {
      return sequences[0];
    } else {
      Matcher<Iterable<? extends T>> headMatcher = sequences[0];
      Matcher<Iterable<? extends T>> tailMatcher = containsSequencesInOrder(copyOfRange(sequences, 1, sequences.length));

      return new TypeSafeMatcher<Iterable<? extends T>>() {
        @Override
        protected boolean matchesSafely(Iterable<? extends T> item) {
          for (int i = 0; ; i++) {
            Iterable<? extends T>[] iterables = splitIterable(item, i);
            if (headMatcher.matches(iterables[0]) && tailMatcher.matches(iterables[1])) {
              return true;
            } else if (!iterables[1].iterator().hasNext()) {
              return false;
            }
          }
        }

        @Override
        public void describeTo(Description description) {
          description.appendDescriptionOf(headMatcher).appendText(", followed-by ").appendDescriptionOf(tailMatcher);
        }
      };
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> Iterable<T>[] splitIterable(Iterable<T> iterable, int headLength) {
    Iterable<T> it1 = () -> StreamSupport.stream(iterable.spliterator(), false).limit(headLength).iterator();
    Iterable<T> it2 = () -> StreamSupport.stream(iterable.spliterator(), false).skip(headLength).iterator();
    return (Iterable<T>[]) new Iterable<?>[] {it1, it2};
  }

  /**
   * Asserts the supplied {@link Task} throws an exception matching the {@link Matcher} provided.
   * @param task the {@code Task} to execute
   * @param matcher the Hamcrest {@code Matcher} to apply to the {@code Throwable} thrown by {@code task}
   */
  public static void assertThrows(Task<?> task, Matcher<Throwable> matcher) {
    try {
      task.execute();
    } catch (Throwable t) {
      assertThat(t, matcher);
      return;
    }
    assertThat(null, matcher);
  }

  /**
   * A task that returns a result and may throw a {@link Throwable}.  Similar to
   * {@link java.util.concurrent.Callable Callable}.
   * @param <R> the return type
   */
  @FunctionalInterface
  public interface Task<R> {
    R execute() throws Throwable;
  }

  public static Matcher<byte[]> arrayWithSize(int size) {
    return new FeatureMatcher<byte[], Integer>(equalTo(size), "a byte array with size","array size") {
      @Override
      protected Integer featureValueOf(byte[] actual) {
        return actual.length;
      }
    };
  }

  public static Matcher<byte[]> arrayContaining(byte ... values) {
    Matcher<Byte[]> wrappedMatcher = ArrayMatching.arrayContaining(wrap(values));
    return new TypeSafeMatcher<byte[]>() {

      @Override
      protected void describeMismatchSafely(byte[] item, Description mismatchDescription) {
        wrappedMatcher.describeMismatch(wrap(item), mismatchDescription);
      }

      @Override
      public void describeTo(Description description) {
        wrappedMatcher.describeTo(description);
      }

      @Override
      protected boolean matchesSafely(byte[] item) {
        return wrappedMatcher.matches(wrap(item));
      }
    };
  }

  private static Byte[] wrap(byte[] primitive) {
    Byte[] wrapped = new Byte[primitive.length];
    for (int i = 0; i < wrapped.length; i++) {
      wrapped[i] = primitive[i];
    }
    return wrapped;
  }

  public static <T> Matcher<Optional<T>> optionalThat(Matcher<? super T> matcher) {
    return new FeatureMatcher<Optional<T>, T>(matcher, "an optional with value", "value") {

      @Override
      protected T featureValueOf(Optional<T> actual) {
        return actual.orElseThrow(AssertionError::new);
      }
    };
  }
}
