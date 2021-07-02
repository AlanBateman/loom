/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.concurrent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.DeadlineExpiredException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;

/**
 * An object that supports a basic form of <em>structured concurrency</em>. A
 * task that splits into several concurrent sub-tasks uses the {@link #fork(Callable)}
 * method to start a thread to execute each sub-task. The {@link #completions()}
 * method creates an object that can be used to iterate over the completed
 * sub-tasks. When done, the {@link #close()} method waits for any remaining
 * threads executing sub-tasks to finish.
 *
 * <p> A Thing is thread confined. It is owned by the thread that creates it
 * and only the owner can fork tasks, iterate over completed tasks, or close
 * the Thing. Failure to close a Thing method may result in a memory leak.
 *
 * <p> A Thing supports bulk-cancellation with the {@link #cancelRemaining()}
 * method. This interrupts the threads executing any remaining tasks. This is
 * useful for cases where a sub-task completes with a result or exception and
 * the main task wants to complete early or abort. It is also useful with
 * the try-finally construct to cancel remaining tasks when the main task
 * throws an exception.
 *
 * <p> The {@link #deadline(Instant)} method may be used to set a deadline. If
 * the deadline expires before the Thing is closed then the remaining tasks are
 * cancelled and the threads executing the tasks are interrupted. The owner
 * is also interrupted if the deadline expires before it invokes the close method.
 * The close method throws {@link DeadlineExpiredException} after all tasks have
 * completed and the Thing is closed. When using the try-with-resources construct,
 * and an exception is thrown by the owner before it invokes close, then the
 * DeadlineExpiredException will be added as a {@linkplain Throwable#getSuppressed()
 * suppressed exception}.
 *
 * <p> Thing defines the {@link #disableCompletions()} method to support cases
 * where the main task does iterate over completed tasks. This avoids accumulation
 * of completed tasks that would otherwise arise.
 *
 * <p> When interrupted, all blocking operations defined by Thing cancel the
 * remaining tasks and complete with the interrupt status set. This includes the
 * hasNext() and next() methods of Iterators used to iterate over the completed
 * tasks.
 *
 * <h2> Exceptions </h2>
 *
 * <p> A Thing is thread confined. Unless otherwise specified, all instance methods
 * throw {@link IllegalCallerException} if invoked by a thread that is not the owner.
 *
 * <p> Unless otherwise specified, passing a null  argument to a method in this
 * class will cause a {@link NullPointerException} to be thrown.
 *
 * @apiNote
 * The following example is a method that connects to a given host name and port.
 * The host name resolve to many address. The connect method attempts to connect
 * to all address. If a connection is established to more than one address then
 * it ensures that all but one is closed so that it doesn't leak resources
 *
 * <pre> {@code
 *     Socket connect(String host, int port, Instant deadline) throws IOException {
 *         InetAddress[] addresses = InetAddress.getAllByName(host);
 *
 *         List<SocketConnector> connectors = Stream.of(addresses)
 *                 .map(a -> new InetSocketAddress(a, port))
 *                 .map(SocketConnector::new)
 *                 .toList();
 *
 *         Socket first = null;
 *         Throwable exception = null;
 *
 *         try (var thing = Thing.<SocketConnector>ofVirtual().deadline(deadline)) {
 *
 *             thing.fork(connectors);
 *
 *             for (TaskThing<? extends SocketConnector> task : thing.completions()) {
 *                 Socket socket = ((SocketConnector) task.task()).socket();
 *
 *                 switch (task.status()) {
 *                     case RUNNING -> {
 *                         assert false;
 *                     }
 *                     case SUCCESS -> {
 *                         if (first == null) {
 *                             first = socket;
 *                             thing.cancelRemaining();
 *                         } else {
 *                             socket.close();
 *                         }
 *                     }
 *                     case FAILURE -> {
 *                         assert socket.isClosed() : "Socket should be closed";
 *                         if (first == null && exception == null) {
 *                             exception = task.exception();
 *                         }
 *                     }
 *                     case CANCELLED -> {
 *                         // the connection may be established
 *                         socket.close();
 *                     }
 *                 }
 *             }
 *         }
 *
 *         assert first != null || exception != null;
 *
 *         if (first != null) {
 *             return first;
 *         } else if (exception instanceof IOException ioe) {
 *             throw ioe;
 *         } else {
 *             throw new IOException(exception);
 *         }
 *     }
 *
 *     static class SocketConnector implements Callable<SocketConnector>, AutoCloseable {
 *         private final Socket socket;
 *         private final SocketAddress remote;
 *         SocketConnector(SocketAddress remote) {
 *             this.socket = new Socket();
 *             this.remote = remote;
 *         }
 *         @Override
 *         public SocketConnector call() throws IOException {
 *             socket.connect(remote);
 *             return this;
 *         }
 *         Socket socket() {
 *             return socket;
 *         }
 *         @Override
 *         public void close() {
 *             try { socket.close(); } catch (IOException ignore) { }
 *         }
 *         @Override
 *         public String toString() {
 *             return remote + "/" + socket;
 *         }
 *     }
 * }</pre>
 *
 * <p> The following method is a simple server that starts a virtual thread to
 * handle each connection. It uses the disableCompletions() methods as the tasks
 * don't return a result for the main task to consume.
 * <pre> {@code
 *     void server(int port) throws IOException {
 *         try (ServerSocket listener = new ServerSocket(port);
 *              Thing<Object> thing = Thing.ofVirtual().disableCompletions()) {
 *             while (true) {
 *                 Socket socket = listener.accept();
 *                 thing.fork(() -> handle(socket));
 *             }
 *         } // close with cause
 *     }
 *
 *     Void handle(Socket s) throws IOException {
 *         try (s) {
 *             // handle connection
 *         }
 *         return null;
 *     }
 * }</pre>
 *
 * @param <T> The result type
 * @since 99
 */
public sealed interface Thing<T>
        extends AutoCloseable permits ThingImpl {

    /**
     * Creates a Thing that starts a new thread for each task. The given
     * ThreadFactory is used to create the threads. The number of threads is
     * unbounded.
     *
     * @param factory the factory to use when creating new threads
     * @param <T> The result type of the tasks the threads execute
     * @return a new Thing, owned by the current thread
     */
    static <T> Thing<T> of(ThreadFactory factory) {
        return new ThingImpl<>(factory);
    }

    /**
     * Creates a Thing that starts a new virtual thread for each task. The
     * number of threads is unbounded.
     *
     * @param <T> The result type of the tasks the threads execute
     * @return a new Thing, owned by the current thread
     */
    static <T> Thing<T> ofVirtual() {
        ThreadFactory factory = Thread.ofVirtual().factory();
        return new ThingImpl<>(factory);
    }

    /**
     * Sets a deadline.
     *
     * If the deadline expires before this Thing is closed then:
     * <ol>
     *   <li> The remaining tasks are cancelled, as if by invoking {@link
     *   #cancelRemaining()}. </li>
     *   <li> If not waiting in the {@link #close()} method, then the owner
     *   thread is interrupted. </li>
     *   <li> The {@code close} method throws {@link DeadlineExpiredException}
     *   after all tasks (including cancelled tasks) complete and this Thing
     *   is closed. </li>
     * </ol>
     *
     * @apiNote
     * When using the {@code try-with-resources} construct, and an exception is
     * thrown by the owner thread in response to being interrupted before it
     * closes this thing, then the {@code DeadlineExpiredException} will be added
     * as a {@linkplain Throwable#getSuppressed() suppressed exception}.
     *
     * @param deadline the deadline
     * @return this thing
     * @throws IllegalStateException if a deadline has already been set
     * or this thing is closed
     */
    Thing<T> deadline(Instant deadline);

    /**
     * Disables completion notifications. This method is for cases where
     * {@link #completions()} is not used and where unbounded queueing of
     * completed tasks would be problematic.
     *
     * @return this thing
     * @throws IllegalStateException if the {@code fork} method has already
     * been used to start threads or this thing is closed
     */
    Thing<T> disableCompletions();

    /**
     * Starts a new thread to execute the given value-returning task.
     * If created with a {@link ThreadFactory} then its {@link
     * ThreadFactory#newThread(Runnable) newThread(Runnable)} method is
     * invoked to create the thread to execute the task.
     *
     * <p> Invoking {@link Future#cancel(boolean) cancel(true)} on a {@link
     * Future Future} representing the pending result of the task will
     * {@link Thread#interrupt() interrupt} the thread executing the task.
     *
     * @param task the task
     * @return a TaskThing representing the task
     * @throws RejectedExecutionException if the thread factory is unable to create
     * a new thread or a deadline has been set and it has expired
     * @throws IllegalStateException if this thing is closed
     */
    <V extends T> TaskThing<V> fork(Callable<V> task);

    /**
     * Starts a new thread to execute the given value-returning tasks.
     *
     * @implSpec
     * The default implementation invokes {@link #fork(Callable) fork} for each task.
     *
     * @apiNote This method is not atomic. RejectedExecutionException may
     * be thrown after some tasks have been started. This method makes a best
     * effort attempt to cancel the tasks that it submitted when
     * RejectedExecutionException is thrown.
     *
     * @param tasks the tasks to execute
     * @return a list of TaskThing elements, in the same sequential order as
     * produced by the iterator given collection of tasks.
     * @throws RejectedExecutionException if the thread factory is unable to create
     * a new thread or a deadline has been set and it has expired
     * @throws IllegalStateException if this thing is closed
     */
    default List<TaskThing<T>> fork(Collection<? extends Callable<T>> tasks) {
        Objects.requireNonNull(tasks);
        List<TaskThing<T>> list = new ArrayList<>();
        boolean submitted = false;
        try {
            for (Callable<T> t : tasks) {
                list.add(fork(t));
            }
            submitted = true;
        } finally {
            if (!submitted) {
                list.forEach(t -> t.asFuture().cancel(true));
            }
        }
        return list;
    }

    /**
     * Starts a new thread to execute the given value-returning tasks.
     *
     * @implSpec
     * The default implementation invokes {@link #fork(Callable) fork} for each task.
     *
     * @apiNote This method is not atomic. RejectedExecutionException may
     * be thrown after some tasks have been started. This method makes a best
     * effort attempt to cancel the tasks that it submitted when
     * RejectedExecutionException is thrown.
     *
     * @param tasks the tasks to execute
     * @return a list of TaskThing elements, in the same sequential order as
     * the array of tasks.
     * @throws RejectedExecutionException if the thread factory is unable to create
     * a new thread or a deadline has been set and it has expired
     * @throws IllegalStateException if this thing is closed
     */
    @SuppressWarnings("unchecked")
    default List<TaskThing<T>> fork(Callable<T>... tasks) {
        return fork(List.of(tasks));
    }

    /**
     * Wait for all unfinished tasks to complete or be cancelled.
     *
     * If interrupted while waiting then all remaining tasks are cancelled,
     * as if by invoking {@link Future#cancel(boolean) cancel(true)}, and
     * the method completes with the interrupt status set.
     *
     * @return this thing
     * @throws IllegalStateException if this thing is closed
     */
    Thing<T> awaitRemaining();

    /**
     * Cancels all unfinished tasks. This method is equivalent to invoking
     * {@link Future#cancel(boolean) cancel(true)} on all unfinished tasks.
     *
     * @return this thing
     * @throws IllegalStateException if this thing is closed
     */
    Thing<T> cancelRemaining();

    /**
     * {@return an object that may be used with the {@code for-each} construct
     * to iterate over completed tasks}.
     *
     * <p> The object returned by this method creates an {@link Iterator Iterator}
     * that iterates over the completed tasks in the order that they complete. Once
     * all completed tasks have been iterated over then the {@code hasNext()} or
     * {@code next()} methods will wait for further unfinished tasks to complete.
     * The iterator ends, and {@code hasNext()} returns {@code false}, when all tasks
     * have been iterated over.
     *
     * <p> The object returned by this method is not a general-purpose {@code Iterable}
     * in that a completed task will only returned by one call to the {@code next()}
     * method. In other words, if more than one {@code Iterator} is used to iterate
     * over the completed tasks then a specific task will only be returned by one
     * of the iterators.
     *
     * <p> The {@code hasNext()} method of the iterator reads ahead by one element.
     * If the {@code hasNext} method returns {@code true}, and is followed by a call
     * to the {@code next} method on the same iterator it is guaranteed that the
     * {@code next} method will return an element. The {@code Iterator} does not
     * support the {@link Iterator#remove remove} operation.
     *
     * <p> If interrupted while waiting in {@code hasNext()} or {@code next()}
     * then remaining tasks are cancelled, as if by invoking {@link
     * Future#cancel(boolean) cancel(true)}. The cancelled tasks will be queued so
     * they can be retrieved using the iterator. When interrupted, the {@code
     * hasNext()} and {@code next()} methods complete with the interrupt status
     * set.
     *
     * <p> If {@link #disableCompletions()} has been used to disable completion
     * notification then the iterators created by the object iterate over zero
     * elements.
     *
     * @throws IllegalStateException if this thing is closed
     */
    Iterable<TaskThing<? extends T>> completions();

    /**
     * Waits for the threads executing unfinished tasks to finish execution of
     * the tasks and closes this Thing.
     *
     * <p> If interrupted while waiting then all remaining tasks are cancelled,
     * as if by invoking {@link Future#cancel(boolean) cancel(true)}. The
     * method then continues to wait until the threads executing the cancelled
     * tasks finish the tasks. The method returns with the interrupt status set.
     *
     * @throws DeadlineExpiredException if a deadline was set and it expired
     * before this method was invoked
     */
    @Override
    void close();
}
