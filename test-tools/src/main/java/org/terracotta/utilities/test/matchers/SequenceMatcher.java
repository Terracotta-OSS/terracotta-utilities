/*
 * Copyright 2020 Terracotta, Inc., a Software AG company.
 * Copyright IBM Corp. 2024, 2025
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
import org.hamcrest.TypeSafeMatcher;

import java.util.stream.StreamSupport;

import static java.util.Arrays.copyOfRange;
import static org.hamcrest.collection.IsEmptyIterable.emptyIterable;

public class SequenceMatcher<T> extends TypeSafeMatcher<Iterable<? extends T>> {

  Matcher<Iterable<? extends T>> headMatcher;
  Matcher<Iterable<? extends T>> tailMatcher;

  public SequenceMatcher(Matcher<Iterable<? extends T>> head, Matcher<Iterable<? extends T>> tail) {
    this.headMatcher = head;
    this.tailMatcher = tail;
  }

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

  @SuppressWarnings("varargs") @SafeVarargs
  public static <T> Matcher<Iterable<? extends T>> containsSequencesInOrder(Matcher<Iterable<? extends T>>... sequences) {
    if (sequences.length == 0) {
      return emptyIterable();
    } else if (sequences.length == 1) {
      return sequences[0];
    } else {
      return new SequenceMatcher<>(sequences[0], containsSequencesInOrder(copyOfRange(sequences, 1, sequences.length)));
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> Iterable<T>[] splitIterable(Iterable<T> iterable, int headLength) {
    Iterable<T> it1 = () -> StreamSupport.stream(iterable.spliterator(), false).limit(headLength).iterator();
    Iterable<T> it2 = () -> StreamSupport.stream(iterable.spliterator(), false).skip(headLength).iterator();
    return (Iterable<T>[]) new Iterable<?>[] {it1, it2};
  }
}
