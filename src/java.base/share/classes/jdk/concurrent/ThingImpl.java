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

import java.security.AccessController;
import java.security.Permission;
import java.security.PrivilegedAction;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.misc.InnocuousThread;
import jdk.internal.vm.ThreadContainer;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * A basic implementation of Thing.
 */
final class ThingImpl<T> implements Thing<T>, ThreadContainer {
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();
    private static final Permission MODIFY_THREAD = new RuntimePermission("modifyThread");

    private final Set<ThreadBoundFuture<T>> tasks = ConcurrentHashMap.newKeySet();
    private final ReentrantLock terminationLock = new ReentrantLock();
    private final Condition terminationCondition = terminationLock.newCondition();

    private final ThreadFactory factory;
    private final Thread owner;
    private volatile ThreadContainer previous; // when nested

    // states: OPEN -> CLOSING -> CLOSED
    private static final int OPEN      = 0;
    private static final int CLOSING   = 1;
    private static final int CLOSED    = 2;
    private volatile int state;

    // number of threads started
    private int tasksStarted;

    // completed tasks
    private LinkedTransferQueue<TaskThing<T>> queue = new LinkedTransferQueue<>();

    // number of tasks taken from queue
    private int taken;

    // deadline support
    private volatile Future<?> timerTask;
    private volatile boolean deadlineExpired;  // set to true if deadline expired
    private final Object closeLock = new Object();

    ThingImpl(ThreadFactory factory) {
        this.factory = Objects.requireNonNull(factory);
        this.owner = Thread.currentThread();
        JLA.pushThreadContainer(this);
    }

    @SuppressWarnings("removal")
    private void checkPermission() {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(MODIFY_THREAD);
        }
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalCallerException();
        }
    }

    private void ensureOpen() {
        if (state >= CLOSING)
            throw new IllegalStateException("Closed");
    }

    @Override
    public Thing<T> disableCompletions() {
        checkOwner();
        ensureOpen();
        if (tasksStarted > 0)
            throw new IllegalStateException();
        queue = null;
        return this;
    }

    @Override
    public Thing<T> deadline(Instant deadline) {
        Objects.requireNonNull(deadline);
        checkOwner();
        ensureOpen();
        if (timerTask != null || deadlineExpired)
            throw new IllegalStateException("Deadline already set");
        Duration timeout = Duration.between(Instant.now(), deadline);
        if (timeout.isZero() || timeout.isNegative()) {
            deadlineExpired = true;
            Thread.currentThread().interrupt();
        } else {
            long nanos = NANOSECONDS.convert(timeout);
            this.timerTask = TimerSupport.schedule(this::timeout, nanos, NANOSECONDS);
        }
        return this;
    }

    @Override
    public Thread owner() {
        return owner;
    }

    @Override
    public ThreadContainer previous() {
        return previous;
    }

    @Override
    public void setPrevious(ThreadContainer previous) {
        this.previous = previous;
    }

    @Override
    public long threadCount() {
        return tasks.size();
    }

    @Override
    public Stream<Thread> threads() {
        return tasks.stream().map(ThreadBoundFuture::thread);
    }

    /**
     * Creates a new unstarted thread to run the given task.
     */
    private Thread newThread(Runnable task) {
        Thread thread = factory.newThread(task);
        if (thread == null)
            throw new RejectedExecutionException();
        return thread;
    }

    /**
     * Called from the thread executing a task when the task is finished
     * and the thread is ready to terminate.
     */
    private void taskFinish(ThreadBoundFuture<T> task) {
        assert Thread.currentThread() == task.thread();

        boolean removed = tasks.remove(task);
        assert removed;
        if (state >= CLOSING && tasks.isEmpty()) {
            // signal owner
            terminationLock.lock();
            try {
                terminationCondition.signalAll();
            } finally {
                terminationLock.unlock();
            }
        }

        // close any containers that the thread forgot to close
        ThreadContainer container = JLA.headThreadContainer(task.thread());
        while (container != null) {
            if (container instanceof ThingImpl<?> thing) {
                assert thing.owner() == Thread.currentThread();
                thing.close();;
            }
            container = container.previous();
        }
    }

    /**
     * Cancels remaining tasks.
     */
    @SuppressWarnings("removal")
    private void cancelRemainingTasks() {
        PrivilegedAction<Void> pa = () -> {
            tasks.forEach(t -> {
                Future<T> f = t.asFuture();
                if (!f.isDone()) {
                    f.cancel(true);
                }
            });
            return null;
        };
        AccessController.doPrivileged(pa);
    }

    /**
     * Waits until all tasks terminate, cancel remaining tasks and continue
     * waiting if interrupted.
     */
    private void awaitTermination() {
        if (!tasks.isEmpty()) {
            boolean interrupted = false;
            terminationLock.lock();
            try {
                while (!tasks.isEmpty()) {
                    try {
                        terminationCondition.await();
                    } catch (InterruptedException e) {
                        interrupted = true;
                        cancelRemainingTasks();
                    }
                }
            } finally {
                terminationLock.unlock();
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Override
    public <V extends T> TaskThing<V> fork(Callable<V> task) {
        Objects.requireNonNull(task);
        checkOwner();
        ensureOpen();

        @SuppressWarnings("unchecked")
        var callable = (Callable<T>) task;
        var future = new ThreadBoundFuture<T>(this, callable);

        // Add dependent task to queue task when completed. This ensures the
        // task is queued promptly when cancelled. This is important for tasks
        // that do not respond to interrupt as they may not terminate.
        LinkedTransferQueue<TaskThing<T>> q = this.queue;
        if (queue != null) {
            future.handle((r, e) -> {
                q.add(future);
                return null;
            });
        }

        // start thread to run the task
        tasks.add(future);
        boolean started = false;
        try {
            if (deadlineExpired)
                throw new RejectedExecutionException();
            // start thread in this container
            JLA.start(future.thread(), this);
            started = true;
        } finally {
            if (!started) {
                tasks.remove(future);
            }
        }
        tasksStarted++;

        @SuppressWarnings("unchecked")
        var result = (ThreadBoundFuture<V>) future;
        return result;
    }

    @Override
    public Iterable<TaskThing<? extends T>> completions() {
        checkOwner();
        ensureOpen();
        if (queue != null) {
            return TaskIterator::new;
        } else {
            // return Iterable that creates an "empty" iterators
            var iterator = new Iterator<TaskThing<? extends T>>() {
                @Override
                public boolean hasNext() {
                    return false;
                }
                @Override
                public TaskThing<T> next() {
                    throw new NoSuchElementException();
                }
            };
            return () -> iterator;
        }
    }

    @Override
    public Thing<T> awaitRemaining() {
        checkOwner();
        ensureOpen();
        boolean interrupted = false;
        try {
            Iterator<ThreadBoundFuture<T>> iterator = tasks.iterator();
            while (iterator.hasNext()) {
                ThreadBoundFuture<T> future = iterator.next();
                if (!future.isDone()) {
                    try {
                        future.asFuture().get();
                    } catch (InterruptedException e) {
                        interrupted = true;
                        cancelRemainingTasks();
                    } catch (ExecutionException | CancellationException e) { }
                }
            }
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
        return this;
    }

    @Override
    public Thing<T> cancelRemaining() {
        checkOwner();
        ensureOpen();
        cancelRemainingTasks();
        return this;
    }

    @Override
    public void close() {
        checkPermission();
        checkOwner();
        if (state == CLOSED)
            return;
        state = CLOSING;

        // coordinate with timeout task
        Future<?> timer = this.timerTask;
        if (timer != null) {
            synchronized (closeLock) { }
        }

        // wait for tasks to complete
        try {
            awaitTermination();
        } finally {
            JLA.popThreadContainer(this);
            state = CLOSED;
        }

        // cancel timer
        if (timer != null && !timer.isDone()) {
            timer.cancel(false);
        }

        // throw if the deadline expired
        if (deadlineExpired) {
            throw new DeadlineExpiredException();
        }
    }

    /**
     * Invoked when the timeout expires.
     */
    @SuppressWarnings("removal")
    private void timeout() {
        deadlineExpired = true;

        if (state < CLOSED) {
            // timer task may need permission to interrupt threads
            PrivilegedAction<Void> pa = () -> {

                // cancel remaining
                tasks.forEach(t -> {
                    Future<T> f = t.asFuture();
                    if (!f.isDone()) {
                        f.cancel(true);
                    }
                });

                // interrupt owner if it hasn't invoked close
                synchronized (closeLock) {
                    if (state < CLOSING) {
                        owner.interrupt();
                    }
                }
                return null;
            };
            AccessController.doPrivileged(pa, null, MODIFY_THREAD);
        }
    }

    /**
     * The TaskThing for a task that runs in its own thread. The thread is
     * created (but not started) when the TaskThing is created. The thread
     * is interrupted when the task is cancelled.
     */
    static final class ThreadBoundFuture<T>
            extends CompletableFuture<T>
            implements Runnable, TaskThing<T> {

        final ThingImpl<T> thing;
        final Callable<T> task;
        final Thread thread;

        ThreadBoundFuture(ThingImpl<T> thing, Callable<T> task) {
            this.thing = thing;
            this.task = task;
            this.thread = thing.newThread(this);
        }

        Thread thread() {
            return thread;
        }

        @Override
        public void run() {
            if (Thread.currentThread() != thread) {
                // should not happen except where something casts this object
                // to a Runnable and invokes the run method.
                throw new IllegalCallerException();
            }
            try {
                T result = task.call();
                complete(result);
            } catch (Throwable e) {
                completeExceptionally(e);
            } finally {
                thing.taskFinish(this);
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled && mayInterruptIfRunning)
                thread.interrupt();
            return cancelled;
        }

        @Override
        public Callable<T> task() {
            return task;
        }

        @Override
        public Future<T> asFuture() {
            return this;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("[");
            sb.append(task);
            sb.append(": ");
            if (isDone()) {
                if (isCompletedNormally()) {
                    sb.append("Completed normally");
                } else {
                    sb.append("Completed exceptionally(" + exception() + ")");
                }
            } else {
                sb.append("Not completed");
            }
            sb.append("]");
            return sb.toString();
        }
    }

    /**
     * An Iterator that takes elements from a blocking queue.
     */
    private class TaskIterator<T>
            implements Iterator<TaskThing<? extends T>> {

        private TaskThing<T> next;
        private boolean finished;

        private TaskThing<T> fetchNext() {
            if (finished)
                return null;
            if (taken >= tasksStarted) {
                finished = true;
                return null;
            }

            // try polling the queue when interrupted
            if (Thread.currentThread().isInterrupted()) {
                @SuppressWarnings("unchecked")
                var task = (TaskThing<T>) queue.poll();
                if (task != null) {
                    taken++;
                    return task;
                }
            }

            boolean interrupted = false;
            try {
                for (;;) {
                    try {
                        @SuppressWarnings("unchecked")
                        var task = (TaskThing<T>) queue.take();
                        taken++;
                        return task;
                    } catch (InterruptedException e) {
                        interrupted = true;
                        cancelRemaining();
                    }
                }
            } finally {
                if (interrupted)
                    Thread.currentThread().interrupt();
            }
        }

        @Override
        public boolean hasNext() {
            checkOwner();
            ensureOpen();
            if (next == null) {
                next = fetchNext();
                return (next != null);
            } else {
                return true;
            }
        }

        @Override
        public TaskThing<T> next() {
            checkOwner();
            ensureOpen();
            TaskThing<T> result = next;
            if (result == null && hasNext()) {
                result = next;
            }
            if (result != null) {
                next = null;
                return result;
            } else {
                throw new NoSuchElementException();
            }
        }
    }

    /**
     * Encapsulates a ScheduledThreadPoolExecutor for scheduling tasks.
     */
    private static class TimerSupport {
        private static final ScheduledThreadPoolExecutor STPE;
        static {
            STPE = new ScheduledThreadPoolExecutor(0, task -> {
                Thread thread = InnocuousThread.newThread(task);
                thread.setDaemon(true);
                return thread;
            });
            STPE.setRemoveOnCancelPolicy(true);
        }
        static ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
            return STPE.schedule(task, delay, unit);
        }
    }
}
