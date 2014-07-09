package org.reactfx;

import java.util.function.Supplier;

/**
 * An event stream whose emission of events can be suspended temporarily.
 * What events, if any, are emitted when resumed depends on the concrete
 * implementation.
 */
public interface SuspendableEventStream<T> extends EventStream<T> {

    Guard suspend();

    default void suspendWhile(Runnable r) {
        try(Guard g = suspend()) { r.run(); }
    };

    default <U> U suspendWhile(Supplier<U> f) {
        try(Guard g = suspend()) { return f.get(); }
    }
}

abstract class SuspendableEventStreamBase<T>
extends LazilyBoundStream<T>
implements SuspendableEventStream<T> {

    private final EventStream<T> source;
    private int suspended = 0;

    protected SuspendableEventStreamBase(EventStream<T> source) {
        this.source = source;
    }

    protected abstract void handleEventWhenSuspended(T event);

    protected void onSuspend() {}
    protected void onResume() {}

    protected final boolean isSuspended() {
        return suspended > 0;
    }

    @Override
    public final Guard suspend() {
        if(suspended == 0) {
            onSuspend();
        }
        ++suspended;
        return Guard.closeableOnce(this::resume);
    }

    @Override
    protected final Subscription subscribeToInputs() {
        return subscribeTo(source, this::handleEvent);
    }

    private void resume() {
        --suspended;
        if(suspended == 0) {
            onResume();
        }
    }

    private void handleEvent(T event) {
        if(isSuspended()) {
            handleEventWhenSuspended(event);
        } else {
            emit(event);
        }
    }
}