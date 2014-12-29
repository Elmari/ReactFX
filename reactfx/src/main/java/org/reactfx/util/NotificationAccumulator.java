package org.reactfx.util;

import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Function;

import org.reactfx.collection.ListChange;
import org.reactfx.collection.ListModificationSequence;
import org.reactfx.collection.ObsList;
import org.reactfx.collection.ObsList.Observer;
import org.reactfx.util.AccuMap.Empty;

/**
 * @param <O> observer type
 * @param <V> type of produced values
 */
public interface NotificationAccumulator<O, V> {

    static <T, A> NotificationAccumulator<Consumer<? super T>, T> accumulativeStreamNotifications(
            Function<? super A, AccumulatorSize> size,
            Function<? super A, ? extends T> head,
            Function<? super A, ? extends A> tail,
            Function<? super T, ? extends A> initialTransformation,
            BiFunction<? super A, ? super T, ? extends A> reduction) {
        return new AccumulativeStreamNotifications<>(
                size, head, tail, initialTransformation, reduction);
    }

    static <T> NotificationAccumulator<Consumer<? super T>, T> queuingStreamNotifications() {
        return new QueuingStreamNotifications<>();
    }

    static <T> NotificationAccumulator<Consumer<? super T>, T> reducingStreamNotifications(BinaryOperator<T> reduction) {
        return new ReducingStreamNotifications<>(reduction);
    }

    static <T> NotificationAccumulator<Consumer<? super T>, T> retainLatestStreamNotifications() {
        return new RetainLatestStreamNotifications<>();
    }

    static <T> NotificationAccumulator<Consumer<? super T>, T> nonRecursiveStreamNotifications() {
        return new NonRecursiveStreamNotifications<>();
    }

    static <E> NotificationAccumulator<ObsList.Observer<? super E, ?>, ListChange<? extends E>> listNotifications() {
        return new ListNotifications<>();
    }


    /* Interface methods */

    boolean isEmpty();
    Runnable takeOne();
    void addAll(Iterator<O> observers, V value);
    void clear();
}

abstract class NotificationAccumulatorBase<O, V, A>
implements NotificationAccumulator<O, V> {
    private AccuMap<O, V, A> accuMap;

    NotificationAccumulatorBase(AccuMap.Empty<O, V, A> accuMap) {
        this.accuMap = accuMap;
    }

    protected abstract AccumulatorSize size(O observer, A accumulatedValue);
    protected abstract Runnable head(O observer, A accumulatedValue);
    protected abstract A tail(O observer, A accumulatedValue);

    @Override
    public boolean isEmpty() {
        return accuMap.isEmpty();
    }

    @Override
    public Runnable takeOne() {
        Tuple2<O, A> t = accuMap.peek();
        switch(t.map(this::size)) {
            case ZERO:
                accuMap = accuMap.dropPeeked();
                return () -> {};
            case ONE:
                accuMap = accuMap.dropPeeked();
                return t.map(this::head);
            case MANY:
                Runnable notification = t.map(this::head);
                A newAccumulatedValue = t.map(this::tail);
                accuMap = accuMap.updatePeeked(newAccumulatedValue);
                return notification;
            default:
                throw new AssertionError("Unreachable code");
        }
    }

    @Override
    public void addAll(Iterator<O> keys, V value) {
        accuMap = accuMap.addAll(keys, value);
    }

    @Override
    public void clear() {
        accuMap = accuMap.empty();
    }
}


/* ******************** *
 * Non-recursive stream *
 * ******************** */

final class NonRecursiveStreamNotifications<T>
extends NotificationAccumulatorBase<Consumer<? super T>, T, T> {

    public NonRecursiveStreamNotifications() {
        super(AccuMap.emptyNonAdditiveMap());
    }

    @Override
    protected AccumulatorSize size(
            Consumer<? super T> observer,
            T accumulatedValue) {
        return AccumulatorSize.ONE;
    }

    @Override
    protected Runnable head(Consumer<? super T> observer, T accumulatedValue) {
        return () -> observer.accept(accumulatedValue);
    }

    @Override
    protected T tail(Consumer<? super T> observer, T accumulatedValue) {
        throw new NoSuchElementException();
    }
}


/* ******************* *
 * Accumulative stream *
 * ******************* */

final class AccumulativeStreamNotifications<T, A>
extends NotificationAccumulatorBase<Consumer<? super T>, T, A> {
    private final Function<? super A, AccumulatorSize> size;
    private final Function<? super A, ? extends T> head;
    private final Function<? super A, ? extends A> tail;

    AccumulativeStreamNotifications(
            Function<? super A, AccumulatorSize> size,
            Function<? super A, ? extends T> head,
            Function<? super A, ? extends A> tail,
            Function<? super T, ? extends A> initialTransformation,
            BiFunction<? super A, ? super T, ? extends A> reduction) {
        super(AccuMap.emptyAccumulationMap(initialTransformation, reduction));
        this.size = size;
        this.head = head;
        this.tail = tail;
    }

    @Override
    protected AccumulatorSize size(Consumer<? super T> observer, A accumulatedValue) {
        return size.apply(accumulatedValue);
    }

    @Override
    protected Runnable head(Consumer<? super T> observer, A accumulatedValue) {
        T event = head.apply(accumulatedValue);
        return () -> observer.accept(event);
    }

    @Override
    protected A tail(Consumer<? super T> observer, A accumulatedValue) {
        return tail.apply( accumulatedValue);
    }
}


/* ************** *
 * Queuing stream *
 * ************** */

final class QueuingStreamNotifications<T>
extends NotificationAccumulatorBase<Consumer<? super T>, T, Deque<T>> {

    QueuingStreamNotifications() {
        super(AccuMap.emptyQueueMap());
    }

    @Override
    protected AccumulatorSize size(
            Consumer<? super T> observer,
            Deque<T> accumulatedValue) {
        return AccumulatorSize.fromInt(accumulatedValue.size());
    }

    @Override
    protected Runnable head(
            Consumer<? super T> observer,
            Deque<T> accumulatedValue) {
        T t = accumulatedValue.getFirst();
        return () -> observer.accept(t);
    }

    @Override
    protected Deque<T> tail(
            Consumer<? super T> observer,
            Deque<T> accumulatedValue) {
        accumulatedValue.removeFirst();
        return accumulatedValue;
    }

}


/* *************** *
 * Reducing stream *
 * *************** */

abstract class AbstractReducingStreamNotifications<T>
extends NotificationAccumulatorBase<Consumer<? super T>, T, T> {

    AbstractReducingStreamNotifications(
            Empty<Consumer<? super T>, T, T> accuMap) {
        super(accuMap);
    }

    @Override
    protected final AccumulatorSize size(
            Consumer<? super T> observer,
            T accumulatedValue) {
        return AccumulatorSize.ONE;
    }

    @Override
    protected final Runnable head(Consumer<? super T> observer, T accumulatedValue) {
        return () -> observer.accept(accumulatedValue);
    }

    @Override
    protected final T tail(Consumer<? super T> observer, T accumulatedValue) {
        throw new NoSuchElementException();
    }
}

final class ReducingStreamNotifications<T>
extends AbstractReducingStreamNotifications<T> {

    ReducingStreamNotifications(BinaryOperator<T> reduction) {
        super(AccuMap.emptyReductionMap(reduction));
    }
}


/* ******************** *
 * Retain Latest stream *
 * ******************** */

final class RetainLatestStreamNotifications<T>
extends AbstractReducingStreamNotifications<T> {

    RetainLatestStreamNotifications() {
        super(AccuMap.emptyRetainLatestMap());
    }
}


/* ************************ *
 * List change accumulation *
 * ************************ */

final class ListNotifications<E>
extends NotificationAccumulatorBase<ObsList.Observer<? super E, ?>, ListChange<? extends E>, ListModificationSequence<E>> {

    ListNotifications() {
        super(AccuMap.emptyListChangeAccumulationMap());
    }

    @Override
    protected AccumulatorSize size(
            Observer<? super E, ?> observer,
            ListModificationSequence<E> accumulatedValue) {
        return observer.sizeOf(accumulatedValue);
    }

    @Override
    protected Runnable head(
            Observer<? super E, ?> observer,
            ListModificationSequence<E> mods) {
        return takeHead(observer, mods);
    }

    private final <O> Runnable takeHead(
            Observer<? super E, O> observer,
            ListModificationSequence<E> mods) {
        O h = observer.headOf(mods);
        return () -> observer.onChange(h);
    }

    @Override
    protected ListModificationSequence<E> tail(
            Observer<? super E, ?> observer,
            ListModificationSequence<E> mods) {
        return observer.tailOf(mods);
    }
}