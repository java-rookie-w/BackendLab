package org.wang.jvmlab.thread;

import java.util.concurrent.CountDownLatch;

/**
 * 【考点：线程状态】六种线程状态的确定性观察
 *
 * 【运行】
 *   java org.wang.jvmlab.thread.ThreadStateDemo
 *
 * 【设计说明】
 *   轮询到目标状态再打印；若超时，则明确打印实际状态，不把期望标签冒充观察结果。
 *
 * 【面试要点】
 *   1. Java 定义了 6 种状态：NEW、RUNNABLE、BLOCKED、WAITING、TIMED_WAITING、TERMINATED。
 *   2. RUNNABLE 在 JVM 里包含操作系统的“就绪”和“运行中”，不代表此刻一定占用 CPU。
 *   3. BLOCKED 对应争抢 synchronized 监视器锁；wait()/join()/park() 等对应 WAITING，
 *      带超时的 sleep()/wait(ms)/join(ms)/parkNanos() 对应 TIMED_WAITING。
 *   4. LockSupport.park() 和 ReentrantLock 的等待是 WAITING，不是 BLOCKED。
 */
public class ThreadStateDemo {

    static final Object LOCK = new Object();
    static volatile boolean spinning = true;

    public static void main(String[] args) throws Exception {
        printNew();
        printBlocked();
        printWaiting();
        printTimedWaiting();
        printRunnable();
        printTerminated();
    }

    static void printNew() {
        Thread t = new Thread(() -> {
        }, "demo-new");
        System.out.printf("%-16s %s%n", "NEW", t.getState());
    }

    /** BLOCKED：主线程先占住锁，另一个线程进不来。 */
    static void printBlocked() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            started.countDown();
            synchronized (LOCK) {
                System.out.println("  （blocked 线程拿到锁了）");
            }
        }, "demo-blocked");
        synchronized (LOCK) {
            t.start();
            started.await();
            printObservedState("期望 BLOCKED", t, Thread.State.BLOCKED);
        }
        t.join();
    }

    /** WAITING：wait() 后需要被唤醒。 */
    static void printWaiting() throws Exception {
        Thread t = new Thread(() -> {
            synchronized (LOCK) {
                try {
                    LOCK.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "demo-waiting");
        t.start();
        printObservedState("期望 WAITING", t, Thread.State.WAITING);
        synchronized (LOCK) {
            LOCK.notifyAll();
        }
        t.join();
    }

    /** TIMED_WAITING：sleep(ms)。 */
    static void printTimedWaiting() throws Exception {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "demo-timed-waiting");
        t.start();
        printObservedState("期望 TIMED_WAITING", t, Thread.State.TIMED_WAITING);
        t.join();
    }

    /** RUNNABLE：持续执行忙循环。 */
    static void printRunnable() throws Exception {
        Thread t = new Thread(() -> {
            long n = 0;
            while (spinning) {
                n++;
            }
            if (n < 0) {
                System.out.println(n);
            }
        }, "demo-runnable");
        t.start();
        printObservedState("期望 RUNNABLE", t, Thread.State.RUNNABLE);
        spinning = false;
        t.join();
    }

    /** TERMINATED：执行完 join 之后。 */
    static void printTerminated() throws Exception {
        Thread t = new Thread(() -> System.out.println("  （terminated 线程执行完毕）"), "demo-terminated");
        t.start();
        t.join();
        System.out.printf("%-16s %s%n", "TERMINATED", t.getState());
    }

    static void printObservedState(String label, Thread t, Thread.State expected) {
        Thread.State observed = awaitState(t, expected);
        System.out.printf("%-16s %s%n", label, observed);
    }

    /** 最多等待约 2 秒，返回实际观察值；超时不再继续冒充目标状态。 */
    static Thread.State awaitState(Thread t, Thread.State expected) {
        for (int i = 0; i < 200; i++) {
            Thread.State observed = t.getState();
            if (observed == expected) {
                return observed;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return t.getState();
            }
        }
        return t.getState();
    }
}
