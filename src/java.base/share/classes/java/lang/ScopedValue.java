/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2020, 2022, Red Hat Inc.
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

package java.lang;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.lang.ref.Reference;
import java.util.concurrent.Callable;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructureViolationException;
import java.util.function.Supplier;
import jdk.internal.access.JavaUtilConcurrentTLRAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.javac.PreviewFeature;
import jdk.internal.vm.annotation.ForceInline;
import jdk.internal.vm.annotation.Hidden;
import jdk.internal.vm.ScopedValueContainer;
import sun.security.action.GetPropertyAction;

/**
 * A value that is set once and is then available for reading for a bounded period of
 * execution by a thread. A {@code ScopedValue} allows for safely and efficiently sharing
 * data for a bounded period of execution without passing the data as method arguments.
 *
 * <p> {@code ScopedValue} defines the {@link #runWhere(ScopedValue, Object, Runnable)}
 * method to set the value of a {@code ScopedValue} for the bounded period of execution by
 * a thread of the runnable's {@link Runnable#run() run} method. The unfolding execution of
 * the methods executed by {@code run} defines a <b><em>dynamic scope</em></b>. The scoped
 * value is {@linkplain #isBound() bound} while executing in the dynamic scope, it reverts
 * to being <em>unbound</em> when the {@code run} method completes (normally or with an
 * exception). Code executing in the dynamic scope uses the {@code ScopedValue} {@link
 * #get() get} method to read its value.
 *
 * <p> Like a {@linkplain ThreadLocal thread-local variable}, a scoped value has multiple
 * incarnations, one per thread. The particular incarnation that is used depends on which
 * thread calls its methods.
 *
 * <p> Consider the following example with a scoped value {@code USERNAME} that is
 * <em>bound</em> to the value "{@code duke}" for the execution, by a thread, of a run
 * method that invokes {@code doSomething()}.
 * {@snippet lang=java :
 *     // @link substring="newInstance" target="#newInstance" :
 *     private static final ScopedValue<String> USERNAME = ScopedValue.newInstance();
 *
 *     ScopedValue.runWhere(USERNAME, "duke", () -> doSomething());
 * }
 * Code executed directly or indirectly by {@code doSomething()} that invokes {@code
 * USERNAME.get()} will read the value "{@code duke}". The scoped value is bound while
 * executing {@code doSomething()} and becomes unbound when {@code doSomething()}
 * completes (normally or with an exception). If one thread were to call {@code
 * doSomething()} with {@code USERNAME} bound to "{@code duke1}", and another thread
 * were to call the method with {@code USERNAME} bound to "{@code duke2}", then
 * {@code USERNAME.get()} would read the value "{@code duke1}" or "{@code duke2}",
 * depending on which thread is executing.
 *
 * <p> In addition to the {@code runWhere} method that executes a {@code run} method, {@code
 * ScopedValue} defines the {@link #callWhere(ScopedValue, Object, Callable)} and
 * {@link #getWhere(ScopedValue, Object, Supplier)} methods to execute
 * methods that returns results. It also defines the {@link #where(ScopedValue, Object)}
 * method for cases where it is useful to accumulate mappings of multiple {@code ScopedValue}s to
 * bound values.
 *
 * <p> A {@code ScopedValue} will typically be declared in a {@code final} and {@code
 * static} field. The accessibility of the field will determine which components can
 * bind or read its value.
 *
 * <p> Unless otherwise specified, passing a {@code null} argument to a method in this
 * class will cause a {@link NullPointerException} to be thrown.
 *
 * <h2><a id="rebind">Rebinding</a></h2>
 *
 * The {@code ScopedValue} API allows a new binding to be established for <em>nested
 * dynamic scopes</em>. This is known as <em>rebinding</em>. A {@code ScopedValue} that
 * is bound to some value may be bound to a new value for the bounded execution of some
 * method. The unfolding execution of code executed by that method defines the nested
 * dynamic scope. When the method completes (normally or with an exception), the value of
 * the {@code ScopedValue} reverts to its previous value.
 *
 * <p> In the above example, suppose that code executed by {@code doSomething()} binds
 * {@code USERNAME} to a new value with:
 * {@snippet lang=java :
 *     ScopedValue.runWhere(USERNAME, "duchess", () -> doMore());
 * }
 * Code executed directly or indirectly by {@code doMore()} that invokes {@code
 * USERNAME.get()} will read the value "{@code duchess}". When {@code doMore()} completes
 * (normally or with an exception), the value of {@code USERNAME} reverts to
 * "{@code duke}".
 *
 * <h2><a id="inheritance">Inheritance</a></h2>
 *
 * {@code ScopedValue} supports sharing data across threads. This sharing is limited to
 * structured cases where child threads are started and terminate within the bounded
 * period of execution by a parent thread. More specifically, when using a {@link
 * StructuredTaskScope}, scoped value bindings are <em>captured</em> when creating a
 * {@code StructuredTaskScope} and inherited by all threads started in that scope with
 * the {@link StructuredTaskScope#fork(Callable) fork} method.
 *
 * <p> In the following example, the {@code ScopedValue} {@code USERNAME} is bound to the
 * value "{@code duke}" for the execution of a runnable operation. The code in the {@code
 * run} method creates a {@code StructuredTaskScope} and forks three child threads. Code
 * executed directly or indirectly by these threads running {@code childTask1()},
 * {@code childTask2()}, and {@code childTask3()} will read the value "{@code duke}".
 *
 * {@snippet lang=java :
 *     private static final ScopedValue<String> USERNAME = ScopedValue.newInstance();

 *     ScopedValue.runWhere(USERNAME, "duke", () -> {
 *         try (var scope = new StructuredTaskScope<String>()) {
 *
 *             scope.fork(() -> childTask1());
 *             scope.fork(() -> childTask2());
 *             scope.fork(() -> childTask3());
 *
 *             ...
 *          }
 *     });
 * }
 *
 * @implNote
 * Scoped values are designed to be used in fairly small
 * numbers. {@link #get} initially performs a search through enclosing
 * scopes to find a scoped value's innermost binding. It
 * then caches the result of the search in a small thread-local
 * cache. Subsequent invocations of {@link #get} for that scoped value
 * will almost always be very fast. However, if a program has many
 * scoped values that it uses cyclically, the cache hit rate
 * will be low and performance will be poor. This design allows
 * scoped-value inheritance by {@link StructuredTaskScope} threads to
 * be very fast: in essence, no more than copying a pointer, and
 * leaving a scoped-value binding also requires little more than
 * updating a pointer.
 *
 * <p>Because the scoped-value per-thread cache is small, clients
 * should minimize the number of bound scoped values in use. For
 * example, if it is necessary to pass a number of values in this way,
 * it makes sense to create a record class to hold those values, and
 * then bind a single {@code ScopedValue} to an instance of that record.
 *
 * <p>For this release, the reference implementation
 * provides some system properties to tune the performance of scoped
 * values.
 *
 * <p>The system property {@code java.lang.ScopedValue.cacheSize}
 * controls the size of the (per-thread) scoped-value cache. This cache is crucial
 * for the performance of scoped values. If it is too small,
 * the runtime library will repeatedly need to scan for each
 * {@link #get}. If it is too large, memory will be unnecessarily
 * consumed. The default scoped-value cache size is 16 entries. It may
 * be varied from 2 to 16 entries in size. {@code ScopedValue.cacheSize}
 * must be an integer power of 2.
 *
 * <p>For example, you could use {@code -Djava.lang.ScopedValue.cacheSize=8}.
 *
 * <p>The other system property is {@code jdk.preserveScopedValueCache}.
 * This property determines whether the per-thread scoped-value
 * cache is preserved when a virtual thread is blocked. By default
 * this property is set to {@code true}, meaning that every virtual
 * thread preserves its scoped-value cache when blocked. Like {@code
 * ScopedValue.cacheSize}, this is a space versus speed trade-off: in
 * situations where many virtual threads are blocked most of the time,
 * setting this property to {@code false} might result in a useful
 * memory saving, but each virtual thread's scoped-value cache would
 * have to be regenerated after a blocking operation.
 *
 * @param <T> the type of the object bound to this {@code ScopedValue}
 * @since 21
 */
@PreviewFeature(feature = PreviewFeature.Feature.SCOPED_VALUES)
public final class ScopedValue<T> {
    private final int hash;

    @Override
    public int hashCode() { return hash; }

    /**
     * An immutable map from {@code ScopedValue} to values.
     *
     * <p> Unless otherwise specified, passing a {@code null} argument to a constructor
     * or method in this class will cause a {@link NullPointerException} to be thrown.
     */
    static final class Snapshot {
        final Snapshot prev;
        final Carrier bindings;
        final int bitmask;

        private static final Object NIL = new Object();

        static final Snapshot EMPTY_SNAPSHOT = new Snapshot();

        Snapshot(Carrier bindings, Snapshot prev) {
            this.prev = prev;
            this.bindings = bindings;
            this.bitmask = bindings.bitmask | prev.bitmask;
        }

        protected Snapshot() {
            this.prev = null;
            this.bindings = null;
            this.bitmask = 0;
        }

        Object find(ScopedValue<?> key) {
            int bits = key.bitmask();
            for (Snapshot snapshot = this;
                 containsAll(snapshot.bitmask, bits);
                 snapshot = snapshot.prev) {
                for (Carrier carrier = snapshot.bindings;
                     carrier != null && containsAll(carrier.bitmask, bits);
                     carrier = carrier.prev) {
                    if (carrier.getKey() == key) {
                        Object value = carrier.get();
                        return value;
                    }
                }
            }
            return NIL;
        }
    }

    /**
     * A mapping of scoped values, as <em>keys</em>, to values.
     *
     * <p> A {@code Carrier} is used to accumlate mappings so that an operation (a
     * {@link Runnable} or {@link Callable}) can be executed with all scoped values in the
     * mapping bound to values. The following example runs an operation with {@code k1}
     * bound (or rebound) to {@code v1}, and {@code k2} bound (or rebound) to {@code v2}.
     * {@snippet lang=java :
     *     // @link substring="runWhere" target="#runWhere(ScopedValue, Object)" :
     *     ScopedValue.where(k1, v1).where(k2, v2).run(() -> ... );
     * }
     *
     * <p> A {@code Carrier} is immutable and thread-safe. The {@link
     * #where(ScopedValue, Object) where} method returns a new {@code Carrier} object,
     * it does not mutate an existing mapping.
     *
     * <p> Unless otherwise specified, passing a {@code null} argument to a method in
     * this class will cause a {@link NullPointerException} to be thrown.
     *
     * @since 21
     */
    @PreviewFeature(feature = PreviewFeature.Feature.SCOPED_VALUES)
    public static final class Carrier {
        // Bit masks: a 1 in postion n indicates that this set of bound values
        // hits that slot in the cache.
        final int bitmask;
        final ScopedValue<?> key;
        final Object value;
        final Carrier prev;

        Carrier(ScopedValue<?> key, Object value, Carrier prev) {
            this.key = key;
            this.value = value;
            this.prev = prev;
            int bits = key.bitmask();
            if (prev != null) {
                bits |= prev.bitmask;
            }
            this.bitmask = bits;
        }

        /**
         * Add a binding to this map, returning a new Carrier instance.
         */
        private static final <T> Carrier where(ScopedValue<T> key, T value,
                                               Carrier prev) {
            return new Carrier(key, value, prev);
        }

        /**
         * Returns a new {@code Carrier} with the mappings from this carrier plus a
         * new mapping from {@code key} to {@code value}. If this carrier already has a
         * mapping for the scoped value {@code key} then it will map to the new
         * {@code value}. The current carrier is immutable, so it is not changed by this
         * method.
         *
         * @param key the {@code ScopedValue} key
         * @param value the value, can be {@code null}
         * @param <T> the type of the value
         * @return a new {@code Carrier} with the mappings from this carrier plus the new mapping
         */
        public <T> Carrier where(ScopedValue<T> key, T value) {
            return where(key, value, this);
        }

        /*
         * Return a new set consisting of a single binding.
         */
        static <T> Carrier of(ScopedValue<T> key, T value) {
            return where(key, value, null);
        }

        final Object get() {
            return value;
        }

        final ScopedValue<?> getKey() {
            return key;
        }

        /**
         * Returns the value of a {@link ScopedValue} in this mapping.
         *
         * @param key the {@code ScopedValue} key
         * @param <T> the type of the value
         * @return the value
         * @throws NoSuchElementException if the key is not present in this mapping
         */
        @SuppressWarnings("unchecked")
        public <T> T get(ScopedValue<T> key) {
            var bits = key.bitmask();
            for (Carrier carrier = this;
                 carrier != null && containsAll(carrier.bitmask, bits);
                 carrier = carrier.prev) {
                if (carrier.getKey() == key) {
                    Object value = carrier.get();
                    return (T)value;
                }
            }
            throw new NoSuchElementException();
        }

        /**
         * Calls a value-returning operation with each scoped value in this mapping bound
         * to its value in the current thread.
         * When the operation completes (normally or with an exception), each scoped value
         * in the mapping will revert to being unbound, or revert to its previous value
         * when previously bound, in the current thread.
         *
         * <p> Scoped values are intended to be used in a <em>structured manner</em>.
         * If {@code op} creates a {@link StructuredTaskScope} but does not {@linkplain
         * StructuredTaskScope#close() close} it, then exiting {@code op} causes the
         * underlying construct of each {@code StructuredTaskScope} created in the
         * dynamic scope to be closed. This may require blocking until all child threads
         * have completed their sub-tasks. The closing is done in the reverse order that
         * they were created. Once closed, {@link StructureViolationException} is thrown.
         *
         * @param op the operation to run
         * @param <R> the type of the result of the operation
         * @return the result
         * @throws Exception if {@code op} completes with an exception
         * @see ScopedValue#callWhere(ScopedValue, Object, Callable) callWhere(ScopedValue, Object, Callable)
         */
        public <R> R call(Callable<? extends R> op) throws Exception {
            Objects.requireNonNull(op);
            Cache.invalidate(bitmask);
            var prevSnapshot = scopedValueBindings();
            var newSnapshot = new Snapshot(this, prevSnapshot);
            return runWith(newSnapshot, op);
        }

        /**
         * Invokes a supplier of results with each scoped value in this mapping bound
         * to its value in the current thread.
         * When the operation completes (normally or with an exception), each scoped value
         * in the mapping will revert to being unbound, or revert to its previous value
         * when previously bound, in the current thread.
         *
         * <p> Scoped values are intended to be used in a <em>structured manner</em>.
         * If {@code op} creates a {@link StructuredTaskScope} but does not {@linkplain
         * StructuredTaskScope#close() close} it, then exiting {@code op} causes the
         * underlying construct of each {@code StructuredTaskScope} created in the
         * dynamic scope to be closed. This may require blocking until all child threads
         * have completed their sub-tasks. The closing is done in the reverse order that
         * they were created. Once closed, {@link StructureViolationException} is thrown.
         *
         * @param op the operation to run
         * @param <R> the type of the result of the operation
         * @return the result
         * @see ScopedValue#getWhere(ScopedValue, Object, Supplier)
         */
        public <R> R get(Supplier<? extends R> op) {
            Objects.requireNonNull(op);
            Cache.invalidate(bitmask);
            var prevSnapshot = scopedValueBindings();
            var newSnapshot = new Snapshot(this, prevSnapshot);
            return runWith(newSnapshot, new CallableAdapter<R>(op));
        }

        // A lightweight adapter from Supplier to Callable. This is
        // used here to create the Callable which is passed to
        // Carrier#call() in this thread because it needs neither
        // runtime bytecode generation nor any release fencing.
        private static final class CallableAdapter<V> implements Callable<V> {
            private Supplier<? extends V> s;
            CallableAdapter(Supplier<? extends V> s) {
                this.s = s;
            }
            public V call() {
                return s.get();
            }
        }

        /**
         * Execute the action with a set of ScopedValue bindings.
         *
         * The VM recognizes this method as special, so any changes to the
         * name or signature require corresponding changes in
         * JVM_FindScopedValueBindings().
         */
        @Hidden
        @ForceInline
        private <R> R runWith(Snapshot newSnapshot, Callable<R> op) {
            try {
                Thread.setScopedValueBindings(newSnapshot);
                Thread.ensureMaterializedForStackWalk(newSnapshot);
                return ScopedValueContainer.call(op);
            } finally {
                Reference.reachabilityFence(newSnapshot);
                Thread.setScopedValueBindings(newSnapshot.prev);
                Cache.invalidate(bitmask);
            }
        }

        /**
         * Runs an operation with each scoped value in this mapping bound to its value
         * in the current thread.
         * When the operation completes (normally or with an exception), each scoped value
         * in the mapping will revert to being unbound, or revert to its previous value
         * when previously bound, in the current thread.
         *
         * <p> Scoped values are intended to be used in a <em>structured manner</em>.
         * If {@code op} creates a {@link StructuredTaskScope} but does not {@linkplain
         * StructuredTaskScope#close() close} it, then exiting {@code op} causes the
         * underlying construct of each {@code StructuredTaskScope} created in the
         * dynamic scope to be closed. This may require blocking until all child threads
         * have completed their sub-tasks. The closing is done in the reverse order that
         * they were created. Once closed, {@link StructureViolationException} is thrown.
         *
         * @param op the operation to run
         * @see ScopedValue#runWhere(ScopedValue, Object, Runnable)
         */
        public void run(Runnable op) {
            Objects.requireNonNull(op);
            Cache.invalidate(bitmask);
            var prevSnapshot = scopedValueBindings();
            var newSnapshot = new Snapshot(this, prevSnapshot);
            runWith(newSnapshot, op);
        }

        /**
         * Execute the action with a set of {@code ScopedValue} bindings.
         *
         * The VM recognizes this method as special, so any changes to the
         * name or signature require corresponding changes in
         * JVM_FindScopedValueBindings().
         */
        @Hidden
        @ForceInline
        private void runWith(Snapshot newSnapshot, Runnable op) {
            try {
                Thread.setScopedValueBindings(newSnapshot);
                Thread.ensureMaterializedForStackWalk(newSnapshot);
                ScopedValueContainer.run(op);
            } finally {
                Reference.reachabilityFence(newSnapshot);
                Thread.setScopedValueBindings(newSnapshot.prev);
                Cache.invalidate(bitmask);
            }
        }
    }

    /**
     * Creates a new {@code Carrier} with a single mapping of a {@code ScopedValue}
     * <em>key</em> to a value. The {@code Carrier} can be used to accumlate mappings so
     * that an operation can be executed with all scoped values in the mapping bound to
     * values. The following example runs an operation with {@code k1} bound (or rebound)
     * to {@code v1}, and {@code k2} bound (or rebound) to {@code v2}.
     * {@snippet lang=java :
     *     // @link substring="run" target="Carrier#run(Runnable)" :
     *     ScopedValue.where(k1, v1).where(k2, v2).run(() -> ... );
     * }
     *
     * @param key the {@code ScopedValue} key
     * @param value the value, can be {@code null}
     * @param <T> the type of the value
     * @return a new {@code Carrier} with a single mapping
     */
    public static <T> Carrier where(ScopedValue<T> key, T value) {
        return Carrier.of(key, value);
    }

    /**
     * Calls a value-returning operation with a {@code ScopedValue} bound to a value
     * in the current thread. When the operation completes (normally or with an
     * exception), the {@code ScopedValue} will revert to being unbound, or revert to
     * its previous value when previously bound, in the current thread.
     *
     * <p> Scoped values are intended to be used in a <em>structured manner</em>.
     * If {@code op} creates a {@link StructuredTaskScope} but does not {@linkplain
     * StructuredTaskScope#close() close} it, then exiting {@code op} causes the
     * underlying construct of each {@code StructuredTaskScope} created in the
     * dynamic scope to be closed. This may require blocking until all child threads
     * have completed their sub-tasks. The closing is done in the reverse order that
     * they were created. Once closed, {@link StructureViolationException} is thrown.
     *
     * @implNote
     * This method is implemented to be equivalent to:
     * {@snippet lang=java :
     *     // @link substring="call" target="Carrier#call(Callable)" :
     *     ScopedValue.where(key, value).call(op);
     * }
     *
     * @param key the {@code ScopedValue} key
     * @param value the value, can be {@code null}
     * @param <T> the type of the value
     * @param <R> the result type
     * @param op the operation to call
     * @return the result
     * @throws Exception if the operation completes with an exception
     */
    public static <T, R> R callWhere(ScopedValue<T> key,
                                 T value,
                                 Callable<? extends R> op) throws Exception {
        return where(key, value).call(op);
    }

    /**
     * Invokes a supplier of results with a {@code ScopedValue} bound to a value
     * in the current thread. When the operation completes (normally or with an
     * exception), the {@code ScopedValue} will revert to being unbound, or revert to
     * its previous value when previously bound, in the current thread.
     *
     * <p> Scoped values are intended to be used in a <em>structured manner</em>.
     * If {@code op} creates a {@link StructuredTaskScope} but does not {@linkplain
     * StructuredTaskScope#close() close} it, then exiting {@code op} causes the
     * underlying construct of each {@code StructuredTaskScope} created in the
     * dynamic scope to be closed. This may require blocking until all child threads
     * have completed their sub-tasks. The closing is done in the reverse order that
     * they were created. Once closed, {@link StructureViolationException} is thrown.
     *
     * @implNote
     * This method is implemented to be equivalent to:
     * {@snippet lang=java :
     *     // @link substring="call" target="Carrier#call(Callable)" :
     *     ScopedValue.where(key, value).get(op);
     * }
     *
     * @param key the {@code ScopedValue} key
     * @param value the value, can be {@code null}
     * @param <T> the type of the value
     * @param <R> the result type
     * @param op the operation to call
     * @return the result
     */
    public static <T, R> R getWhere(ScopedValue<T> key,
                                 T value,
                                 Supplier<? extends R> op) {
        return where(key, value).get(op);
    }

    /**
     * Run an operation with a {@code ScopedValue} bound to a value in the current
     * thread. When the operation completes (normally or with an exception), the
     * {@code ScopedValue} will revert to being unbound, or revert to its previous value
     * when previously bound, in the current thread.
     *
     * <p> Scoped values are intended to be used in a <em>structured manner</em>.
     * If {@code op} creates a {@link StructuredTaskScope} but does not {@linkplain
     * StructuredTaskScope#close() close} it, then exiting {@code op} causes the
     * underlying construct of each {@code StructuredTaskScope} created in the
     * dynamic scope to be closed. This may require blocking until all child threads
     * have completed their sub-tasks. The closing is done in the reverse order that
     * they were created. Once closed, {@link StructureViolationException} is thrown.
     *
     * @implNote
     * This method is implemented to be equivalent to:
     * {@snippet lang=java :
     *     // @link substring="run" target="Carrier#run(Runnable)" :
     *     ScopedValue.where(key, value).run(op);
     * }
     *
     * @param key the {@code ScopedValue} key
     * @param value the value, can be {@code null}
     * @param <T> the type of the value
     * @param op the operation to call
     */
    public static <T> void runWhere(ScopedValue<T> key, T value, Runnable op) {
        where(key, value).run(op);
    }

    private ScopedValue() {
        this.hash = generateKey();
    }

    /**
     * Creates a scoped value that is initially unbound for all threads.
     *
     * @param <T> the type of the value
     * @return a new {@code ScopedValue}
     */
    public static <T> ScopedValue<T> newInstance() {
        return new ScopedValue<T>();
    }

    /**
     * {@return the value of the scoped value if bound in the current thread}
     *
     * @throws NoSuchElementException if the scoped value is not bound
     */
    @ForceInline
    @SuppressWarnings("unchecked")
    public T get() {
        Object[] objects;
        if ((objects = scopedValueCache()) != null) {
            // This code should perhaps be in class Cache. We do it
            // here because the generated code is small and fast and
            // we really want it to be inlined in the caller.
            int n = (hash & Cache.SLOT_MASK) * 2;
            if (objects[n] == this) {
                return (T)objects[n + 1];
            }
            n = ((hash >>> Cache.INDEX_BITS) & Cache.SLOT_MASK) * 2;
            if (objects[n] == this) {
                return (T)objects[n + 1];
            }
        }
        return slowGet();
    }

    @SuppressWarnings("unchecked")
    private T slowGet() {
        var value = findBinding();
        if (value == Snapshot.NIL) {
            throw new NoSuchElementException();
        }
        Cache.put(this, value);
        return (T)value;
    }

    /**
     * {@return {@code true} if this scoped value is bound in the current thread}
     */
    public boolean isBound() {
        Object[] objects = scopedValueCache();
        if (objects != null) {
            int n = (hash & Cache.SLOT_MASK) * 2;
            if (objects[n] == this) {
                return true;
            }
            n = ((hash >>> Cache.INDEX_BITS) & Cache.SLOT_MASK) * 2;
            if (objects[n] == this) {
                return true;
            }
        }
        var value = findBinding();
        boolean result = (value != Snapshot.NIL);
        if (result)  Cache.put(this, value);
        return result;
    }

    /**
     * Return the value of the scoped value or NIL if not bound.
     */
    private Object findBinding() {
        Object value = scopedValueBindings().find(this);
        return value;
    }

    /**
     * Returns the value of this scoped value if bound in the current thread, otherwise
     * returns {@code other}.
     *
     * @param other the value to return if not bound, can be {@code null}
     * @return the value of the scoped value if bound, otherwise {@code other}
     */
    public T orElse(T other) {
        Object obj = findBinding();
        if (obj != Snapshot.NIL) {
            @SuppressWarnings("unchecked")
            T value = (T) obj;
            return value;
        } else {
            return other;
        }
    }

    /**
     * Returns the value of this scoped value if bound in the current thread, otherwise
     * throws an exception produced by the exception supplying function.
     *
     * @param <X> the type of the exception that may be thrown
     * @param exceptionSupplier the supplying function that produces the exception to throw
     * @return the value of the scoped value if bound in the current thread
     * @throws X if the scoped value is not bound in the current thread
     */
    public <X extends Throwable> T orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
        Objects.requireNonNull(exceptionSupplier);
        Object obj = findBinding();
        if (obj != Snapshot.NIL) {
            @SuppressWarnings("unchecked")
            T value = (T) obj;
            return value;
        } else {
            throw exceptionSupplier.get();
        }
    }

    private static Object[] scopedValueCache() {
        return Thread.scopedValueCache();
    }

    private static void setScopedValueCache(Object[] cache) {
        Thread.setScopedValueCache(cache);
    }

    // Special value to indicate this is a newly-created Thread
    // Note that his must match the declaration in j.l.Thread.
    private static final Object NEW_THREAD_BINDINGS = Thread.class;

    private static Snapshot scopedValueBindings() {
        // Bindings can be in one of four states:
        //
        // 1: class Thread: this is a new Thread instance, and no
        // scoped values have ever been bound in this Thread.
        // 2: EmptySnapshot.SINGLETON: This is effectively an empty binding.
        // 3: A Snapshot instance: this contains one or more scoped value
        // bindings.
        // 4: null: there may be some bindings in this Thread, but we don't know
        // where they are. We must invoke Thread.findScopedValueBindings() to walk
        // the stack to find them.

        Object bindings = Thread.scopedValueBindings();
        if (bindings == NEW_THREAD_BINDINGS) {
            // This must be a new thread
           return Snapshot.EMPTY_SNAPSHOT;
        }
        if (bindings == null) {
            // Search the stack
            bindings = Thread.findScopedValueBindings();
            if (bindings == null) {
                // Nothing on the stack.
                bindings = Snapshot.EMPTY_SNAPSHOT;
            }
        }
        assert (bindings != null);
        Thread.setScopedValueBindings(bindings);
        return (Snapshot) bindings;
    }

    private static int nextKey = 0xf0f0_f0f0;

    // A Marsaglia xor-shift generator used to generate hashes. This one has full period, so
    // it generates 2**32 - 1 hashes before it repeats. We're going to use the lowest n bits
    // and the next n bits as cache indexes, so we make sure that those indexes map
    // to different slots in the cache.
    private static synchronized int generateKey() {
        int x = nextKey;
        do {
            x ^= x >>> 12;
            x ^= x << 9;
            x ^= x >>> 23;
        } while (Cache.primarySlot(x) == Cache.secondarySlot(x));
        return (nextKey = x);
    }

    /**
     * Return a bit mask that may be used to determine if this ScopedValue is
     * bound in the current context. Each Carrier holds a bit mask which is
     * the OR of all the bit masks of the bound ScopedValues.
     * @return the bitmask
     */
    int bitmask() {
        return (1 << Cache.primaryIndex(this)) | (1 << (Cache.secondaryIndex(this) + Cache.TABLE_SIZE));
    }

    // Return true iff bitmask, considered as a set of bits, contains all
    // of the bits in targetBits.
    static boolean containsAll(int bitmask, int targetBits) {
        return (bitmask & targetBits) == targetBits;
    }

    // A small fixed-size key-value cache. When a scoped value's get() method
    // is invoked, we record the result of the lookup in this per-thread cache
    // for fast access in future.
    private static final class Cache {
        static final int INDEX_BITS = 4;  // Must be a power of 2
        static final int TABLE_SIZE = 1 << INDEX_BITS;
        static final int TABLE_MASK = TABLE_SIZE - 1;
        static final int PRIMARY_MASK = (1 << TABLE_SIZE) - 1;

        // The number of elements in the cache array, and a bit mask used to
        // select elements from it.
        private static final int CACHE_TABLE_SIZE, SLOT_MASK;
        // The largest cache we allow. Must be a power of 2 and greater than
        // or equal to 2.
        private static final int MAX_CACHE_SIZE = 16;

        static {
            final String propertyName = "java.lang.ScopedValue.cacheSize";
            var sizeString = GetPropertyAction.privilegedGetProperty(propertyName, "16");
            var cacheSize = Integer.valueOf(sizeString);
            if (cacheSize < 2 || cacheSize > MAX_CACHE_SIZE) {
                cacheSize = MAX_CACHE_SIZE;
                System.err.println(propertyName + " is out of range: is " + sizeString);
            }
            if ((cacheSize & (cacheSize - 1)) != 0) {  // a power of 2
                cacheSize = MAX_CACHE_SIZE;
                System.err.println(propertyName + " must be an integer power of 2: is " + sizeString);
            }
            CACHE_TABLE_SIZE = cacheSize;
            SLOT_MASK = cacheSize - 1;
        }

        static int primaryIndex(ScopedValue<?> key) {
            return key.hash & TABLE_MASK;
        }

        static int secondaryIndex(ScopedValue<?> key) {
            return (key.hash >> INDEX_BITS) & TABLE_MASK;
        }

        private static int primarySlot(ScopedValue<?> key) {
            return key.hashCode() & SLOT_MASK;
        }

        private static int secondarySlot(ScopedValue<?> key) {
            return (key.hash >> INDEX_BITS) & SLOT_MASK;
        }

        static int primarySlot(int hash) {
            return hash & SLOT_MASK;
        }

        static int secondarySlot(int hash) {
            return (hash >> INDEX_BITS) & SLOT_MASK;
        }

        static void put(ScopedValue<?> key, Object value) {
            Object[] theCache = scopedValueCache();
            if (theCache == null) {
                theCache = new Object[CACHE_TABLE_SIZE * 2];
                setScopedValueCache(theCache);
            }
            // Update the cache to replace one entry with the value we just looked up.
            // Each value can be in one of two possible places in the cache.
            // Pick a victim at (pseudo-)random.
            int k1 = primarySlot(key);
            int k2 = secondarySlot(key);
            var usePrimaryIndex = chooseVictim();
            int victim = usePrimaryIndex ? k1 : k2;
            int other = usePrimaryIndex ? k2 : k1;
            setKeyAndObjectAt(victim, key, value);
            if (getKey(theCache, other) == key) {
                setKeyAndObjectAt(other, key, value);
            }
        }

        private static void setKeyAndObjectAt(int n, Object key, Object value) {
            var cache = scopedValueCache();
            cache[n * 2] = key;
            cache[n * 2 + 1] = value;
        }

        private static void setKeyAndObjectAt(Object[] cache, int n, Object key, Object value) {
            cache[n * 2] = key;
            cache[n * 2 + 1] = value;
        }

        private static Object getKey(Object[] objs, int n) {
            return objs[n * 2];
        }

        private static void setKey(Object[] objs, int n, Object key) {
            objs[n * 2] = key;
        }

        private static final JavaUtilConcurrentTLRAccess THREAD_LOCAL_RANDOM_ACCESS
                = SharedSecrets.getJavaUtilConcurrentTLRAccess();

        // Return either true or false, at pseudo-random, with a bias towards true.
        // This chooses either the primary or secondary cache slot, but the
        // primary slot is approximately twice as likely to be chosen as the
        // secondary one.
        private static boolean chooseVictim() {
            int r = THREAD_LOCAL_RANDOM_ACCESS.nextSecondaryThreadLocalRandomSeed();
            return (r & 15) >= 5;
        }

        // Null a set of cache entries, indicated by the 1-bits given
        static void invalidate(int toClearBits) {
            toClearBits = (toClearBits >>> TABLE_SIZE) | (toClearBits & PRIMARY_MASK);
            Object[] objects;
            if ((objects = scopedValueCache()) != null) {
                for (int bits = toClearBits; bits != 0; ) {
                    int index = Integer.numberOfTrailingZeros(bits);
                    setKeyAndObjectAt(objects, index & SLOT_MASK, null, null);
                    bits &= ~1 << index;
                }
            }
        }
    }
}
