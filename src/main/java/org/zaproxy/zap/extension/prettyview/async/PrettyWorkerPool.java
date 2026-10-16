package org.zaproxy.zap.extension.prettyview.async;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The worker threads every Pretty view shares.
 *
 * <p>Formatting and highlighting are CPU-bound, so one thread per view oversubscribes the
 * machine the moment several panels load at once (main viewer, Manual Request Editor,
 * Requester and Break each hold their own views). A small shared pool caps the concurrency
 * while views keep their own ordering: each view still cancels its stale task before queuing
 * the next, and tasks from different views never wait on each other past the pool size.
 * Threads are daemon with a low priority and time out when idle; unloading the add-on stops
 * the pool so no thread outlives it.
 */
public final class PrettyWorkerPool {
  /** Threads while busy; idle threads die after this long. */
  private static final long THREAD_TIMEOUT_SECONDS = 30L;

  private static final ThreadFactory THREAD_FACTORY = new ThreadFactory() {
    private final AtomicInteger sequence = new AtomicInteger();

    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable,
          "ZAP-PrettyView-worker-" + this.sequence.incrementAndGet());
      thread.setDaemon(true);
      thread.setPriority(4);
      return thread;
    }
  };

  /**
   * Enough threads to format request and response side by side, few enough to leave the
   * machine responsive: half the cores, clamped to two through four.
   */
  private static final int THREADS = Math.max(2,
      Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() / 2)));

  private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(THREADS, THREADS,
      THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(),
      THREAD_FACTORY);

  static {
    POOL.allowCoreThreadTimeOut(true);
  }

  private PrettyWorkerPool() {
  }

  static void execute(Runnable task) {
    POOL.execute(task);
  }

  /**
   * Drops a task that is still queued. Instance-scoped, so one view can never steal another
   * view's work; a no-op unless the exact task is still waiting.
   */
  static boolean removeQueued(Runnable task) {
    return POOL.remove(task);
  }

  /** Stops the pool on add-on unload, after every view has cancelled its own tasks. */
  public static void shutdown() {
    POOL.shutdownNow();
  }
}
