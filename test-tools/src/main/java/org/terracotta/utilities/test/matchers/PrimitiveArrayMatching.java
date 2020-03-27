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
import org.hamcrest.FeatureMatcher;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.hamcrest.collection.ArrayMatching;

import static org.hamcrest.core.IsEqual.equalTo;

public class PrimitiveArrayMatching {

  public static Matcher<byte[]> byteArrayWithSize(int size) {
    return new FeatureMatcher<byte[], Integer>(equalTo(size), "a byte array with size","array size") {
      @Override
      protected Integer featureValueOf(byte[] actual) {
        return actual.length;
      }
    };
  }

  public static Matcher<byte[]> byteArrayContaining(byte ... values) {
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
}
