package org.zaproxy.zap.extension.prettyview.async;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.zaproxy.zap.extension.prettyview.formatters.PrettyResult;
import org.zaproxy.zap.extension.prettyview.formatters.UniversalPrettifierManager;

public final class PrettyWorker {
  private static final ThreadFactory THREAD_FACTORY = runnable -> {
    Thread thread = new Thread(runnable, "ZAP-PrettyView-worker");
    thread.setDaemon(true);
    thread.setPriority(4);
    return thread;
  };
  private final ExecutorHolder holder;
  private final AtomicReference<PrettyWorkerTask> current = new AtomicReference();
  private final AtomicInteger submitted = new AtomicInteger();
  private final AtomicInteger skipped = new AtomicInteger();

  public PrettyWorker() {
    this.holder = new ExecutorHolder();
  }

  public void submit(String message, String contentType, UniversalPrettifierManager prettifierManager, Consumer<PrettyResult> onResult) {
    block3: {
      PrettyWorkerTask task = new PrettyWorkerTask(message, contentType, prettifierManager, onResult);
      PrettyWorkerTask previous = this.current.getAndSet(task);
      if (previous != null) {
        previous.cancel();
      }
      this.submitted.incrementAndGet();
      try {
        this.holder.executor.execute(task);
      }
      catch (RejectedExecutionException e) {
        if (this.onRejected(task)) break block3;
        task.cancel();
        return;
      }
    }
  }

  private boolean onRejected(PrettyWorkerTask task) {
    PrettyWorkerTask queued = this.holder.clearQueued();
    if (queued != null) {
      queued.cancel();
      this.skipped.incrementAndGet();
    }
    try {
      this.holder.executor.execute(task);
      return true;
    }
    catch (RejectedExecutionException retryFailed) {
      return false;
    }
  }

  public void cancelPending() {
    PrettyWorkerTask queued;
    PrettyWorkerTask task = this.current.getAndSet(null);
    if (task != null) {
      task.cancel();
    }
    if ((queued = this.holder.clearQueued()) != null) {
      queued.cancel();
      this.skipped.incrementAndGet();
    }
  }

  public void shutdown() {
    this.cancelPending();
    this.holder.executor.shutdownNow();
  }

  public int getSubmittedCount() {
    return this.submitted.get();
  }

  public int getSkippedCount() {
    return this.skipped.get();
  }

  private static final class ExecutorHolder {
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(1), THREAD_FACTORY, new ThreadPoolExecutor.AbortPolicy());

    private ExecutorHolder() {
      this.executor.allowCoreThreadTimeOut(true);
    }

    private PrettyWorkerTask clearQueued() {
      Runnable queued = (Runnable)this.executor.getQueue().poll();
      return queued instanceof PrettyWorkerTask ? (PrettyWorkerTask)queued : null;
    }
  }
}

