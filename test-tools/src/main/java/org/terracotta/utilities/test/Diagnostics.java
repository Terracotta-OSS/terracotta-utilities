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
package org.terracotta.utilities.test;

import com.sun.management.HotSpotDiagnosticMXBean;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

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
   * Dumps the supplied threads in a thread-dump-like format.  Information about
   * locked/blocked objects is only available when {@code useThreadInfo} is {@code true}.
   * @param threads the threads to dump
   * @param useThreadInfo if {@code true}, obtains a {@link ThreadMXBean} through
   *                      which the {@link ThreadInfo} instance describing each
   *                      {@code Thread} is obtained; the {@code ThreadInfo} instance,
   *                      <i>if available</i>, is used for the dump; when {@code false},
   *                      only the {@code Thread} content is used for the dump
   * @param printStream the {@code PrintStream} to which the dump is written
   */
  public static void dumpThreads(Collection<Thread> threads, boolean useThreadInfo, PrintStream printStream) {
    class ThreadPair {
      final Thread thread;
      final ThreadInfo threadInfo;

      public ThreadPair(Thread thread, ThreadInfo threadInfo) {
        this.thread = thread;
        this.threadInfo = threadInfo;
      }

      public ThreadPair(Thread thread) {
        this(thread, null);
      }
    }

    List<ThreadPair> threadPairs = new ArrayList<>();
    if (useThreadInfo) {
      /*
       * The return from ThreadMXBean.getThreadInfo can have "holes" -- if a Thread is already
       * terminated, its slot in the returned array is null.
       */
      List<Thread> threadList = new ArrayList<>(threads);
      long[] threadIds = threadList.stream().mapToLong(ThreadId::get).toArray();
      ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
      ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(threadIds, true, true);
      for (int i = 0; i < threadList.size(); i++) {
        threadPairs.add(new ThreadPair(threadList.get(i), threadInfos[i]));
      }
    } else {
      threads.stream().map(ThreadPair::new).forEach(threadPairs::add);
    }

    for (ThreadPair p : threadPairs) {
      if (p.threadInfo == null) {
        Thread t = p.thread;
        printStream.format("\"%s\" Id=%d prio=%d %s%n",
            t.getName(), ThreadId.get(t), t.getPriority(), t.getState());
        for (StackTraceElement element : t.getStackTrace()) {
          printStream.format("\tat %s%n", element);
        }
        printStream.println();
      } else {
        printStream.print(format(p.threadInfo));
      }
    }
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
   * the current process obtained from {@link #getLongPid()}.
   *
   * @param dumpLiveObjects if {@code true}, only "live" (reachable) objects are dumped;
   *                        if {@code false}, all objects in the heap are dumped
   *
   * @return the name of the dump file; the file is written to the current directory (generally {@code user.dir})
   */
  public static String dumpHeap(final boolean dumpLiveObjects) {

    String dumpName;
    long pid = getLongPid();
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
   * Gets the PID of the current process.
   * For Java 9+, this method returns the value obtained from {@code ProcessHandle.pid()};
   * for Java 8-, this method is dependent upon "common"
   * operation of the {@code java.lang.management.RuntimeMXBean#getName()} method.
   *
   * @return the PID of the current process or {@code -1} if the PID can not be determined
   *
   * @deprecated Use {@link #getLongPid()}; the PID returned by the Java 9+
   *    {@code ProcessHandle.pid()} method is a {@code long}.  This method
   *    returns the {@code long} value cast to an {@code int}.
   */
  @Deprecated
  public static int getPid() {
    return (int)Pid.PID.orElse(-1);
  }

  /**
   * Gets the PID of the current process.
   * For Java 9+, this method returns the value obtained from {@code ProcessHandle.pid()};
   * for Java 8-, this method is dependent upon "common"
   * operation of the {@code java.lang.management.RuntimeMXBean#getName()} method.
   *
   * @return the PID of the current process or {@code -1} if the PID can not be determined
   */
  public static long getLongPid() {
    return Pid.PID.orElse(-1);
  }

  /**
   * Gets the thread id of the specified thread.  For Java 19+, this method returns the
   * value of {@code Thread.threadId}; for Java 18-, this method returns the value of
   * {@code Thread.getId}.
   * @param thread the {@code thread} for which the id is to be returned
   * @return the {@code Thread} id; {@code -1} if an error was raised while obtaining the id
   */
  public static long threadId(Thread thread) {
    return ThreadId.get(thread);
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
    int estThreadCount = 1 + root.activeCount();
    int actualThreadCount;
    while ((actualThreadCount = root.enumerate(threads = new Thread[estThreadCount])) >= estThreadCount) {
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
   * Handles using the JVM-appropriate method to obtain the id for a {@code Thread}.
   */
  private static class ThreadId {
    private static final MethodHandle THREAD_ID_METHOD;

    static {
      MethodHandles.Lookup lookup = MethodHandles.publicLookup();
      MethodType longNiladic = MethodType.methodType(long.class);
      MethodHandle threadId;
      try {
        // Thread.threadId is added in Java 19 as a replacement for Thread.getId
        threadId = lookup.findVirtual(Thread.class, "threadId", longNiladic);
      } catch (NoSuchMethodException | IllegalAccessException e) {
        try {
          // Thread.getId is deprecated in Java 19
          threadId = lookup.findVirtual(Thread.class, "getId", longNiladic);
        } catch (NoSuchMethodException | IllegalAccessException ex) {
          threadId = MethodHandles.constant(long.class, -1L);

          System.err.format("Failed to create handle for Thread 'id' method; calls to %s.threadId will return -1", Diagnostics.class.getSimpleName());
          ex.addSuppressed(e);
          ex.printStackTrace(System.err);
        }
      }
      THREAD_ID_METHOD = threadId;
    }

    /**
     * Gets the id of the specified {@code Thread}.
     * @param t the {@code Thread} for which the id is to be obtained
     * @return the {@code Thread} id; {@code -1} if there is an error obtaining the {@code Thread} id
     */
    private static long get(Thread t) {
      try {
        return (long)THREAD_ID_METHOD.invoke(t);
      } catch (Error e) {
        throw e;
      } catch (Throwable throwable) {
        System.err.format("Failed obtain thread id from %s; returning -1", THREAD_ID_METHOD);
        throwable.printStackTrace(System.err);
        return -1L;
      }
    }
  }

  /**
   * Determines the process identifier of the current process.
   */
  private static final class Pid {
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private static final OptionalLong PID = getPidInternal();

    private static OptionalLong getPidInternal() {
      Long pid = null;
      try {
        // Use Java 9+ ProcessHandle.current().pid() if available
        Class<?> processHandleClass = Class.forName("java.lang.ProcessHandle");
        Method currentMethod = processHandleClass.getMethod("current");
        Method getPidMethod = processHandleClass.getMethod("pid");
        pid = (Long)getPidMethod.invoke(currentMethod.invoke(null));

      } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | ClassNotFoundException e) {
        // Expected to be of the form "<pid>@<hostname>"
        String jvmProcessName = ManagementFactory.getRuntimeMXBean().getName();
        try {
          pid = Long.parseLong(jvmProcessName.substring(0, jvmProcessName.indexOf('@')));

        } catch (NumberFormatException | IndexOutOfBoundsException ignored) {
        }
      }
      return (pid == null ? OptionalLong.empty() : OptionalLong.of(pid));
    }
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
