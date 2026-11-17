package org.wang.jvmlab.jit;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

/**
 * 【P1-2】逃逸分析与标量替换：只保留可靠的“不逃逸”对照
 *
 * 【运行（两个独立进程对比）】
 *   java org.wang.jvmlab.jit.EscapeAnalysisDemo
 *   java -XX:-DoEscapeAnalysis org.wang.jvmlab.jit.EscapeAnalysisDemo
 *   更直观：java -Xmx64m -Xlog:gc* org.wang.jvmlab.jit.EscapeAnalysisDemo
 *
 * 【怎么读】
 *   比较两次运行的实际 GC 次数与耗时，不把单次教学运行当成性能基准。
 *   旧版的“局部数组强制逃逸”对照已删除：数组本身也可能被 JIT 优化，不能可靠证明对象逃逸。
 *
 * 【面试要点】
 *   1. 逃逸分析判断对象是否逃出方法/线程，可支持标量替换和锁消除等优化。
 *   2. HotSpot 常见效果是标量替换：对象被打散为局部变量，并不等于把完整对象分配到栈上。
 *   3. 是否实际优化取决于 JIT、内联和代码形态；源码里写了 new 不代表运行时一定分配对象。
 */
public class EscapeAnalysisDemo {

    static final int WARMUP = 5;
    static final int N = 50_000_000;

    public static void main(String[] args) {
        var arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        boolean disabled = arguments.contains("-XX:-DoEscapeAnalysis");
        System.out.println("本轮参数：" + arguments);
        System.out.println("逃逸分析开关观察：" + (disabled ? "命令行要求关闭" : "未显式关闭，使用 JVM 默认值"));
        System.out.println("请分别运行开启默认配置与 -XX:-DoEscapeAnalysis，再比较两次实测输出\n");

        for (int i = 0; i < WARMUP; i++) {
            noEscape(1_000_000);
        }

        System.gc();
        sleep(300);
        runPhase("不逃逸（方法内 new 完就丢）");

        System.out.println("\n只陈述本次结果：以上是当前 JDK、参数和机器上的观察，不打印预设的性能结论。");
        System.out.println("该实验不是 JMH 基准；单次耗时只用于帮助理解，不作为容量规划或调参依据。");
    }

    static void runPhase(String name) {
        long gcBefore = gcCount();
        long timeBefore = gcTime();
        long start = System.nanoTime();
        long sum = noEscape(N);
        long elapsed = System.nanoTime() - start;
        long gcAfter = gcCount();
        long timeAfter = gcTime();

        System.out.printf("%-30s 耗时=%5d ms, GC 次数+%-3d, GC 耗时+%d ms (sum=%d)%n",
                name, elapsed / 1_000_000, gcAfter - gcBefore, timeAfter - timeBefore, sum);
    }

    /** 对象只在方法内部使用；是否发生标量替换以 JIT 实际优化为准。 */
    static long noEscape(int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            Point p = new Point(i, i + 1);
            sum += p.x + p.y;
        }
        return sum;
    }

    static class Point {
        final int x, y;

        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
    }

    static long gcTime() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
