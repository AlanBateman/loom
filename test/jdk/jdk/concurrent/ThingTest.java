/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

/*
 * @test
 * @summary Basic tests for Thing
 * @run testng ThingTest
 */

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import jdk.concurrent.Thing;
import jdk.concurrent.TaskThing;
import static jdk.concurrent.TaskThing.Status.*;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class ThingTest {
    // long running interruptible task
    private static final Callable<Void> SLEEP_FOR_A_DAY = () -> {
        Thread.sleep(Duration.ofDays(1));
        return null;
    };

    private ScheduledExecutorService scheduler;

    @BeforeClass
    public void setUp() {
        ThreadFactory factory = (task) -> {
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            return thread;
        };
        scheduler = Executors.newSingleThreadScheduledExecutor(factory);
    }

    @DataProvider(name = "factories")
    public Object[][] factories() {
        return new Object[][] {
            { Executors.defaultThreadFactory(), },
            { Thread.ofVirtual().factory(), },
        };
    }

    @DataProvider(name = "things")
    public Object[][] things() {
        var defaultThreadFactory = Executors.defaultThreadFactory();
        var virtualThreadFactory = Thread.ofVirtual().factory();
        return new Object[][] {
                { Thing.<Object>of(defaultThreadFactory), },
                { Thing.<Object>of(virtualThreadFactory), },
        };
    }

    /**
     * Test that a thread is created for each task.
     */
    @Test(dataProvider = "factories")
    public void testThreadPerTask(ThreadFactory factory) {
        final int NUM_TASKS = 100;
        AtomicInteger threadCount = new AtomicInteger();

        ThreadFactory wrapper = task -> {
            threadCount.addAndGet(1);
            return factory.newThread(task);
        };

        var tasks = new ArrayList<TaskThing<Integer>>();
        try (Thing<Integer> thing = Thing.of(wrapper)) {
            for (int i=0; i<NUM_TASKS; i++) {
                int result = i;
                TaskThing<Integer> task = thing.fork(() -> result);
                tasks.add(task);
            }
        }

        assertEquals(threadCount.get(), NUM_TASKS);
        for (int i=0; i<NUM_TASKS; i++) {
            TaskThing<Integer> task = tasks.get(i);
            assertEquals((int) task.result(), i);
        }
    }

    /**
     * Test that Thing uses the specified thread factory.
     */
    @Test
    public void testThreadFactory()  {
        var ref1 = new AtomicReference<Thread>();
        var ref2 = new AtomicReference<Thread>();
        ThreadFactory factory = task -> {
            assertTrue(ref1.get() == null);
            Thread thread = new Thread(task);
            ref1.set(thread);
            return thread;
        };
        try (Thing<Void> thing = Thing.of(factory)) {
            thing.fork(() -> {
                ref2.set(Thread.currentThread());
                return null;
            });
        }
        Thread thread1 = ref1.get();   // Thread created by thread factory
        Thread thread2 = ref2.get();   // Thread that executed task
        assertTrue(thread1 == thread2);
    }

    /**
     * Test close with no threads running.
     */
    @Test(dataProvider = "things")
    public void testClose1(Thing<Object> thing) {
        thing.close();
        thing.close(); // already closed
    }

    /**
     * Test close with threads running.
     */
    @Test(dataProvider = "things")
    public void testClose2(Thing<Object> thing) {
        TaskThing<String> task;
        try (thing) {
            task = thing.fork(() -> {
                Thread.sleep(Duration.ofMillis(500));
                return "foo";
            });
        }
        assertEquals(task.result(), "foo");   // task should complete
    }

    /**
     * Invoke close with interrupt status set, should cancel task.
     */
    @Test(dataProvider = "things")
    public void testClose3(Thing<Object> thing) {
        TaskThing<Void> task;
        try (thing) {
            task = thing.fork(SLEEP_FOR_A_DAY);
            Thread.currentThread().interrupt();
        } finally {
            assertTrue(Thread.interrupted());  // clear interrupt
        }
        assertTrue(task.status() == CANCELLED);
    }

    /**
     * Interrupt thread waiting in close.
     */
    @Test(dataProvider = "things")
    public void testClose4(Thing<Object> thing) {
        TaskThing<Void> task;
        try (thing) {
            task = thing.fork(SLEEP_FOR_A_DAY);
            scheduleInterrupt(Thread.currentThread(), Duration.ofMillis(500));
        } finally {
            assertTrue(Thread.interrupted());
        }
        assertTrue(task.status() == CANCELLED);
    }

    @Test(dataProvider = "factories")
    public void testCloseOrder1(ThreadFactory factory) {
        Thing<Object> thing1 = Thing.of(factory);
        Thing<Object> thing2 = Thing.of(factory);
        Thing<Object> thing3 = Thing.of(factory);
        thing1.close();
        thing2.close();
        thing3.close();
    }

    @Test(dataProvider = "factories")
    public void testCloseOrder2(ThreadFactory factory) {
        Thing<Object> thing1 = Thing.of(factory);
        Thing<Object> thing2 = Thing.of(factory);
        Thing<Object> thing3 = Thing.of(factory);
        thing1.close();
        thing3.close();
        thing2.close();
    }

    @Test(dataProvider = "factories")
    public void testCloseOrder3(ThreadFactory factory) {
        Thing<Object> thing1 = Thing.of(factory);
        Thing<Object> thing2 = Thing.of(factory);
        Thing<Object> thing3 = Thing.of(factory);
        thing2.close();
        thing1.close();
        thing3.close();
    }

    @Test(dataProvider = "factories")
    public void testCloseOrder4(ThreadFactory factory) {
        Thing<Object> thing1 = Thing.of(factory);
        Thing<Object> thing2 = Thing.of(factory);
        Thing<Object> thing3 = Thing.of(factory);
        thing2.close();
        thing3.close();
        thing1.close();
    }

    @Test(dataProvider = "factories")
    public void testCloseOrder5(ThreadFactory factory) {
        Thing<Object> thing1 = Thing.of(factory);
        Thing<Object> thing2 = Thing.of(factory);
        Thing<Object> thing3 = Thing.of(factory);
        thing3.close();
        thing1.close();
        thing2.close();
    }

    @Test(dataProvider = "factories")
    public void testCloseOrder6(ThreadFactory factory) {
        Thing<Object> thing1 = Thing.of(factory);
        Thing<Object> thing2 = Thing.of(factory);
        Thing<Object> thing3 = Thing.of(factory);
        thing3.close();
        thing2.close();
        thing1.close();
    }

    /**
     * Test fork from a thread that is not the owner.
     */
    @Test(dataProvider = "factories")
    public void testForkByNonOwner(ThreadFactory factory) throws Exception {
        var exception = new AtomicReference<Exception>();
        try (Thing<Object> thing = Thing.of(factory)) {
            Thread.ofVirtual().start(() -> {
                try {
                    thing.fork(() -> null);
                } catch (Exception e) {
                    exception.set(e);
                }
            }).join();
        }
        assertTrue(exception.get() instanceof IllegalCallerException);
    }

    /**
     * Test close from a thread that is not the owner.
     */
    @Test(dataProvider = "factories")
    public void testCloseByNonOwner(ThreadFactory factory) throws Exception {
        var exception = new AtomicReference<Exception>();
        try (Thing<Object> thing = Thing.of(factory)) {
            Thread.ofVirtual().start(() -> {
                try {
                    thing.close();
                } catch (Exception e) {
                    exception.set(e);
                }
            }).join();
        }
        assertTrue(exception.get() instanceof IllegalCallerException);
    }

    /**
     * Test a closed Thing
     */
    @Test(dataProvider = "things")
    public void testClosed(Thing<Object> thing) {
        Iterator<?> iterator = thing.completions().iterator();

        thing.close();

        // IllegalStateException should be thrown by all methods
        var d = Instant.now().plusSeconds(2);
        expectThrows(IllegalStateException.class, () -> thing.deadline(d));
        expectThrows(IllegalStateException.class, () -> thing.fork(() -> "foo"));
        expectThrows(IllegalStateException.class, thing::awaitRemaining);
        expectThrows(IllegalStateException.class, thing::cancelRemaining);
        expectThrows(IllegalStateException.class, thing::completions);
        expectThrows(IllegalStateException.class, iterator::hasNext);
        expectThrows(IllegalStateException.class, iterator::next);
    }

    /**
     * Test that awaitRemaining waits for all tasks.
     */
    @Test(dataProvider = "factories")
    public void testAwaitRemaining1(ThreadFactory factory) {
        try (Thing<String> thing = Thing.of(factory)) {
            TaskThing<String> task1 = thing.fork(() -> {
                Thread.sleep(100);
                return "foo";
            });
            TaskThing<String> task2 = thing.fork(() -> {
                Thread.sleep(200);
                throw new RuntimeException();
            });
            assertTrue(thing.awaitRemaining() == thing);
            assertEquals(task1.result(), "foo");
            assertTrue(task2.exception() instanceof RuntimeException);
        }
    }

    /**
     * Test awaitRemaining with interrupt status set.
     */
    @Test(dataProvider = "factories")
    public void testAwaitRemaining2(ThreadFactory factory) {
        try (Thing<Void> thing = Thing.of(factory)) {
            TaskThing<Void> task = thing.fork(SLEEP_FOR_A_DAY);
            Thread.currentThread().interrupt();
            assertTrue(thing.awaitRemaining() == thing);
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(task.status() == CANCELLED);
        } finally {
            Thread.interrupted();  // clear interrupt status
        }
    }

    /**
     * Test interrupt thread waiting in awaitRemaining.
     */
    @Test(dataProvider = "factories")
    public void testAwaitRemaining3(ThreadFactory factory) {
        try (Thing<Void> thing = Thing.of(factory)) {
            TaskThing<Void> task = thing.fork(SLEEP_FOR_A_DAY);
            scheduleInterrupt(Thread.currentThread(), Duration.ofSeconds(1));
            assertTrue(thing.awaitRemaining() == thing);
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(task.status() == CANCELLED);
        } finally {
            Thread.interrupted();  // clear interrupt status
        }
    }

    /**
     * Test cancelRemaining cancels remaining tasks.
     */
    @Test(dataProvider = "factories")
    public void testCancelRemaining1(ThreadFactory factory) {
        try (Thing<Void> thing = Thing.of(factory)) {
            TaskThing<Void> task = thing.fork(SLEEP_FOR_A_DAY);
            assertTrue(thing.cancelRemaining() == thing);
            assertTrue(task.status() == CANCELLED);
        }
    }

    /**
     * Basic tests of completions.
     */
    @Test(dataProvider = "factories")
    public void testCompletions1(ThreadFactory factory) {
        try (Thing<String> thing = Thing.of(factory)) {
            TaskThing<String> task1 = thing.fork(() -> "foo");
            TaskThing<String> task2 = thing.fork(() -> "bar");

            Set<String> results = new HashSet<>();
            for (var task : thing.completions()) {
                assertTrue(task.status() == SUCCESS);
                results.add(task.result());
            }
            assertEquals(results, Set.of("foo", "bar"));
        }
    }

    /**
     * Test mix of tasks that complete normally, with exception, and cancel.
     */
    @Test(dataProvider = "factories")
    public void testCompletions2(ThreadFactory factory) {
        class BarException extends RuntimeException { }
        try (Thing<String> thing = Thing.of(factory)) {
            TaskThing<String> task1 = thing.fork(() -> "foo");
            TaskThing<String> task2 = thing.fork(() -> {
                throw new BarException();
            });
            TaskThing<String> task3 = thing.fork(() -> {
                Thread.sleep(Duration.ofDays(1));
                return null;
            });
            task3.asFuture().cancel(true);

            for (var task : thing.completions()) {
                if (task == task1) {
                    assertTrue(task1.status() == SUCCESS);
                    assertEquals(task1.result(), "foo");
                } else if (task == task2) {
                    assertTrue(task2.status() == FAILURE);
                    assertTrue(task2.exception() instanceof BarException);
                } else if (task == task3) {
                    assertTrue(task3.status() == CANCELLED);
                    assertTrue(task3.exception() instanceof CancellationException);
                } else {
                    assertTrue(false);
                }
            }
        }
    }

    /**
     * Test invoking fork during iteration.
     */
    @Test(dataProvider = "factories")
    public void testCompletions3(ThreadFactory factory) {
        try (Thing<String> thing = Thing.of(factory)) {
            TaskThing<String> task1 = thing.fork(() -> "foo");
            Set<String> results = new HashSet<>();
            for (var task : thing.completions()) {
                if (task == task1) {
                    thing.fork(() -> "bar");
                }
                results.add(task.result());
            }
            assertEquals(results, Set.of("foo", "bar"));
        }
    }

    /**
     * Test two iterators.
     */
    @Test(dataProvider = "factories")
    public void testCompletions4(ThreadFactory factory) {
        try (Thing<String> thing = Thing.of(factory)) {
            TaskThing<String> task1 = thing.fork(() -> "foo");
            var iterator1 = thing.completions().iterator();
            assertTrue(iterator1.next() == task1);
            assertFalse(iterator1.hasNext());

            TaskThing<String> task2 = thing.fork(() -> "bar");
            var iterator2 = thing.completions().iterator();
            assertTrue(iterator2.next() == task2);
            assertFalse(iterator2.hasNext());
        }
    }

    /**
     * Test invoking iterator methods with interrupt status set.
     */
    @Test(dataProvider = "factories")
    public void testCompletions5(ThreadFactory factory) {
        try (Thing<Void> thing = Thing.of(factory)) {
            TaskThing<Void> task = thing.fork(SLEEP_FOR_A_DAY);
            var iterator = thing.completions().iterator();
            Thread.currentThread().interrupt();
            assertTrue(iterator.next() == task);
            assertTrue(task.status() == CANCELLED);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();  // clear interrupt status
        }
    }

    /**
     * Test interrupting thread waiting in iterator
     */
    @Test(dataProvider = "factories")
    public void testCompletions6(ThreadFactory factory) {
        try (Thing<Void> thing = Thing.of(factory)) {
            TaskThing<Void> task = thing.fork(SLEEP_FOR_A_DAY);
            var iterator = thing.completions().iterator();
            scheduleInterrupt(Thread.currentThread(), Duration.ofMillis(500));
            assertTrue(iterator.next() == task);
            assertTrue(task.status() == CANCELLED);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();  // clear interrupt status
        }
    }

    @Test(dataProvider = "factories")
    public void testDisableCompletions1(ThreadFactory factory) {
        try (Thing<String> thing = Thing.of(factory)) {
            thing.disableCompletions();
            thing.fork(() -> "foo");
            assertFalse(thing.completions().iterator().hasNext());
        }
    }

    @Test(dataProvider = "factories", expectedExceptions = { IllegalStateException.class })
    public void testDisableCompletions2(ThreadFactory factory) {
        try (Thing<String> thing = Thing.of(factory)) {
            thing.fork(() -> "foo");
            thing.disableCompletions(); // should throw
        }
    }

    /**
     * Deadline expires before owner invokes close. The owner should be interrupted.
     */
    @Test(dataProvider = "factories")
    public void testDeadlineBeforeClose(ThreadFactory factory) {
        var deadline = Instant.now().plusSeconds(2);
        try (Thing<Object> thing = Thing.of(factory).deadline(deadline)) {
            Thread.sleep(Duration.ofDays(1));
            assertTrue(false);
        } catch (InterruptedException e) {
            assertFalse(Thread.currentThread().isInterrupted()); // should be cleared
            Throwable[] suppressed = e.getSuppressed();
            assertTrue(suppressed[0] instanceof DeadlineExpiredException);
        }
    }

    /**
     * Deadline expires while owner is blocked in close. The owner should not
     * be interrupted.
     */
    @Test(dataProvider = "factories")
    public void testDeadlineInClose(ThreadFactory factory) {
        var deadline = Instant.now().plusSeconds(2);
        var thing = Thing.<Void>of(factory).deadline(deadline);
        TaskThing<Void> task = thing.fork(SLEEP_FOR_A_DAY); // should be interrupted
        try {
            thing.close();
            assertTrue(false);
        } catch (DeadlineExpiredException e) {
            assertFalse(Thread.currentThread().isInterrupted());
            assertTrue(task.status() == CANCELLED);
        } finally {
            Thread.interrupted();  // clear interrupt status
        }
    }

    /**
     * Deadline expires after the thing is closed. The owner should not be interrupted.
     */
    @Test(dataProvider = "factories")
    public void testDeadlineAfterClose(ThreadFactory factory) throws Exception {
        var deadline = Instant.now().plusSeconds(3);
        try (var thing = Thing.<Void>of(factory).deadline(deadline)) {
        }
        Thread.sleep(Duration.ofSeconds(5)); // should not be interrupted
    }

    /**
     * Deadline expires with owner blocked in Future::get.
     */
    @Test(dataProvider = "factories")
    public void testDeadlineInFutureGet(ThreadFactory factory) throws Exception {
        var deadline = Instant.now().plusSeconds(2);
        try (var thing = Thing.<Void>of(factory).deadline(deadline)) {
            thing.fork(SLEEP_FOR_A_DAY).asFuture().get();
            assertTrue(false);
        } catch (CancellationException | InterruptedException e) {
            Throwable[] suppressed = e.getSuppressed();
            assertTrue(suppressed[0] instanceof DeadlineExpiredException);
        } finally {
            Thread.interrupted();  // clear interrupt status
        }
    }

    /**
     * Deadline expires with owner blocked in an iterator.
     */
    @Test(dataProvider = "factories")
    public void testDeadlineInIterator(ThreadFactory factory) throws Exception {
        var deadline = Instant.now().plusSeconds(1);
        try (var thing = Thing.<Void>of(factory).deadline(deadline)) {
            var task = thing.fork(SLEEP_FOR_A_DAY);
            Iterator<TaskThing<? extends Void>> iterator = thing.completions().iterator();
            assertTrue(iterator.next() == task);
            assertTrue(task.status() == CANCELLED);
            assertFalse(iterator.hasNext());

            // thing.close should throw DeadlineExpiredException
            expectThrows(DeadlineExpiredException.class, thing::close);

            // test that the owner is not interrupted after close
            if (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(500);  // should not be interrupted
            }
        } finally {
            Thread.interrupted();  // clear interrupt status
        }
    }

    /**
     * Deadline has already expired.
     */
    @Test(dataProvider = "factories")
    public void testDeadlineAlreadyExpired1(ThreadFactory factory) {
        Instant now = Instant.now();
        var thing = Thing.of(factory).deadline(now);
        assertTrue(Thread.interrupted());  // clears interrupt status
        expectThrows(DeadlineExpiredException.class, thing::close);
    }

    @Test(dataProvider = "factories")
    public void testDeadlineAlreadyExpired2(ThreadFactory factory) {
        var yesterday = Instant.now().minus(Duration.ofDays(1));
        var thing = Thing.of(factory).deadline(yesterday);
        assertTrue(Thread.interrupted());   // clears interrupt status
        expectThrows(DeadlineExpiredException.class, thing::close);
    }

    /**
     * Test nulls
     */
    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull1() {
        Thing.of(null);
    }

    @Test(dataProvider = "factories", expectedExceptions = { NullPointerException.class })
    public void testNull2(ThreadFactory factory) {
        try (Thing<Object> thing = Thing.of(factory)) {
            thing.deadline(null);
        }
    }

    @Test(dataProvider = "factories", expectedExceptions = { NullPointerException.class })
    public void testNull3(ThreadFactory factory) {
        try (Thing<Object> thing = Thing.of(factory)) {
            thing.fork((Callable<Object>) null);
        }
    }

    @Test(dataProvider = "factories", expectedExceptions = { NullPointerException.class })
    public void testNull4(ThreadFactory factory) {
        try (Thing<Object> thing = Thing.of(factory)) {
            thing.fork((Collection<Callable<Object>>) null);
        }
    }

    @Test(dataProvider = "factories", expectedExceptions = { NullPointerException.class })
    public void testNull5(ThreadFactory factory) {
        try (Thing<Object> thing = Thing.of(factory)) {
            thing.fork((Callable[]) null);
        }
    }

    /**
     * Schedules a thread to be interrupted after the given delay.
     */
    private void scheduleInterrupt(Thread thread, Duration delay) {
        long millis = delay.toMillis();
        scheduler.schedule(thread::interrupt, millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Schedules a resource to be closed after the given delay.
     */
    private void scheduleClose(AutoCloseable closeable, Duration delay) {
        long millis = delay.toMillis();
        Callable<Void> action = () -> {
            closeable.close();
            return null;
        };
        scheduler.schedule(action, millis, TimeUnit.MILLISECONDS);
    }
}