/*
 * Copyright (c) 2021, 2023, Oracle and/or its affiliates. All rights reserved.
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
package java.util.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import jdk.internal.javac.PreviewFeature;
import jdk.internal.misc.ThreadFlock;

/**
 * A basic API for <em>structured concurrency</em>. {@code StructuredTaskScope} supports
 * cases where a task splits into several concurrent subtasks, to be executed in their
 * own threads, and where the subtasks must complete before the main task continues. A
 * {@code StructuredTaskScope} can be used to ensure that the lifetime of a concurrent
 * operation is confined by a <em>syntax block</em>, just like that of a sequential
 * operation in structured programming.
 *
 * <h2>Basic usage</h2>
 *
 * A {@code StructuredTaskScope} is created with one of its public constructors. It defines
 * the {@link #fork(Callable) fork} method to start a thread to execute a task, the {@link
 * #join() join} method to wait for all threads to finish, and the {@link #close() close}
 * method to close the task scope. The API is intended to be used with the {@code
 * try-with-resources} construct. The intention is that code in the <em>block</em> uses
 * the {@code fork} method to fork threads to execute the subtasks, wait for the threads
 * to finish with the {@code join} method, and then <em>process the results</em>.
 * Processing of results may include handling or re-throwing of exceptions.
 * {@snippet lang=java :
 *     try (var scope = new StructuredTaskScope<Object>()) {
 *
 *         Handle<Integer> handle1 = scope.fork(task1);   // @highlight substring="fork"
 *         Handle<String> handle2 = scope.fork(task2);    // @highlight substring="fork"
 *
 *         scope.join();                                  // @highlight substring="join"
 *
 *         ... process results/exceptions ...
 *
 *     } // close                                         // @highlight substring="close"
 * }
 * To ensure correct usage, the {@code join} and {@code close} methods may only be invoked
 * by the <em>owner</em> (the thread that opened/created the task scope), and the
 * {@code close} method throws an exception after closing if the owner did not invoke the
 * {@code join} method after forking.
 *
 * <p> {@code StructuredTaskScope} defines the {@link #shutdown() shutdown} method to shut
 * down a task scope without closing it. Shutdown is useful for cases where a subtask
 * completes with a result (or exception) and the results of other unfinished subtasks are
 * no longer needed. If a subtask invokes {@code shutdown} while the owner is waiting in
 * the {@code join} method then it will cause {@code join} to wakeup, all unfinished
 * threads to be {@linkplain Thread#interrupt() interrupted} and prevents new threads
 * from starting in the task scope.
 *
 * <h2>Subclasses with policies for common cases</h2>
 *
 * Two subclasses of {@code StructuredTaskScope} are defined to implement policy for
 * common cases:
 * <ol>
 *   <li> {@link ShutdownOnSuccess ShutdownOnSuccess} captures the first result and
 *   shuts down the task scope to interrupt unfinished threads and wakeup the owner. This
 *   class is intended for cases where the result of any subtask will do ("invoke any")
 *   and where there is no need to wait for results of other unfinished tasks. It defines
 *   methods to get the first result or throw an exception if all subtasks fail.
 *   <li> {@link ShutdownOnFailure ShutdownOnFailure} captures the first exception and
 *   shuts down the task scope. This class is intended for cases where the results of all
 *   subtasks are required ("invoke all"); if any subtask fails then the results of other
 *   unfinished subtasks are no longer needed. If defines methods to throw an exception if
 *   any of the subtasks fail.
 * </ol>
 *
 * <p> The following are two examples that use the two classes. In both cases, a pair of
 * subtasks are forked to fetch resources from two URL locations "left" and "right". The
 * first example creates a ShutdownOnSuccess object to capture the result of the first
 * subtask to complete normally, cancelling the other by way of shutting down the task
 * scope. The main task waits in {@code join} until either subtask completes with a result
 * or both subtasks fail. It invokes {@link ShutdownOnSuccess#result(Function)
 * result(Function)} method to get the captured result. If both subtasks fail then this
 * method throws a {@code WebApplicationException} with the exception from one of the
 * subtasks as the cause.
 * {@snippet lang=java :
 *     try (var scope = new StructuredTaskScope.ShutdownOnSuccess<String>()) {
 *
 *         scope.fork(() -> fetch(left));
 *         scope.fork(() -> fetch(right));
 *
 *         scope.join();
 *
 *         // @link regex="result(?=\()" target="ShutdownOnSuccess#result" :
 *         String result = scope.result(e -> new WebApplicationException(e));
 *
 *         ...
 *     }
 * }
 * The second example creates a ShutdownOnFailure object to capture the exception of the
 * first subtask to fail, cancelling the other by way of shutting down the task scope. The
 * main task waits in {@link #joinUntil(Instant)} until both subtasks complete with a
 * result, either fails, or a deadline is reached. It invokes {@link
 * ShutdownOnFailure#throwIfFailed(Function) throwIfFailed(Function)} to throw an exception
 * when either subtask fails. This method is a no-op if no subtasks fail. The main task
 * uses {@link Handle#result()} method to retrieve the result of each subtask.
 *
 * {@snippet lang=java :
 *    Instant deadline = ...
 *
 *    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
 *
 *         Handle<String> handle1 = scope.fork(() -> query(left));
 *         Handle<String> handle2 = scope.fork(() -> query(right));
 *
 *         scope.joinUntil(deadline);
 *
 *         // @link substring="throwIfFailed" target="ShutdownOnFailure#throwIfFailed" :
 *         scope.throwIfFailed(e -> new WebApplicationException(e));
 *
 *         // both subtasks completed successfully
 *         String result = Stream.of(handle1, handle1)
 *                 .map(Handle::result)
 *                 .collect(Collectors.joining(", ", "{ ", " }"));
 *
 *         ...
 *     }
 * }
 *
 * <h2>Extending StructuredTaskScope</h2>
 *
 * {@code StructuredTaskScope} can be extended, and the {@link
 * #handleComplete(Callable, Object, Throwable) handleComplete} method overridden, to
 * implement policies other than those implemented by {@code ShutdownOnSuccess} and
 * {@code ShutdownOnFailure}. The method may be overridden to, for example, collect the
 * results of subtasks that complete with a result and ignore subtasks that fail. It may
 * collect exceptions when subtasks fail. It may invoke the {@link #shutdown() shutdown}
 * method to shut down and cause {@link #join() join} to wakeup when some condition arises.
 *
 * <p> A subclass will typically define methods to make available results, state, or other
 * outcome to code that executes after the {@code join} method. A subclass that collects
 * results and ignores subtasks that fail may define a method that returns a collection of
 * results. A subclass that implements a policy to shut down when a subtask fails may
 * define a method to retrieve the exception of the first subtask to fail.
 *
 * <p> The following is an example of a {@code StructuredTaskScope} implementation that
 * collects the results of subtasks that complete successfully. It defines the method
 * <b>{@code results()}</b> to be used by the main task to retrieve the results.
 *
 * {@snippet lang=java :
 *     class MyScope<T> extends StructuredTaskScope<T> {
 *         private final Queue<T> results = new ConcurrentLinkedQueue<>();
 *
 *         MyScope() {
 *             super(null, Thread.ofVirtual().factory());
 *         }
 *
 *         @Override
 *         // @link substring="handleComplete" target="handleComplete" :
 *         protected void handleComplete(Callable<T> task, T result, Throwable exception) {
 *             if (exception == null) {
 *                 results.add(result);
 *             }
 *         }
 *
 *         // Returns a stream of results from the subtasks that completed successfully
 *         public Stream<T> results() {     // @highlight substring="results"
 *             return results.stream();
 *         }
 *     }
 *  }
 *
 * <h2><a id="TreeStructure">Tree structure</a></h2>
 *
 * Task scopes form a tree where parent-child relations are established implicitly when
 * opening a new task scope:
 * <ul>
 *   <li> A parent-child relation is established when a thread started in a task scope
 *   opens its own task scope. A thread started in task scope "A" that opens task scope
 *   "B" establishes a parent-child relation where task scope "A" is the parent of task
 *   scope "B".
 *   <li> A parent-child relation is established with nesting. If a thread opens task
 *   scope "B", then opens task scope "C" (before it closes "B"), then the enclosing task
 *   scope "B" is the parent of the nested task scope "C".
 * </ul>
 *
 * The <i>descendants</i> of a task scope are the child task scopes that it is a parent
 * of, plus the descendants of the child task scopes, recursively.
 *
 * <p> The tree structure supports:
 * <ul>
 *   <li> Inheritance of {@linkplain ScopedValue scoped values} across threads.
 *   <li> Confinement checks. The phrase "threads contained in the task scope" in method
 *   descriptions means threads started in the task scope or descendant scopes.
 * </ul>
 *
 * <p> The following example demonstrates the inheritance of a scoped value. A scoped
 * value {@code USERNAME} is bound to the value "{@code duke}". A {@code StructuredTaskScope}
 * is created and its {@code fork} method invoked to start a thread to execute {@code
 * childTask}. The thread inherits the scoped value <em>bindings</em> captured when
 * creating the task scope. The code in {@code childTask} uses the value of the scoped
 * value and so reads the value "{@code duke}".
 * {@snippet lang=java :
 *     private static final ScopedValue<String> USERNAME = ScopedValue.newInstance();
 *
 *     // @link substring="where" target="ScopedValue#where(ScopedValue, Object, Runnable)" :
 *     ScopedValue.where(USERNAME, "duke", () -> {
 *         try (var scope = new StructuredTaskScope<String>()) {
 *
 *             scope.fork(() -> childTask());           // @highlight substring="fork"
 *             ...
 *          }
 *     });
 *
 *     ...
 *
 *     String childTask() {
 *         // @link substring="get" target="ScopedValue#get()" :
 *         String name = USERNAME.get();   // "duke"
 *         ...
 *     }
 * }
 *
 * <p> {@code StructuredTaskScope} does not define APIs that exposes the tree structure
 * at this time.
 *
 * <p> Unless otherwise specified, passing a {@code null} argument to a constructor
 * or method in this class will cause a {@link NullPointerException} to be thrown.
 *
 * <h2>Memory consistency effects</h2>
 *
 * <p> Actions in the owner thread of, or a thread contained in, the task scope prior to
 * {@linkplain #fork forking} of a {@code Callable} task
 * <a href="{@docRoot}/java.base/java/util/concurrent/package-summary.html#MemoryVisibility">
 * <i>happen-before</i></a> any actions taken by that task, which in turn <i>happen-before</i>
 * the task result is retrieved via its {@code Handle}, or <i>happen-before</i> any
 * actions taken in a thread after {@linkplain #join() joining} of the task scope.
 *
 * @jls 17.4.5 Happens-before Order
 *
 * @param <T> the result type of tasks executed in the scope
 * @since 21
 */
@PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
public class StructuredTaskScope<T> implements AutoCloseable {
    private final ThreadFactory factory;
    private final ThreadFlock flock;
    private final ReentrantLock shutdownLock = new ReentrantLock();

    // set by owner when it forks, reset by owner when it joins
    private boolean needJoin;

    // states: OPEN -> SHUTDOWN -> CLOSED
    private static final int OPEN     = 0;   // initial state
    private static final int SHUTDOWN = 1;
    private static final int CLOSED   = 2;

    // scope state, set by owner, read by any thread
    private volatile int state;

    /**
     * A handle to a task worked with {@linkplain #fork(Callable)}.
     * @param <T> the result type
     * @since 21
     */
    @PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
    public interface Handle<T> {
        /**
         * Represents the state of a task.
         * @since 21
         */
        @PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
        enum State {
            /**
             * The task has not completed.
             */
            RUNNING,
            /**
             * The task completed with a result.
             * @see Handle#result()
             */
            SUCCESS,
            /**
             * The task completed with an exception.
             * @see Handle#exception()
             */
            FAILED,
            /**
             * The task was cancelled.
             * @see StructuredTaskScope#shutdown()
             */
            CANCELLED
        }

        /**
         * {@return the state of the task}
         */
        State state();

        /**
         * {@return the result, if the task completed normally}
         * @throws IllegalStateException if the task has not completed, the task
         * completed abnormally, or the scope was shutdown before the task completed
         * @throws IllegalStateException if the current thread is the task scope owner
         * and it did not invoke join after forking
         */
        T result();

        /**
         * {@return the exception, if the task completed abnormally}
         * @throws IllegalStateException if the task has not completed, the task
         * completed normally, or the scope was shutdown before the task completed
         * @throws IllegalStateException if the current thread is the task scope owner
         * and it did not invoke join after forking
         */
        Throwable exception();
    }

    /**
     * Creates a structured task scope with the given name and thread factory. The task
     * scope is optionally named for the purposes of monitoring and management. The thread
     * factory is used to {@link ThreadFactory#newThread(Runnable) create} threads when
     * tasks are {@linkplain #fork(Callable) forked}. The task scope is owned by the
     * current thread.
     *
     * <p> This method captures the current thread's {@linkplain ScopedValue scoped value}
     * bindings for inheritance by threads created in the task scope. The
     * <a href="#TreeStructure">Tree Structure</a> section in the class description
     * details how parent-child relations are established implicitly for the purpose of
     * inheritance of scoped value bindings.
     *
     * @param name the name of the task scope, can be null
     * @param factory the thread factory
     */
    public StructuredTaskScope(String name, ThreadFactory factory) {
        this.factory = Objects.requireNonNull(factory, "'factory' is null");
        this.flock = ThreadFlock.open(name);
    }

    /**
     * Creates an unnamed structured task scope that creates virtual threads. The task
     * scope is owned by the current thread.
     *
     * <p> This constructor is equivalent to invoking the 2-arg constructor with a name
     * of {@code null} and a thread factory that creates virtual threads.
     */
    public StructuredTaskScope() {
        this.factory = Thread.ofVirtual().factory();
        this.flock = ThreadFlock.open(null);
    }

    /**
     * Return true if the task scope is shutdown.
     */
    private boolean isShutdown() {
        return state >= SHUTDOWN;
    }

    /**
     * Return true if the task scope is closed.
     */
    private boolean isClosed() {
        return state == CLOSED;
    }

    private IllegalStateException newIllegalStateExceptionScopeClosed() {
        return new IllegalStateException("Task scope is closed");
    }

    private IllegalStateException newIllegalStateExceptionNoJoin() {
        return new IllegalStateException("Owner did not invoke join or joinUntil after fork");
    }

    /**
     * Throws IllegalStateException if the scope is closed, returning the current state.
     */
    private int ensureOpen() {
        int s = state;
        if (s == CLOSED)
            throw newIllegalStateExceptionScopeClosed();
        return s;
    }

    /**
     * Throws WrongThreadException if the current thread is not the owner.
     */
    private void ensureOwner() {
        if (Thread.currentThread() != flock.owner())
            throw new WrongThreadException("Current thread not owner");
    }

    /**
     * Throws WrongThreadException if the current thread is not the owner
     * or a thread contained in the tree.
     */
    private void ensureOwnerOrContainsThread() {
        Thread currentThread = Thread.currentThread();
        if (currentThread != flock.owner() && !flock.containsThread(currentThread))
            throw new WrongThreadException("Current thread not owner or thread in the tree");
    }

    /**
     * Throws IllegalStateException if the current thread is the owner and it did
     * not invoke join after forking
     */
    private void ensureJoinedIfOwner() {
        if (Thread.currentThread() == flock.owner() && needJoin) {
            throw newIllegalStateExceptionNoJoin();
        }
    }

    /**
     * Ensures that the current thread is the task scope owner and that the {@link #join()}
     * or {@link #joinUntil(Instant)} method has been called after {@linkplain
     * #fork(Callable) forking}.
     *
     * @apiNote This method can be used by subclasses that define methods to make available
     * results, state, or other outcome to code intended to execute after the join method.
     *
     * @throws WrongThreadException if the current thread is not the task scope owner
     * @throws IllegalStateException if the task scope owner did not invoke join after
     * forking
     */
    protected final void ensureOwnerAndJoined() {
        ensureOwner();
        if (needJoin) {
            throw newIllegalStateExceptionNoJoin();
        }
    }

    /**
     * Invoked when a task completes before the scope is shut down.
     *
     * <p> If the task completed successfully then this method is invoked with the result
     * (which may be {@code null}) and a {@code null} exception.
     *
     * <p> If the task completed with an exception then this method is invoked with a
     * {@code null} result and a non-{@code null} exception.
     *
     * @implSpec The default implementation throws {@code NullPointerException} if task
     * is null. It throws {@code IllegalArgumentException} if both the result and
     * exception are non-{@code null}.
     *
     * @apiNote The {@code handleComplete} method should be thread safe. It may be
     * invoked by several threads concurrently.
     *
     * @param task the task
     * @param result the result if the task completed successfully, can be null
     * @param exception the exception if the task completed abnormally, otherwise null
     *
     * @throws IllegalArgumentException if both result and exception are non-{@code null}
     */
    protected void handleComplete(Callable<T> task, T result, Throwable exception) {
        Objects.requireNonNull(task);
        if (result != null && exception != null)
            throw new IllegalArgumentException("result and exception are non-null");
    }

//    /**
//     * Starts a new thread to run the given task.
//     *
//     * <p> The new thread is created with the task scope's {@link ThreadFactory}. It
//     * inherits the current thread's {@linkplain ScopedValue scoped value} bindings. The
//     * bindings must match the bindings captured when the task scope was created.
//     *
//     * <p> The fork method returns a {@link Supplier} to supply the result when the task
//     * completes normally. The {@code Supplier}'s {@linkplain Supplier#get() get}
//     * method is intended to be called after waiting for all threads to finish (with
//     * {@link #join() join}) and when it is known that the task completed with a result.
//     * The {@code get} method throws {@code IllegalStateException} when
//     * <ul>
//     *     <li> The task has not completed. </li>
//     *     <li> The task completed abnormally (with an exception). </li>
//     *     <li> The scope was shutdown before the task completed. </li>
//     *     <li> Called by the task scope owner before waiting for all threads to finish
//     *          after forking. </li>
//     * </ul>
//     *
//     * <p> If this task scope is {@linkplain #shutdown() shutdown} (or in the process
//     * of shutting down) then the task will not run. In that case, this method returns
//     * a {@link Supplier} that throws {@code IllegalStateException} unconditionally.
//     *
//     * <p> If the task completes before the task scope is {@link #shutdown() shutdown}
//     * then the {@link #handleComplete(Callable, Object, Throwable) handleComplete} method
//     * is invoked to consume the completed task. If the task scope is shutdown at or around
//     * the same time that the task completes then the {@code handleComplete} method may
//     * or may not be invoked.
//     *
//     * <p> This method may only be invoked by the task scope owner or threads contained
//     * in the task scope.
//     *
//     * @param task the task to run
//     * @param <U> the result type
//     * @return a supplier of the result
//     * @throws IllegalStateException if this task scope is closed
//     * @throws WrongThreadException if the current thread is not the task scope owner or
//     * a thread contained in the task scope
//     * @throws StructureViolationException if the current scoped value bindings are not
//     * the same as when the task scope was created
//     * @throws RejectedExecutionException if the thread factory rejected creating a
//     * thread to run the task
//     */
//    public <U extends T> Supplier<U> fork2(Callable<? extends U> task) {
//        Handle<U> handle = fork(task);
//        return new Supplier<U>() {
//            @Override
//            public U get() {
//                return handle.result();
//            }
//            @Override
//            public String toString() {
//                return handle.toString();
//            }
//        };
//    }

    /**
     * Starts a new thread to run the given task.
     *
     * <p> The new thread is created with the task scope's {@link ThreadFactory}. It
     * inherits the current thread's {@linkplain ScopedValue scoped value} bindings. The
     * bindings must match the bindings captured when the task scope was created.
     *
     * <p> The fork method returns a {@link Handle Handle} to the forked task. The handle
     * can be used to obtain the result when the task completes normally, or the exception
     * when task completes abnormally. To ensure correct usage, the {@link Handle#result()
     * result()} and {@link Handle#exception() exception()} methods may only be called by
     * the task scope owner after it has waited for all threads to finish with the
     * {@link #join() join} or {@link #joinUntil(Instant)} methods.
     *
     * <p> If this task scope is {@linkplain #shutdown() shutdown} (or in the process
     * of shutting down) then the task will not run. In that case, this method returns
     * a handle representing a {@linkplain Handle.State#CANCELLED cancelled} task.
     *
     * <p> If the task completes before the task scope is {@link #shutdown() shutdown}
     * then the {@link #handleComplete(Callable, Object, Throwable) handleComplete} method
     * is invoked to consume the completed task. If the task scope is shutdown at or around
     * the same time that the task completes then the {@code handleComplete} method may
     * or may not be invoked.
     *
     * <p> This method may only be invoked by the task scope owner or threads contained
     * in the task scope.
     *
     * @param task the task to run
     * @param <U> the result type
     * @return the handle to the forked task
     * @throws IllegalStateException if this task scope is closed
     * @throws WrongThreadException if the current thread is not the task scope owner or a
     * thread contained in the task scope
     * @throws StructureViolationException if the current scoped value bindings are not
     * the same as when the task scope was created
     * @throws RejectedExecutionException if the thread factory rejected creating a
     * thread to run the task
     */
    public <U extends T> Handle<U> fork(Callable<? extends U> task) {
        Objects.requireNonNull(task, "'task' is null");
        int s = ensureOpen();

        TaskRunner<U> runner = new TaskRunner<>(this, task);
        boolean started = false;

        if (s == OPEN) {
            // create thread to run task
            Thread thread = factory.newThread(runner);
            if (thread == null) {
                throw new RejectedExecutionException("Rejected by thread factory");
            }

            // attempt to start the thread
            try {
                flock.start(thread);
                started = true;
            } catch (IllegalStateException e) {
                // shutdown (or underlying flock is shutdown)
            }
        }

        if (!started) {
            runner.tryCancel();
        } else if (Thread.currentThread() == flock.owner() && !needJoin) {
            // force owner to join
            needJoin = true;
        }

        // return handle to forked or cancelled task
        return new HandleImpl<>(runner);
    }

    /**
     * Wait for all threads to finish or the task scope to shut down.
     */
    private void implJoin(Duration timeout)
        throws InterruptedException, TimeoutException
    {
        ensureOwner();
        needJoin = false;
        int s = ensureOpen();
        if (s != OPEN)
            return;

        // wait for all threads, wakeup, interrupt, or timeout
        if (timeout != null) {
            flock.awaitAll(timeout);
        } else {
            flock.awaitAll();
        }
    }

    /**
     * Wait for all threads to finish or the task scope to shut down. This method waits
     * until all threads started in the task scope finish execution (of both task and
     * {@link #handleComplete(Callable, Object, Throwable) handleComplete} method), the
     * {@link #shutdown() shutdown} method is invoked to shut down the task scope, or the
     * current thread is {@linkplain Thread#interrupt() interrupted}.
     *
     * <p> This method may only be invoked by the task scope owner.
     *
     * @return this task scope
     * @throws IllegalStateException if this task scope is closed
     * @throws WrongThreadException if the current thread is not the task scope owner
     * @throws InterruptedException if interrupted while waiting
     */
    public StructuredTaskScope<T> join() throws InterruptedException {
        try {
            implJoin(null);
        } catch (TimeoutException e) {
            throw new InternalError();
        }
        return this;
    }

    /**
     * Wait for all threads to finish or the task scope to shut down, up to the given
     * deadline. This method waits until all threads started in the task scope finish
     * execution (of both task and {@link #handleComplete(Callable, Object, Throwable)
     * handleComplete} method), the {@link #shutdown() shutdown} method is invoked to shut
     * down the task scope, the current thread is {@linkplain Thread#interrupt() interrupted},
     * or the deadline is reached.
     *
     * <p> This method may only be invoked by the task scope owner.
     *
     * @param deadline the deadline
     * @return this task scope
     * @throws IllegalStateException if this task scope is closed
     * @throws WrongThreadException if the current thread is not the task scope owner
     * @throws InterruptedException if interrupted while waiting
     * @throws TimeoutException if the deadline is reached while waiting
     */
    public StructuredTaskScope<T> joinUntil(Instant deadline)
        throws InterruptedException, TimeoutException
    {
        Duration timeout = Duration.between(Instant.now(), deadline);
        implJoin(timeout);
        return this;
    }

    /**
     * Interrupt all unfinished threads.
     */
    private void implInterruptAll() {
        flock.threads().forEach(t -> {
            if (t != Thread.currentThread()) {
                try {
                    t.interrupt();
                } catch (Throwable ignore) { }
            }
        });
    }

    @SuppressWarnings("removal")
    private void interruptAll() {
        if (System.getSecurityManager() == null) {
            implInterruptAll();
        } else {
            PrivilegedAction<Void> pa = () -> {
                implInterruptAll();
                return null;
            };
            AccessController.doPrivileged(pa);
        }
    }

    /**
     * Shutdown the task scope if not already shutdown. Return true if this method
     * shutdowns the task scope, false if already shutdown.
     */
    private boolean implShutdown() {
        int s = ensureOpen();
        if (s < SHUTDOWN) {
            shutdownLock.lock();
            try {
                if (state < SHUTDOWN) {

                    // prevent new threads from starting
                    flock.shutdown();

                    // set status before interrupting tasks
                    state = SHUTDOWN;

                    // interrupt all unfinished threads
                    interruptAll();

                    return true;
                }
            } finally {
                shutdownLock.unlock();
            }
        }
        assert state >= SHUTDOWN;
        return false;
    }

    /**
     * Shut down the task scope without closing it. Shutting down a task scope prevents
     * new threads from starting, interrupts all unfinished threads, and causes the
     * {@link #join() join} method to wakeup. Shutdown is useful for cases where the
     * results of unfinished subtasks are no longer needed.
     *
     * <p> More specifically, this method:
     * <ul>
     * <li> {@linkplain Thread#interrupt() Interrupts} all unfinished threads in the
     * task scope (except the current thread).
     * <li> Wakes up the task scope owner if it is waiting in {@link #join()} or {@link
     * #joinUntil(Instant)}. If the task scope owner is not waiting then its next call to
     * {@code join} or {@code joinUntil} will return immediately.
     * </ul>
     *
     * <p> This method may only be invoked by the task scope owner or threads contained
     * in the task scope.
     *
     * @apiNote
     * There may be threads that have not finished because they are executing code that
     * did not respond (or respond promptly) to thread interrupt. This method does not wait
     * for these threads. When the owner invokes the {@link #close() close} method
     * to close the task scope then it will wait for the remaining threads to finish.
     *
     * @throws IllegalStateException if this task scope is closed
     * @throws WrongThreadException if the current thread is not the task scope owner or
     * a thread contained in the task scope
     */
    public void shutdown() {
        ensureOwnerOrContainsThread();
        if (implShutdown())
            flock.wakeup();
    }

    /**
     * Closes this task scope.
     *
     * <p> This method first shuts down the task scope (as if by invoking the {@link
     * #shutdown() shutdown} method). It then waits for the threads executing any
     * unfinished tasks to finish. If interrupted then this method will continue to
     * wait for the threads to finish before completing with the interrupt status set.
     *
     * <p> This method may only be invoked by the task scope owner. If the task scope
     * is already closed then the task scope owner invoking this method has no effect.
     *
     * <p> A {@code StructuredTaskScope} is intended to be used in a <em>structured
     * manner</em>. If this method is called to close a task scope before nested task
     * scopes are closed then it closes the underlying construct of each nested task scope
     * (in the reverse order that they were created in), closes this task scope, and then
     * throws {@link StructureViolationException}.
     *
     * Similarly, if this method is called to close a task scope while executing with
     * {@linkplain ScopedValue scoped value} bindings, and the task scope was created
     * before the scoped values were bound, then {@code StructureViolationException} is
     * thrown after closing the task scope.
     *
     * If a thread terminates without first closing task scopes that it owns then
     * termination will cause the underlying construct of each of its open tasks scopes to
     * be closed. Closing is performed in the reverse order that the task scopes were
     * created in. Thread termination may therefore be delayed when the task scope owner
     * has to wait for threads forked in these task scopes to finish.
     *
     * @throws IllegalStateException thrown after closing the task scope if the task scope
     * owner did not invoke join after forking
     * @throws WrongThreadException if the current thread is not the task scope owner
     * @throws StructureViolationException if a structure violation was detected
     */
    @Override
    public void close() {
        ensureOwner();
        if (isClosed())
            return;

        try {
            implShutdown();
            flock.close();
        } finally {
            state = CLOSED;
        }

        if (needJoin) {
            throw newIllegalStateExceptionNoJoin();
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        String name = flock.name();
        if (name != null) {
            sb.append(name);
            sb.append('/');
        }
        sb.append(Objects.toIdentityString(this));
        int s = state;
        if (s == CLOSED)
            sb.append("/closed");
        else if (s == SHUTDOWN)
            sb.append("/shutdown");
        return sb.toString();
    }

    /**
     * Runs the task specified to fork. If the task completes before the scope is shutdown
     * then it is runs the handleComplete method with the task result or exception.
     */
    private static class TaskRunner<T> implements Runnable {
        private static final AltResult RESULT_NULL = new AltResult(Handle.State.SUCCESS);
        private static final AltResult RESULT_CANCELLED = new AltResult(Handle.State.CANCELLED);
        private static final VarHandle RESULT;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                RESULT = l.findVarHandle(TaskRunner.class, "result", Object.class);
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private record AltResult(Handle.State state, Throwable exception) {
            AltResult(Handle.State state) {
                this(state, null);
            }
            AltResult(Throwable ex) {
                this(Handle.State.FAILED, ex);
            }
        }

        private final StructuredTaskScope<T> scope;
        private final Callable<T> task;
        private volatile Object result;

        @SuppressWarnings("unchecked")
        TaskRunner(StructuredTaskScope<? super T> scope, Callable<? extends T> task) {
            this.scope = (StructuredTaskScope<T>) scope;
            this.task = (Callable<T>) task;
        }

        /**
         * Set result to "CANCELLED" if not already set.
         */
        boolean tryCancel() {
            return RESULT.compareAndSet(this, null, RESULT_CANCELLED);
        }

        @Override
        public void run() {
            T result = null;
            Throwable ex = null;
            try {
                result = task.call();
            } catch (Throwable e) {
                ex = e;
            }

            // invoke handleComplete if the scope has not shutdown
            if (!scope.isShutdown()) {
                if (ex == null) {
                    // task succeeded
                    Object r = (result != null) ? result : RESULT_NULL;
                    if (RESULT.compareAndSet(this, null, r)) {
                        scope.handleComplete(task, result, null);
                    }
                } else {
                    // task failed
                    if (RESULT.compareAndSet(this, null, new AltResult(ex))) {
                        scope.handleComplete(task, null, ex);
                    }
                }
            }
        }

        /**
         * Returns the result if the task completed normally.
         */
        T result() {
            scope.ensureJoinedIfOwner();
            Object result = this.result;
            if (result instanceof AltResult) {
                if (result == RESULT_NULL) return null;
            } else if (result != null) {
                @SuppressWarnings("unchecked")
                T r = (T) result;
                return r;
            }

            throw new IllegalStateException();
        }

        /**
         * Returns the exception if the task completed abnormally.
         */
        Throwable exception() {
            scope.ensureJoinedIfOwner();
            if (result instanceof AltResult alt && alt.state() == Handle.State.FAILED) {
                return alt.exception();
            }
            throw new IllegalStateException();
        }



        /**
         * Returns the state.
         */
        Handle.State state() {
            Object result = this.result;
            if (result == null && scope.isShutdown()) {
                // cancelled
                tryCancel();
                result = this.result;
            }
            if (result == null) {
                return Handle.State.RUNNING;
            } else if (result instanceof AltResult alt) {
                return alt.state();
            } else {
                return Handle.State.SUCCESS;
            }
        }

        /**
         * Return string representing the state.
         */
        String stateAsString() {
            return switch (state()) {
                case RUNNING   -> "[Not completed]";
                case SUCCESS   -> "[Completed normally]";
                case FAILED    -> {
                    Throwable ex = ((AltResult) result).exception();
                    yield "[Completed exceptionally: " + ex + "]";
                }
                case CANCELLED -> "[Cancelled]";
            };
        }
    }

//    /**
//     * The object to supply the task result, returned by the fork method.
//     */
//    private static class ResultSupplier<T> implements Supplier<T> {
//        private final TaskRunner<T> runner;
//        ResultSupplier(TaskRunner<T> runner) {
//            this.runner = runner;
//        }
//        @Override
//        public T get() {
//            return runner.result();
//        }
//        @Override
//        public String toString() {
//            return Objects.toIdentityString(this ) + runner.stateAsString();
//        }
//    }

    /**
     * The handle returned by the fork method.
     */
    private static class HandleImpl<T> implements Handle<T> {
        private final TaskRunner<T> runner;

        HandleImpl(TaskRunner<T> runner) {
            this.runner = runner;
        }

        @Override
        public State state() {
            return runner.state();
        }

        @Override
        public T result() {
            return runner.result();
        }

        @Override
        public Throwable exception() {
            return runner.exception();
        }

        @Override
        public String toString() {
            return Objects.toIdentityString(this ) + runner.stateAsString();
        }
    }

    /**
     * A {@code StructuredTaskScope} that captures the result of the first subtask to
     * complete successfully. Once captured, it invokes the {@linkplain #shutdown() shutdown}
     * method to interrupt unfinished threads and wakeup the task scope owner. The policy
     * implemented by this class is intended for cases where the result of any subtask
     * will do ("invoke any") and where the results of other unfinished subtask are no
     * longer needed.
     *
     * <p> Unless otherwise specified, passing a {@code null} argument to a method
     * in this class will cause a {@link NullPointerException} to be thrown.
     *
     * @param <T> the result type
     * @since 21
     */
    @PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
    public static final class ShutdownOnSuccess<T> extends StructuredTaskScope<T> {
        private static final Object RESULT_NULL = new Object();
        private static final VarHandle FIRST_RESULT;
        private static final VarHandle FIRST_EXCEPTION;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                FIRST_RESULT = l.findVarHandle(ShutdownOnSuccess.class, "firstResult", Object.class);
                FIRST_EXCEPTION = l.findVarHandle(ShutdownOnSuccess.class, "firstException", Throwable.class);
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        private volatile Object firstResult;
        private volatile Throwable firstException;

        /**
         * Constructs a new {@code ShutdownOnSuccess} with the given name and thread factory.
         * The task scope is optionally named for the purposes of monitoring and management.
         * The thread factory is used to {@link ThreadFactory#newThread(Runnable) create}
         * threads when tasks are {@linkplain #fork(Callable) forked}. The task scope is
         * owned by the current thread.
         *
         * <p> This method captures the current thread's {@linkplain ScopedValue scoped value}
         * bindings for inheritance by threads created in the task scope. The
         * <a href="StructuredTaskScope.html#TreeStructure">Tree Structure</a> section in
         * the class description details how parent-child relations are established
         * implicitly for the purpose of inheritance of scoped value bindings.
         *
         * @param name the name of the task scope, can be null
         * @param factory the thread factory
         */
        public ShutdownOnSuccess(String name, ThreadFactory factory) {
            super(name, factory);
        }

        /**
         * Constructs a new unnamed {@code ShutdownOnSuccess} that creates virtual threads.
         *
         * <p> This constructor is equivalent to invoking the 2-arg constructor with a
         * name of {@code null} and a thread factory that creates virtual threads.
         */
        public ShutdownOnSuccess() {
            super(null, Thread.ofVirtual().factory());
        }

        /**
         * Shut down the given task scope when invoked for the first time with a task
         * that completed with a result.
         *
         * @throws IllegalArgumentException {@inheritDoc}
         */
        @Override
        protected void handleComplete(Callable<T> task, T result, Throwable exception) {
            super.handleComplete(task, result, exception);

            if (firstResult != null) {
                // already captured a result
                return;
            }

            if (exception == null) {
                // task succeeded
                Object r = (result != null) ? result : RESULT_NULL;
                if (FIRST_RESULT.compareAndSet(this, null, r)) {
                    shutdown();
                }
            } else if (firstException == null) {
                // capture the exception thrown by the first task that failed
                FIRST_EXCEPTION.compareAndSet(this, null, exception);
            }
        }

        /**
         * {@inheritDoc}
         * @return this task scope
         * @throws IllegalStateException {@inheritDoc}
         * @throws WrongThreadException {@inheritDoc}
         */
        @Override
        public ShutdownOnSuccess<T> join() throws InterruptedException {
            super.join();
            return this;
        }

        /**
         * {@inheritDoc}
         * @return this task scope
         * @throws IllegalStateException {@inheritDoc}
         * @throws WrongThreadException {@inheritDoc}
         */
        @Override
        public ShutdownOnSuccess<T> joinUntil(Instant deadline)
            throws InterruptedException, TimeoutException
        {
            super.joinUntil(deadline);
            return this;
        }

        /**
         * {@return the result of the first subtask that completed with a result}
         *
         * <p> When no subtask completed with a result but a task completed with an
         * exception then {@code ExecutionException} is thrown with the exception as the
         * {@linkplain Throwable#getCause() cause}.
         *
         * @throws ExecutionException if no subtasks completed with a result but a subtask
         * completed with an exception
         * @throws IllegalStateException if the handleComplete method was not invoked with a
         * completed subtask or the task scope owner did not invoke join after forking
         * @throws WrongThreadException if the current thread is not the task scope owner
         */
        public T result() throws ExecutionException {
            ensureOwnerAndJoined();

            Object result = firstResult;
            if (result == RESULT_NULL) {
                return null;
            } else if (result != null) {
                @SuppressWarnings("unchecked")
                T r = (T) result;
                return r;
            }

            Throwable ex = firstException;
            if (ex != null) {
                throw new ExecutionException(ex);
            }

            throw new IllegalStateException("No completed subtasks");
        }

        /**
         * Returns the result of the first subtask that completed with a result, otherwise
         * throws an exception produced by the given exception supplying function.
         *
         * <p> When no subtask completed with a result but a subtask completed with an
         * exception then the exception supplying function is invoked with the exception.
         *
         * @param esf the exception supplying function
         * @param <X> type of the exception to be thrown
         * @return the result of the first subtask that completed with a result
         *
         * @throws X if no subtask completed with a result
         * @throws IllegalStateException if the handleComplete method was not invoked with a
         * completed subtask or the task scope owner did not invoke join after forking
         * @throws WrongThreadException if the current thread is not the task scope owner
         */
        public <X extends Throwable> T result(Function<Throwable, ? extends X> esf) throws X {
            Objects.requireNonNull(esf);
            ensureOwnerAndJoined();

            Object result = firstResult;
            if (result == RESULT_NULL) {
                return null;
            } else if (result != null) {
                @SuppressWarnings("unchecked")
                T r = (T) result;
                return r;
            }

            Throwable exception = firstException;
            if (exception != null) {
                X ex = esf.apply(exception);
                Objects.requireNonNull(ex, "esf returned null");
                throw ex;
            }

            throw new IllegalStateException("No completed subtasks");
        }
    }

    /**
     * A {@code StructuredTaskScope} that captures the exception of the first subtask to
     * complete abnormally. Once captured, it invokes the {@linkplain #shutdown() shutdown}
     * method to interrupt unfinished threads and wakeup the task scope owner. The policy
     * implemented by this class is intended for cases where the results for all subtasks
     * are required ("invoke all"); if any subtask fails then the results of other
     * unfinished subtasks are no longer needed.
     *
     * <p> Unless otherwise specified, passing a {@code null} argument to a method
     * in this class will cause a {@link NullPointerException} to be thrown.
     *
     * @since 21
     */
    @PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
    public static final class ShutdownOnFailure extends StructuredTaskScope<Object> {
        private static final VarHandle FIRST_EXCEPTION;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                FIRST_EXCEPTION = l.findVarHandle(ShutdownOnFailure.class, "firstException", Throwable.class);
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        private volatile Throwable firstException;

        /**
         * Constructs a new {@code ShutdownOnFailure} with the given name and thread factory.
         * The task scope is optionally named for the purposes of monitoring and management.
         * The thread factory is used to {@link ThreadFactory#newThread(Runnable) create}
         * threads when tasks are {@linkplain #fork(Callable) forked}. The task scope
         * is owned by the current thread.
         *
         * <p> This method captures the current thread's {@linkplain ScopedValue scoped value}
         * bindings for inheritance by threads created in the task scope. The
         * <a href="StructuredTaskScope.html#TreeStructure">Tree Structure</a> section in
         * the class description details how parent-child relations are established
         * implicitly for the purpose of inheritance of scoped value bindings.
         *
         * @param name the name of the task scope, can be null
         * @param factory the thread factory
         */
        public ShutdownOnFailure(String name, ThreadFactory factory) {
            super(name, factory);
        }

        /**
         * Constructs a new unnamed {@code ShutdownOnFailure} that creates virtual threads.
         *
         * <p> This constructor is equivalent to invoking the 2-arg constructor with a
         * name of {@code null} and a thread factory that creates virtual threads.
         */
        public ShutdownOnFailure() {
            super(null, Thread.ofVirtual().factory());
        }

        /**
         * Shut down the given task scope when invoked for the first time with a task
         * that completed abnormally.
         *
         * @throws IllegalArgumentException {@inheritDoc}
         */
        @Override
        protected void handleComplete(Callable<Object> task, Object result, Throwable exception) {
            super.handleComplete(task, result, exception);
            if (exception != null
                    && firstException == null
                    && FIRST_EXCEPTION.compareAndSet(this, null, exception)) {
                shutdown();
            }
        }

        /**
         * {@inheritDoc}
         * @return this task scope
         * @throws IllegalStateException {@inheritDoc}
         * @throws WrongThreadException {@inheritDoc}
         */
        @Override
        public ShutdownOnFailure join() throws InterruptedException {
            super.join();
            return this;
        }

        /**
         * {@inheritDoc}
         * @return this task scope
         * @throws IllegalStateException {@inheritDoc}
         * @throws WrongThreadException {@inheritDoc}
         */
        @Override
        public ShutdownOnFailure joinUntil(Instant deadline)
            throws InterruptedException, TimeoutException
        {
            super.joinUntil(deadline);
            return this;
        }

        /**
         * Returns the exception for the first subtask that completed abnormally. If no
         * subtasks completed abnormally then an empty {@code Optional} is returned.
         *
         * @return the exception for a subtask that completed abnormally or an empty
         * optional if no subtasks completed abnormally
         *
         * @throws WrongThreadException if the current thread is not the task scope owner
         * @throws IllegalStateException if the task scope owner did not invoke join after
         * forking
         */
        public Optional<Throwable> exception() {
            ensureOwnerAndJoined();
            return Optional.ofNullable(firstException);
        }

        /**
         * Throws if a subtask completed abnormally. If any subtask completed with an
         * exception then {@code ExecutionException} is thrown with the exception of the
         * first subtask to fail as the {@linkplain Throwable#getCause() cause}.
         * This method does nothing if no subtasks completed abnormally.
         *
         * @throws ExecutionException if a subtask completed with an exception
         * @throws WrongThreadException if the current thread is not the task scope owner
         * @throws IllegalStateException if the task scope owner did not invoke join after
         * forking
         */
        public void throwIfFailed() throws ExecutionException {
            ensureOwnerAndJoined();
            Throwable exception = firstException;
            if (exception != null)
                throw new ExecutionException(exception);
        }

        /**
         * Throws the exception produced by the given exception supplying function if
         * a subtask completed abnormally. If any subtask completed with an exception then
         * the function is invoked with the exception of the first subtask to fail. The
         * exception returned by the function is thrown.
         * This method does nothing if no subtasks completed abnormally.
         *
         * @param esf the exception supplying function
         * @param <X> type of the exception to be thrown
         *
         * @throws X produced by the exception supplying function
         * @throws WrongThreadException if the current thread is not the task scope owner
         * @throws IllegalStateException if the task scope owner did not invoke join after
         * forking
         */
        public <X extends Throwable>
        void throwIfFailed(Function<Throwable, ? extends X> esf) throws X {
            ensureOwnerAndJoined();
            Objects.requireNonNull(esf);
            Throwable exception = firstException;
            if (exception != null) {
                X ex = esf.apply(exception);
                Objects.requireNonNull(ex, "esf returned null");
                throw ex;
            }
        }
    }
}
