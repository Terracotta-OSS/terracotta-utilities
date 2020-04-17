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
package org.terracotta.org.junit.rules;

import org.junit.runner.Description;
import org.junit.runners.model.MultipleFailureException;
import org.junit.runners.model.Statement;

import java.util.ArrayList;
import java.util.List;

/**
 * Equivalent of {@link org.junit.rules.ExternalResource}, but correctly handling failure in after method.
 */
public class ExternalResource extends org.junit.rules.ExternalResource {
  @Override
  public Statement apply(Statement base, Description description) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        before();
        List<Throwable> errors = new ArrayList<>(2);
        try {
          base.evaluate();
        } catch (Throwable t) {
          errors.add(t);
        } finally {
          try {
            after();
          } catch (Throwable t) {
            errors.add(t);
          }
        }
        MultipleFailureException.assertEmpty(errors);
      }
    };
  }
}