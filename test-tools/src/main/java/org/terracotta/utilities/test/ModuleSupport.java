/*
 * Copyright 2023 Terracotta, Inc., a Software AG company.
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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

import static java.lang.invoke.MethodType.methodType;

/**
 * Provides methods for interacting with the Java Module framework in a mixed-level environment.
 */
@SuppressWarnings("JavaLangInvokeHandleSignature")
public class ModuleSupport {

  /**
   * Reference to {@code Class.getModule()} method.
   */
  private static final MethodHandle GET_MODULE_METHOD;
  /**
   * Reference to {@code Module.isOpen(String, Module)} method.
   */
  private static final MethodHandle IS_OPEN_METHOD;
  /**
   * Reference to <i>unnamed</i> module for the {@code ClassLoader} of this class.
   */
  private static final Object UNNAMED_MODULE;

  static {
    MethodHandle getModuleMethod = null;
    Object unnamedModule = null;
    MethodHandle isOpenMethod = null;
    try {
      Class<?> moduleClass = Class.forName("java.lang.Module");
      try {
        MethodHandles.Lookup lookup = MethodHandles.lookup();

        getModuleMethod = lookup.findVirtual(Class.class, "getModule", methodType(moduleClass));

        MethodHandle getUnnamedModuleMethod = lookup.findVirtual(ClassLoader.class, "getUnnamedModule",
            methodType(moduleClass));
        unnamedModule = getUnnamedModuleMethod.invoke(ModuleSupport.class.getClassLoader());

        isOpenMethod = lookup.findVirtual(moduleClass, "isOpen", methodType(boolean.class, String.class, moduleClass));
      } catch (Throwable e) {
        throw new AssertionError("Failed to obtain references to Module support methods", e);
      }
    } catch (ClassNotFoundException ignored) {
      // Pre-Java 9 ... no modules
    } finally {
      GET_MODULE_METHOD = getModuleMethod;
      UNNAMED_MODULE = unnamedModule;
      IS_OPEN_METHOD = isOpenMethod;
    }
  }

  /**
   * Determines if the module and package of the object provided is <i>open</i> to the <i>unnamed</i> module
   * for this class.
   *
   * @param ref object for which "openness" is checked
   * @return {@code true} if {@code ref} is open to this unnamed module; {@code false} otherwise;
   * if an error is raised while checking the status, the error is recorded to {@code System.err}
   * and a {@code false} is returned
   */
  @SuppressWarnings("unused")
  public static boolean isOpen(Object ref) {
    return isOpen(ref.getClass());
  }

  /**
   * Determines if the module and package of the class provided is <i>open</i> to the <i>unnamed</i> module
   * for this class.
   *
   * @param targetClass class for which "openness" is checked
   * @return {@code true} if {@code targetClass} is open to this unnamed module; {@code false} otherwise;
   * if an error is raised while checking the status, the error is recorded to {@code System.err}
   * and a {@code false} is returned
   */
  public static boolean isOpen(Class<?> targetClass) {
    if (UNNAMED_MODULE == null) {
      return true;
    }
    try {
      Object module = GET_MODULE_METHOD.invoke(targetClass);
      String packageName = targetClass.getPackage().getName();
      return ((boolean)IS_OPEN_METHOD.invoke(module, packageName, UNNAMED_MODULE));
    } catch (Throwable e) {
      synchronized (System.err) {
        System.err.format("Unable to determine module open state for %s%n", targetClass.getName());
        e.printStackTrace(System.err);
      }
      return false;
    }
  }
}
