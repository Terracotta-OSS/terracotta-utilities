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

import com.sun.management.HotSpotDiagnosticMXBean;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import javax.management.MBeanServer;

/**
 * Provides methods to produce diagnostic output.
 */
@SuppressWarnings({ "UnusedDeclaration", "WeakerAccess" })
public final class Diagnostics {

  private static final String HOTSPOT_DIAGNOSTIC_MXBEAN_NAME = "com.sun.management:type=HotSpotDiagnostic";
  private static final String HEAP_DUMP_FILENAME_TEMPLATE = "java_%1$04d_%2$tFT%2$tH%2$tM%2$tS.%2$tL.hprof";
  private static final File WORKING_DIRECTORY = new File(System.getProperty("user.dir"));

  /**
   * Private niladic constructor to prevent instantiation.
   */
  private Diagnostics() {
  }

  /**
   * Writes a complete thread dump to {@code System.err}.
   */
  public static void threadDump() {
    threadDump(System.err);
  }

  /**
   * Writes a complete thread dump to the designated {@code PrintStream}.
   *
   * @param out the {@code PrintStream} to which the thread dump is written
   */
  public static void threadDump(final PrintStream out) {
    Objects.requireNonNull(out ,"out");

    final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

    final Calendar when = Calendar.getInstance();
    final ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(
      threadMXBean.isObjectMonitorUsageSupported(), threadMXBean.isSynchronizerUsageSupported());

    out.format("%nFull thread dump %1$tF %1$tT.%1$tL %1$tz%n", when);
    for (final ThreadInfo threadInfo : threadInfos) {
      out.print(format(threadInfo));
    }
  }

  /**
   * Format a {@code ThreadInfo} instance <i>without</i> a stack depth limitation.  This method reproduces the
   * formatting performed in {@code java.lang.management.ThreadInfo.toString()} without the stack depth limit.
   *
   * @param threadInfo the {@code ThreadInfo} instance to foramt
   *
   * @return a {@code CharSequence} instance containing the formatted {@code ThreadInfo}
   */
  private static CharSequence format(final ThreadInfo threadInfo) {
    StringBuilder sb = new StringBuilder(4096);

    Thread.State threadState = threadInfo.getThreadState();
    sb.append('"')
      .append(threadInfo.getThreadName())
      .append('"')
      .append(" Id=")
      .append(threadInfo.getThreadId())
      .append(' ')
      .append(threadState);

    if (threadInfo.getLockName() != null) {
      sb.append(" on ").append(threadInfo.getLockName());
    }
    if (threadInfo.getLockOwnerName() != null) {
      sb.append(" owned by ").append('"').append(threadInfo.getLockOwnerName()).append('"')
        .append(" Id=").append(threadInfo.getLockOwnerId());
    }

    if (threadInfo.isSuspended()) {
      sb.append(" (suspended)");
    }
    if (threadInfo.isInNative()) {
      sb.append(" (in native)");
    }
    sb.append('\n');

    StackTraceElement[] stackTrace = threadInfo.getStackTrace();
    for (int i = 0; i < stackTrace.length; i++) {
      StackTraceElement element = stackTrace[i];
      sb.append("\tat ").append(element);
      sb.append('\n');
      if (i == 0) {
        if (threadInfo.getLockInfo() != null) {
          switch (threadState) {
            case BLOCKED:
              sb.append("\t- blocked on ").append(threadInfo.getLockInfo());
              sb.append('\n');
              break;
            case WAITING:
              sb.append("\t- waiting on ").append(threadInfo.getLockInfo());
              sb.append('\n');
              break;
            case TIMED_WAITING:
              sb.append("\t- waiting on ").append(threadInfo.getLockInfo());
              sb.append('\n');
              break;
            default:
          }
        }
      }

      for (MonitorInfo monitorInfo : threadInfo.getLockedMonitors()) {
        if (monitorInfo.getLockedStackDepth() == i) {
          sb.append("\t- locked ").append(monitorInfo);
          sb.append('\n');
        }
      }
    }

    LockInfo[] lockedSynchronizers = threadInfo.getLockedSynchronizers();
    if (lockedSynchronizers.length > 0) {
      sb.append("\n\tNumber of locked synchronizers = ").append(lockedSynchronizers.length);
      sb.append('\n');
      for (LockInfo lockedSynchronizer : lockedSynchronizers) {
        sb.append("\t- ").append(lockedSynchronizer);
        sb.append('\n');
      }
    }

    sb.append('\n');
    return sb;
  }

  /**
   * Take a Java heap dump into a file whose name is produced from the template
   * <code>{@value #HEAP_DUMP_FILENAME_TEMPLATE}</code> where {@code 1$} is the PID of
   * the current process obtained from {@link #getPid()}.
   *
   * @param dumpLiveObjects if {@code true}, only "live" (reachable) objects are dumped;
   *                        if {@code false}, all objects in the heap are dumped
   *
   * @return the name of the dump file; the file is written to the current directory (generally {@code user.dir})
   */
  public static String dumpHeap(final boolean dumpLiveObjects) {

    String dumpName;
    final int pid = getPid();
    final Date currentTime = new Date();
    if (pid > 0) {
      dumpName = String.format(HEAP_DUMP_FILENAME_TEMPLATE, pid, currentTime);
    } else {
      dumpName = String.format(HEAP_DUMP_FILENAME_TEMPLATE, 0, currentTime);
    }

    dumpName = new File(WORKING_DIRECTORY, dumpName).getAbsolutePath();

    try {
      dumpHeap(dumpLiveObjects, dumpName);
    } catch (IOException e) {
      System.err.printf("Unable to write heap dump to %s: %s%n", dumpName, e);
      e.printStackTrace(System.err);
      return null;
    }

    return dumpName;
  }

  /**
   * Write a Java heap dump to the named file.  If the dump file exists, this method will
   * fail.
   *
   * @param dumpLiveObjects if {@code true}, only "live" (reachable) objects are dumped;
   *                        if {@code false}, all objects in the heap are dumped
   * @param dumpName the name of the file to which the heap dump is written; relative names
   *                 are relative to the current directory ({@code user.dir}).  If the value
   *                 of {@code dumpName} does not end in {@code .hprof}, it is appended.
   *
   * @throws IOException if thrown while loading the HotSpot Diagnostic MXBean or writing the heap dump
   *
   * @see <a href="http://docs.oracle.com/javase/8/docs/jre/api/management/extension/com/sun/management/HotSpotDiagnosticMXBean.html">
   *        com.sun.management.HotSpotDiagnosticMXBean</a>
   */
  public static void dumpHeap(final boolean dumpLiveObjects, String dumpName) throws IOException {
    if (dumpName == null) {
      throw new NullPointerException("dumpName");
    }

    if (!dumpName.endsWith(".hprof")) {
      dumpName += ".hprof";
    }

    final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    final HotSpotDiagnosticMXBean hotSpotDiagnosticMXBean =
      ManagementFactory.newPlatformMXBeanProxy(server, HOTSPOT_DIAGNOSTIC_MXBEAN_NAME, HotSpotDiagnosticMXBean.class);
    hotSpotDiagnosticMXBean.dumpHeap(dumpName, dumpLiveObjects);
  }

  /**
   * Gets the PID of the current process.  This method is dependent upon "common"
   * operation of the {@code java.lang.management.RuntimeMXBean#getName()} method.
   *
   * @return the PID of the current process or {@code -1} if the PID can not be determined
   */
  public static int getPid() {
    // Expected to be of the form "<pid>@<hostname>"
    final String jvmProcessName = ManagementFactory.getRuntimeMXBean().getName();
    try {
      return Integer.parseInt(jvmProcessName.substring(0, jvmProcessName.indexOf('@')));
    } catch (NumberFormatException | IndexOutOfBoundsException e) {
      return -1;
    }
  }

  /**
   * Returns an array contain a reference to all {@link Thread} instances in the JVM.  Since the JVM is
   * not stopped, the returned array may miss threads or have no longer extant threads.
   * @return new array containing a reference to all threads in the JVM, subject to acquisition limitations
   * @see ThreadGroup#enumerate(Thread[], boolean)
   */
  public static Thread[] getAllThreads() {
    ThreadGroup root = rootGroup();
    Thread[] threads;
    int estThreadCount = root.activeCount();
    int actualThreadCount;
    while ((actualThreadCount = root.enumerate(threads = new Thread[estThreadCount])) == estThreadCount) {
      estThreadCount += 1 + estThreadCount * 20 / 100;
    }
    threads = Arrays.copyOf(threads, actualThreadCount);
    return threads;
  }

  private static ThreadGroup rootGroup() {
    ThreadGroup parent = Thread.currentThread().getThreadGroup();
    ThreadGroup root = parent;
    while ((parent = parent.getParent()) != null) {
      root = parent;
    }
    return root;
  }

  /**
   * Writes the current stats from {@link BufferPoolMXBean} for off-heap memory use to {@link System#out}.
   * @see #dumpBufferPoolInfo(PrintStream)
   * @see #getMaxDirectMemoryInfo()
   */
  public static void dumpBufferPoolInfo() {
    dumpBufferPoolInfo(System.out);
  }

  /**
   * Writes the current stats from {@link BufferPoolMXBean} for off-heap memory use to the specified
   * print stream.
   * @param out the {@code PrintStream} to which the stats are written
   * @see #getMaxDirectMemoryInfo()
   */
  public static void dumpBufferPoolInfo(PrintStream out) {
    out.println("BufferPoolMXBeans:");
    List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
    for (BufferPoolMXBean pool : pools) {
      out.format("[%s] count=%d, used=%sB, capacity=%sB%n",
          pool.getName(), pool.getCount(), formatSize(pool.getMemoryUsed()), formatSize(pool.getTotalCapacity()));
    }
  }

  /**
   * Gets the maximum amount of direct memory that may be allocated in this virtual machine.
   * @return the maximum direct memory; {@code null} if the maximum direct memory value is not available
   * @see #getMaxDirectMemoryInfo()
   */
  public static Long getMaxDirectMemory() {
    return getMaxDirectMemoryInfo().maxDirectMemory();
  }

  /**
   * Gets the maximum amount of direct memory that may be allocated in this virtual machine.
   * The value is obtained from {@code jdk.internal.misc.VM} when available (Java 9+) or from
   * {@code sun.misc.VM} (Java 8-).  If the value cannot be obtained, the value returned by
   * {@link MaxDirectMemoryInfo#maxDirectMemory()} is {@code null} and
   * {@link MaxDirectMemoryInfo#maxDirectMemoryAccessFault()} describes the reason.
   * <p>
   * For this method to return the {@code maxDirectMemory} under Java 9+, the
   * {@code --add-exports java.core/jdk.internal.misc=ALL-UNNAMED} option must be supplied to
   * the JVM.
   * @return an object describing the amount of direct memory that can be allocated
   */
  public static MaxDirectMemoryInfo getMaxDirectMemoryInfo() {
    return MaxDirectMemoryInfoHelper.INSTANCE;
  }

  /**
   * A singleton {@code MaxDirectMemoryInfo} instance.  Since max direct memory is established
   * during JVM startup, the value does not change.
   */
  private static class MaxDirectMemoryInfoHelper {
    private static final MaxDirectMemoryInfo INSTANCE = getMaxDirectMemoryInfoInternal();

    private static MaxDirectMemoryInfo getMaxDirectMemoryInfoInternal() {
      Throwable maxDirectMemoryFault = null;
      Long maxDirectMemory = null;
      Class<?> vmClass = null;
      try {
        vmClass = Class.forName("jdk.internal.misc.VM");
      } catch (ClassNotFoundException e) {
        try {
          vmClass = Class.forName("sun.misc.VM");
        } catch (ClassNotFoundException e1) {
          maxDirectMemoryFault = e;
        }
      }
      if (vmClass != null) {
        try {
          maxDirectMemory = (Long)vmClass.getDeclaredMethod("maxDirectMemory").invoke(null);
        } catch (IllegalAccessException e) {
          if (vmClass.getName().startsWith("jdk") && e.getMessage().contains(" does not export ")) {
            System.err.format("Unable to access %s.maxDirectMemory(): " +
                    "Access to maxDirectMemory() requires the java option '--add-exports %s/%s=ALL-UNNAMED'%n",
                vmClass.getName(), getModuleName(vmClass), vmClass.getPackage().getName());
          }
          maxDirectMemoryFault = e;
        } catch (NoSuchMethodException | InvocationTargetException e) {
          maxDirectMemoryFault = e;
        }
      }
      return new MaxDirectMemoryInfo(maxDirectMemory, vmClass == null ? "<unavailable>" : vmClass.getName(), maxDirectMemoryFault);
    }

    private static String getModuleName(Class<?> clazz) {
      try {
        Object module = clazz.getClass().getDeclaredMethod("getModule").invoke(clazz);
        return (String)module.getClass().getDeclaredMethod("getName").invoke(module);
      } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
        return null;
      }
    }
  }

  /**
   * Describes the maximum direct memory information.
   */
  public static class MaxDirectMemoryInfo {
    private final Long maxDirectMemory;
    private final String vmClassName;
    private final Throwable maxDirectMemoryAccessFault;

    private MaxDirectMemoryInfo(Long maxDirectMemory, String vmClassName, Throwable maxDirectMemoryAccessFault) {
      this.maxDirectMemory = maxDirectMemory;
      this.vmClassName = vmClassName;
      this.maxDirectMemoryAccessFault = maxDirectMemoryAccessFault;
    }

    /**
     * The maximum direct memory that may be allocated in this JVM.
     * @return {@code null} if the value is unavailable (see {@link #maxDirectMemoryAccessFault()}; otherwise,
     *    the maximum amount of direct memory (set or defaulted) available for allocation in this JVM
     */
    public Long maxDirectMemory() {
      return this.maxDirectMemory;
    }

    /**
     * The name of the class from which the maximum direct memory value was obtained.
     * @return if the maximum direct memory is available, the name of the class from which the value was
     *      obtained; otherwise, {@code <unavailable>}
     */
    public String vmClassName() {
      return this.vmClassName;
    }

    /**
     * The reason the maximum direct memory is not available.
     * @return {@code null} if the maximum direct memory value is available; otherwise, the value of
     *      {@link Throwable#getMessage()} from the exception raised when attempting to obtain the
     *      maximum direct memory value
     */
    public String maxDirectMemoryAccessFault() {
      return (this.maxDirectMemoryAccessFault == null ? "" : this.maxDirectMemoryAccessFault.toString());
    }
  }

  // http://stackoverflow.com/questions/3758606/how-to-convert-byte-size-into-human-readable-format-in-java
  private static String formatSize(long v) {
    if (v < 1024) return v + " ";
    int z = (63 - Long.numberOfLeadingZeros(v)) / 10;
    return String.format("%.1f %si", (double) v / (1L << (z * 10)), " KMGTPE".charAt(z));
  }
}
