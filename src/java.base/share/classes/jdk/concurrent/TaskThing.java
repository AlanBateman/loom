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

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;

/**
 * Represents a task that is running or has completed.
 *
 * @param <T> The result type
 * @since 99
 */
public sealed interface TaskThing<T> permits ThingImpl.ThreadBoundFuture {

    /**
     * Represent the status of a task.
     *
     * <p> The status of a task is {@link #RUNNING} when initially {@link
     * Thing#fork(Callable) forked}. It changes to a terminal state when
     * the task completes or is {@link Future#cancel(boolean) cancelled}.
     *
     * @since 99
     */
    enum Status {
        /**
         * The task has not completed.
         */
        RUNNING,
        /**
         * The task completed with a result. The result can be obtained with the
         * {@link #result()} method.
         */
        SUCCESS,
        /**
         * The task completed with an exception. The exception can be obtained
         * with the {@link #exception()} method.
         */
        FAILURE,
        /**
         * The task has been cancelled.
         * @see Future#cancel(boolean)
         */
        CANCELLED;
    }

    /**
     * {@return the task status}.
     */
    default Status status() {
        Future<T> future = asFuture();
        if (future.isDone()) {
            if (future.isCompletedNormally())
                return Status.SUCCESS;
            if (future.isCancelled())
                return Status.CANCELLED;
            return Status.FAILURE;
        } else {
            return Status.RUNNING;
        }
    }

    /**
     * {@return the task that was {@link Thing#fork(Callable) forked}}.
     */
    Callable<T> task();

    /**
     * {@return the result of the task}.
     * @throws IllegalStateException if the task has not completed or it did not
     * complete normally
     */
    default T result() {
        Future<T> future = asFuture();
        if (future.isDone()) {
            try {
                return future.join();
            } catch (CompletionException | CancellationException e) { }
        }
        throw new IllegalStateException();
    }

    /**
     * {@return the exception thrown by the task}. If cancelled, this method
     * returns a {@code CancellationException}.
     * @throws IllegalStateException if the task has not completed or the task
     * completed normally
     */
    default Throwable exception() {
        Future<T> future = asFuture();
        if (future.isDone()) {
            if (future.isCancelled()) {
                return new CancellationException();
            }
            try {
                future.join();
            } catch (CompletionException e) {
                Throwable cause = e.getCause();
                assert cause != null;
                return cause;
            }
        }
        throw new IllegalStateException();
    }

    /**
     * {@return a Future that may be used to wait on or cancel the task}.
     */
    Future<T> asFuture();
}
