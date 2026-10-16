package org.zaproxy.zap.extension.prettyview.async;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
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

  public PrettyWorker() {
    this.holder = new ExecutorHolder();
  }

  public void submit(String message, String contentType, UniversalPrettifierManager prettifierManager, Consumer<PrettyResult> onResult) {
    this.submit(message, contentType, false, prettifierManager, onResult);
  }

  /**
   * Queues one formatting task. Split (Header/Body) views hand over a bare body, which must go
   * through {@link UniversalPrettifierManager#prettifyBodyMessage}: the whole-message entry point
   * would file it as headers and report {@code PLAIN_TEXT}.
   *
   * <p>When {@code prettify} is false the text goes through verbatim and only its format is
   * detected, for views where the user writes: what is shown - and saved, and sent - stays
   * byte-for-byte theirs, with highlighting only.
   */
  public void submit(String message, String contentType, boolean bodyOnly, UniversalPrettifierManager prettifierManager, Consumer<PrettyResult> onResult) {
    this.submit(message, contentType, bodyOnly, true, prettifierManager, onResult);
  }

  public void submit(String message, String contentType, boolean bodyOnly, boolean prettify, UniversalPrettifierManager prettifierManager, Consumer<PrettyResult> onResult) {
    Supplier<PrettyResult> formatting;
    if (!prettify) {
      formatting = () -> PrettyResult.formatted(message,
          prettifierManager.detectFormat(contentType, PrettyWorker.bodyOf(message, bodyOnly)));
    } else {
      formatting = bodyOnly
          ? () -> prettifierManager.prettifyBodyMessage(message, contentType)
          : () -> prettifierManager.prettifyMessage(message, contentType);
    }
    block3: {
      PrettyWorkerTask task = new PrettyWorkerTask(message, formatting, onResult);
      PrettyWorkerTask previous = this.current.getAndSet(task);
      if (previous != null) {
        previous.cancel();
      }
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

  private static String bodyOf(String message, boolean bodyOnly) {
    if (bodyOnly || message == null) {
      return message == null ? "" : message;
    }
    return org.zaproxy.zap.extension.prettyview.detect.MessageSplitter.split(message).getBody();
  }

  private boolean onRejected(PrettyWorkerTask task) {
    PrettyWorkerTask queued = this.holder.clearQueued();
    if (queued != null) {
      queued.cancel();
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
    PrettyWorkerTask task = this.current.getAndSet(null);
    if (task != null) {
      task.cancel();
    }
    PrettyWorkerTask queued = this.holder.clearQueued();
    if (queued != null) {
      queued.cancel();
    }
  }

  public void shutdown() {
    this.cancelPending();
    this.holder.executor.shutdownNow();
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

