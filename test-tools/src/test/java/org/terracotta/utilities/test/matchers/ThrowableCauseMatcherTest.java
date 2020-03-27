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

import org.junit.Test;

import java.util.NoSuchElementException;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertThat;
import static org.terracotta.utilities.test.TerracottaAssert.assertThrows;
import static org.terracotta.utilities.test.matchers.ThrowableCauseMatcher.causedBy;

/**
 * Basic tests for {@link ThrowableCauseMatcher}.
 */
public class ThrowableCauseMatcherTest {

  @Test
  public void testSuccessfulMatch() {
    assertThrows(() -> {
      throw new Exception("top", new NoSuchElementException());
    }, allOf(instanceOf(Exception.class), causedBy(instanceOf(NoSuchElementException.class))));
  }

  @Test
  public void testNoCause() {
    try {
      assertThrows(() -> {
        throw new Exception("top");
      }, allOf(instanceOf(Exception.class), causedBy(instanceOf(NoSuchElementException.class))));
    } catch (AssertionError e) {
      assertThat(e.getMessage(), containsString("with cause an instance of " + NoSuchElementException.class.getName() + " null"));
    }
  }
}