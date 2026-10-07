package org.zaproxy.zap.extension.prettyview.async;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zaproxy.zap.extension.prettyview.formatters.PrettyResult;
import org.zaproxy.zap.extension.prettyview.formatters.UniversalPrettifierManager;

public final class PrettyWorkerTask
implements Runnable {
  private static final Logger LOGGER = LogManager.getLogger(PrettyWorkerTask.class);
  private final String message;
  private final String contentType;
  private final Supplier<PrettyResult> formatting;
  private final Consumer<PrettyResult> onResult;
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final AtomicBoolean published = new AtomicBoolean();
  private volatile Thread workerThread;

  public PrettyWorkerTask(String message, String contentType, UniversalPrettifierManager prettifierManager, Consumer<PrettyResult> onResult) {
    this(message, contentType, () -> prettifierManager.prettifyMessage(message, contentType), onResult);
  }

  PrettyWorkerTask(String message, String contentType, Supplier<PrettyResult> formatting, Consumer<PrettyResult> onResult) {
    this.message = message;
    this.contentType = contentType;
    this.formatting = formatting;
    this.onResult = onResult;
  }

  @Override
  public void run() {
    this.workerThread = Thread.currentThread();
    try {
      if (this.isCancelled()) {
        return;
      }
      PrettyResult result = this.formatting.get();
      if (this.isCancelled()) {
        return;
      }
      this.publish(result);
    }
    catch (Throwable t) {
      if (!this.isCancelled()) {
        LOGGER.debug("Pretty view formatting task failed; rendering raw text.", t);
        this.publish(PrettyResult.verbatim(this.message, null, "formatting failed"));
      }
    }
    finally {
      this.workerThread = null;
    }
  }

  private void publish(PrettyResult result) {
    if (this.published.compareAndSet(false, true)) {
      this.onResult.accept(result);
    }
  }

  public void cancel() {
    this.cancelled.set(true);
    Thread thread = this.workerThread;
    if (thread != null) {
      thread.interrupt();
    }
  }

  public boolean isCancelled() {
    return this.cancelled.get();
  }

  public boolean isPublished() {
    return this.published.get();
  }

  public String getContentType() {
    return this.contentType;
  }
}

