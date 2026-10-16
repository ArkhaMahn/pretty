package org.zaproxy.zap.extension.prettyview.async;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.zaproxy.zap.extension.prettyview.formatters.PrettyResult;
import org.zaproxy.zap.extension.prettyview.formatters.UniversalPrettifierManager;

public final class PrettyWorker {
  private final AtomicReference<PrettyWorkerTask> current = new AtomicReference();

  public PrettyWorker() {
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
   *
   * <p>Ordering stays per view on the shared pool: the previous task is cancelled and pulled
   * out of the queue before the next is submitted, so a view never has two live tasks and a
   * newer message always wins.
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
    PrettyWorkerTask task = new PrettyWorkerTask(message, formatting, onResult);
    PrettyWorkerTask previous = this.current.getAndSet(task);
    if (previous != null) {
      previous.cancel();
      PrettyWorkerPool.removeQueued(previous);
    }
    try {
      PrettyWorkerPool.execute(task);
    } catch (RejectedExecutionException e) {
      // The shared queue is unbounded, so this only fires past shutdown; drop the task.
      task.cancel();
    }
  }

  private static String bodyOf(String message, boolean bodyOnly) {
    if (bodyOnly || message == null) {
      return message == null ? "" : message;
    }
    return org.zaproxy.zap.extension.prettyview.detect.MessageSplitter.split(message).getBody();
  }

  public void cancelPending() {
    PrettyWorkerTask task = this.current.getAndSet(null);
    if (task != null) {
      task.cancel();
      PrettyWorkerPool.removeQueued(task);
    }
  }

  /**
   * Releases this view's work. The pool itself is shared and stops only on add-on unload (see
   * {@link PrettyWorkerPool#shutdown()}), never here.
   */
  public void shutdown() {
    this.cancelPending();
  }
}
