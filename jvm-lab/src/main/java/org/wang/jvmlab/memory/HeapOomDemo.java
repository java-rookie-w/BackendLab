package org.wang.jvmlab.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * 【考点 16】OOM 类型之一：Java heap space
 *
 * 【运行】
 *   java -Xmx32m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=. \
 *        org.wang.jvmlab.memory.HeapOomDemo
 *
 * 【预期现象】
 *   分配若干次后抛 java.lang.OutOfMemoryError: Java heap space，
 *   并在当前目录生成 java_pidXXXX.hprof。
 *
 * 【面试要点】
 *   1. 静态集合持有对象引用 = 教科书式的内存泄漏（对象不再使用但仍然可达）。
 *   2. 泄漏和溢出的关系：泄漏累积 → 溢出。排查时先回答"是泄漏还是容量不够"。
 *   3. 生产必须配 -XX:+HeapDumpOnOutOfMemoryError，否则 OOM 后现场就没了，
 *      只能靠重启前 jmap 抢救。
 *   4. 拿到 hprof 后用 MAT / JProfiler 看支配树（Dominator Tree），
 *      找"谁持有着这些对象"，而不是只看"哪个对象最大"。
 */
public class HeapOomDemo {

    /** 静态字段可作为 GC Root；只要集合仍可达，其中元素也会保持可达。 */
    static final List<byte[]> LEAK = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("开始向静态集合追加 1MB 数组，堆上限由 -Xmx 控制...");
        int count = 0;
        try {
            while (true) {
                LEAK.add(new byte[1024 * 1024]);
                if (++count % 5 == 0) {
                    System.out.println("  已分配 " + count + " MB");
                }
            }
        } catch (OutOfMemoryError e) {
            // clear() 只解除强引用，不等于立即释放堆；先请求 GC 回收，再打印，降低再次 OOM 的概率。
            // System.gc() 只是请求，这里用于教学演示，不是生产环境的通用 OOM 处理方案。
            LEAK.clear();
            System.gc();
            System.out.println();
            System.out.println(">>> 触发 OOM：" + e.getClass().getName() + "：" + e.getMessage());
            System.out.println(">>> 共分配 " + count + " MB 后堆耗尽");
            System.out.println(">>> 关键：异常类型是 Java heap space，说明是堆不够 / 泄漏，不是元空间或直接内存");
            System.out.println(">>> 下一步：用 MAT 打开同目录的 .hprof，看支配树定位持有者");
        }
    }
}
