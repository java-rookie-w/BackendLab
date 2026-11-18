package org.wang.jvmlab.memory;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 【考点 P1-5 / 16】直接内存"可观察化"：把"堆外涨、堆不涨"做成肉眼可见的数字
 *
 * 【为什么需要这个实验】
 *   DirectMemoryOomDemo 只在最后抛一行异常、进程秒退 —— 在任务管理器里抓不到任何变化，
 *   也没法观察"堆很闲但堆外一直涨"这个过程。这个实验把过程放慢并逐轮打印。
 *
 * 【运行】
 *   java -XX:MaxDirectMemorySize=256m org.wang.jvmlab.memory.DirectMemoryWatchDemo
 *   末尾保持时长可调（默认 10 秒）：java ... DirectMemoryWatchDemo 60
 *   另开终端对照：jcmd <pid> VM.native_memory   （需先加 -XX:NativeMemoryTracking=detail）
 *   抢不到 pid 就用 `jps -l` 列出来。
 *
 * 【如果进程秒退根本抢不到 jcmd】（比如 DirectMemoryOomDemo）：
 *   别抢 —— 让 JVM 自己打印。加
 *   `-XX:+UnlockDiagnosticVMOptions -XX:NativeMemoryTracking=detail -XX:+PrintNMTStatistics`，
 *   JVM 会在**退出时**自动 dump 完整 NMT 报告。实测 M4 带 -XX:MaxDirectMemorySize=1g 的报告里：
 *   `Other (reserved=1073741824, committed=1073741824)` ← 那 1GB 就在 Other 分类，
 *   而同一时刻 `Java Heap committed` 只有 28MB。**直接内存算在 NMT 的 Other 分类**。
 *
 * 【预期现象】
 *   ① 逐轮分配堆外：堆外 direct 每轮 +32MB，而"堆已用"几乎不动 → 两套独立额度，肉眼可见
 *   ② 丢掉引用 + System.gc() 后：观察堆外是否回落，理解不可达对象可经 GC/Cleaner 链路释放；
 *      System.gc() 只是请求，本次回落不是对所有环境的时序保证
 *
 * 【面试要点】
 *   1. 观察直接内存**不要**用任务管理器看 Java 进程总内存（噪声大、分不出哪块涨）。
 *      要用 BufferPoolMXBean("direct") 或 `jcmd <pid> VM.native_memory`，精确到字节。
 *   2. 程序会打印自己的 pid —— 去任务管理器「详细信息」里按 PID 找，
 *      进程名是 **java.exe，不是 idea64.exe**（IDEA 只是启动者，不是宿主）。
 *   3. 生产上"RSS 一直涨、堆很平稳"就是这幅图的慢放版。
 *   4. 第 ② 段正是 DirectMemoryOomDemo 演示不了的那条机制：它用 List 强引用持有全部 buffer，
 *      就算 GC 跑了也回收不掉，所以看不出 Cleaner 的触发时机。
 */
public class DirectMemoryWatchDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("本进程 pid = " + ProcessHandle.current().pid()
                + "（任务管理器「详细信息」按 PID 找，进程名 java.exe）");
        System.out.println();

        List<ByteBuffer> buffers = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            buffers.add(ByteBuffer.allocateDirect(32 * 1024 * 1024));
            Thread.sleep(800);
            System.out.println("  分配第 " + i + " 块 32MB 后 → 堆已用 " + heapUsedMb()
                    + " MB，堆外 direct " + directUsedMb() + " MB");
        }

        System.out.println();
        System.out.println(">>> 看这两列：堆几乎没动，堆外一路涨 —— 这就是「两套独立额度」");
        System.out.println(">>> 下面解除引用并请求 GC；不可达 DirectByteBuffer 被发现后，Cleaner 才有机会释放堆外内存");
        System.out.println();

        buffers.clear();
        buffers = null;
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(700);
        }
        System.out.println("  丢引用 + System.gc() 后 → 堆已用 " + heapUsedMb()
                + " MB，堆外 direct " + directUsedMb() + " MB");
        System.out.println(">>> 本次数字只代表当前 JDK 与参数；System.gc() 是请求，不保证释放时机");
        System.out.println(">>> 对比实验：把上面那三段 System.gc() 注释掉重跑，观察 direct 是否仍回落");
        System.out.println("              —— 若不回落，说明本次未观察到 GC/Cleaner 释放，而不是引用删除即释放");
        System.out.println();
        long holdSeconds = args.length > 0 ? Long.parseLong(args[0]) : 10;
        System.out.println("（进程保持 " + holdSeconds + " 秒，方便你去任务管理器 / jcmd 对照观察）");
        System.out.println("  嫌短就传参数：java ... DirectMemoryWatchDemo 60  → 保持 60 秒");
        System.out.println("  另一个终端用 jps -l 也能直接列出本进程（不用抢着看上面打印的 pid）");
        Thread.sleep(holdSeconds * 1000);
    }

    static long heapUsedMb() {
        return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024;
    }

    static long directUsedMb() {
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if ("direct".equals(pool.getName())) {
                return pool.getMemoryUsed() / 1024 / 1024;
            }
        }
        return -1;
    }
}
