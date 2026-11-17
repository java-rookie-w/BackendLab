package org.wang.jvmlab.gc;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;

/**
 * 【考点 8 / 9】用三组分配场景阅读 GC 日志
 *
 * 【运行（务必带 -Xlog，否则看不到 GC 原因）】
 *   java -Xmx64m -Xmn16m -Xlog:gc* org.wang.jvmlab.gc.GcLogDemo
 *   JDK 8 用：java -Xmx64m -Xmn16m -Xloggc:gc.log -XX:+PrintGCDetails -XX:+PrintGCDateStamps ...
 *
 * 【三组场景】
 *   阶段一：32KB 短命对象，观察普通 Young GC（G1 Evacuation Pause）。
 *   阶段二：有界保留最近约 24MB 小对象，让对象跨多次 GC 存活，观察老年代与回收变化。
 *   阶段三：512KB 数组，在当前 -Xmx64m、G1 Region=1MB 的典型配置下演示 Humongous Allocation。
 *
 * 【边界】
 *   GC 次数与具体日志取决于 JDK、收集器和参数；程序打印计数只用于辅助，归因必须看真实日志。
 *   阶段二不承诺出现 Old/Full GC，也不单独证明某一种晋升路径；清理阶段的显式 GC 单独标注。
 *   Humongous 阈值随 Region 大小变化，不能把 512KB 当成所有堆配置下的固定阈值。
 *
 * 【面试要点】
 *   1. 看 GC 日志重点看停顿、频率、回收前后容量和吞吐量。
 *   2. 老年代回收后的底部持续抬高才值得怀疑泄漏，不能仅凭 GC 次数判断。
 *   3. GC 频繁不等于要调参：先区分分配速率高、对象存活过久和大对象分配。
 */
public class GcLogDemo {

    static final int SMALL_OBJECT_BYTES = 32 * 1024;
    static final int HUMONGOUS_CANDIDATE_BYTES = 512 * 1024;
    static final int SURVIVOR_SLOTS = 768; // 最多保留约 24MB，给 64MB 堆留出余量
    static final int PHASE_TWO_ALLOCATIONS = 6_000;

    public static void main(String[] args) throws Exception {
        System.out.println("JVM 参数：" + ManagementFactory.getRuntimeMXBean().getInputArguments());
        shortLivedObjects();
        longLivedObjects();
        humongousObjects();
        printGcStatistics();
    }

    /** 普通小对象：每次分配后立即失去引用，避免落入 Humongous 路径。 */
    static void shortLivedObjects() {
        System.out.println("\n========== 阶段一：32KB 短命对象 ==========");
        long youngBefore = youngGcCount();
        long checksum = 0;
        for (int i = 0; i < 20_000; i++) {
            byte[] garbage = new byte[SMALL_OBJECT_BYTES];
            garbage[0] = (byte) i;
            checksum += garbage[0];
        }
        System.out.println("  本阶段 Young GC 次数增量 = " + (youngGcCount() - youngBefore));
        System.out.println("  checksum=" + checksum + "（防止计算被整体消除）");
    }

    /** 有界保留普通小对象：观察跨 GC 存活，不靠制造 OOM 来证明晋升。 */
    static void longLivedObjects() throws InterruptedException {
        System.out.println("\n========== 阶段二：有界保留最近约 24MB 小对象 ==========");
        long youngBefore = youngGcCount();
        long fullBefore = fullGcCount();
        ArrayDeque<byte[]> survivors = new ArrayDeque<>();
        long checksum = 0;

        for (int i = 0; i < PHASE_TWO_ALLOCATIONS; i++) {
            byte[] survivor = new byte[SMALL_OBJECT_BYTES];
            survivor[0] = (byte) i;
            survivors.addLast(survivor);
            checksum += survivor[0];
            if (survivors.size() > SURVIVOR_SLOTS) {
                survivors.removeFirst();
            }
            if ((i & 127) == 0) {
                Thread.sleep(5);
            }
        }

        System.out.println("  当前持有约 " + survivors.size() * SMALL_OBJECT_BYTES / 1024 / 1024 + " MB");
        System.out.println("  本阶段 Young GC 次数增量 = " + (youngGcCount() - youngBefore));
        System.out.println("  本阶段 Old/Full GC 次数增量 = " + (fullGcCount() - fullBefore));
        System.out.println("  是否在 Old/Full GC 中回收到多少空间，以真实日志为准");
        System.out.println("  checksum=" + checksum);

        survivors.clear();
        System.out.println("  已解除引用；下面单独请求一次 System.gc() 清理，这次 GC 不计入上面的阶段增量");
        System.gc();
        Thread.sleep(200);
    }

    /** 单独演示大对象分配，避免与普通 Eden/存活对象实验混为一谈。 */
    static void humongousObjects() {
        System.out.println("\n========== 阶段三：512KB Humongous 候选对象 ==========");
        long youngBefore = youngGcCount();
        long checksum = 0;
        for (int i = 0; i < 200; i++) {
            byte[] large = new byte[HUMONGOUS_CANDIDATE_BYTES];
            large[0] = (byte) i;
            checksum += large[0];
        }
        System.out.println("  本阶段 Young GC 次数增量 = " + (youngGcCount() - youngBefore));
        System.out.println("  请在日志中查找 G1 Humongous Allocation；未出现时应先检查 Region 大小与参数");
        System.out.println("  checksum=" + checksum);
    }

    static void printGcStatistics() {
        System.out.println("\n========== 收集器统计 ==========");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            System.out.printf("  %-24s 次数=%-4d 累计耗时=%d ms%n",
                    gc.getName(), gc.getCollectionCount(), gc.getCollectionTime());
        }
        System.out.println("\n提示：按三个阶段分别对照日志，重点看 GC 原因、回收前后容量与停顿时间。");
    }

    static long youngGcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .filter(b -> b.getName().toLowerCase().contains("young"))
                .mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
    }

    static long fullGcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .filter(b -> b.getName().toLowerCase().contains("old")
                        || b.getName().toLowerCase().contains("full"))
                .mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
    }
}
