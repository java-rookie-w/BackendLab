package org.wang.jvmlab.memory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 【考点 16 / P1-5】OOM 类型之一：Direct buffer memory（堆外内存）
 *
 * 【运行】
 *   java -XX:MaxDirectMemorySize=16m org.wang.jvmlab.memory.DirectMemoryOomDemo
 *
 * 【预期现象】
 *   分配若干次后抛 java.lang.OutOfMemoryError: Direct buffer memory。
 *   注意：JDK 内部在分配失败时会先触发一次 System.gc() 再重试，所以过程会比较慢。
 *
 * 【面试要点】
 *   1. 直接内存不受 -Xmx 限制，由 -XX:MaxDirectMemorySize 控制（默认约等于 Xmx）。
 *   2. 为什么用堆外：IO 时少一次堆内存到 native 内存的拷贝（Netty、NIO、零拷贝场景）。
 *   3. 它什么时候被回收？本实验的非池化 DirectByteBuffer 本身是堆上小对象，
 *      不可达后可由 GC 发现，并通过 Cleaner 释放关联堆外内存；System.gc() 不保证具体时机。
 *   4. 经典坑：堆外增长但堆压力小 → GC/Cleaner 可能迟迟不运行 → 堆外释放被延迟。
 *      Netty 池化 ByteBuf 需要按引用计数显式 release，不能套用本实验的 Cleaner 回收模型。
 *   5. 排查手段：-XX:NativeMemoryTracking=detail + jcmd <pid> VM.native_memory。
 *
 * 【实际场景：线上什么时候真会遇到】（2026-09-15 补）
 *   1. **Netty 的 ByteBuf 忘了 release**（最常见）：Handler 里没在 finally 释放，
 *      或异常分支漏了 `ReferenceCountUtil.release(msg)`。
 *      典型现象是**堆内存很平稳、但进程 RSS 一路涨**，最后抛 Direct buffer memory。
 *      定位手段：`-Dio.netty.leakDetection.level=advanced`（能直接报出泄漏点在哪行）。
 *   2. **长连接服务 + 每连接一块读写缓冲**：NIO 的 DirectByteBuffer 按连接/按读写分配，
 *      连接数暴涨、或消费端变慢导致背压缺失时，堆外会比堆先被吃光。
 *   3. **堆压力小可能延迟 GC/Cleaner**：DirectByteBuffer 只是堆上一个小对象，
 *      堆很闲时 GC 可能长期不运行，Cleaner 也随之延迟 —— 堆很闲并不能保证堆外及时释放。
 *   4. **`-XX:+DisableExplicitGC` 的副作用**：JDK 在堆外分配失败时会**先自动 System.gc() 再重试**
 *      （所以本实验逼近上限时会明显卡一下，这不是你的机器慢）。这个参数会把那条自救路径堵死，
 *      让它更容易溢出 —— 这正是"生产别乱加 -XX:+DisableExplicitGC"的原因之一。
 *   5. **容器里往往连这个异常都看不到**：堆外 + 堆 + 元空间 + CodeCache + 线程栈
 *      加起来超过 cgroup limit 时，进程会被 OOM Killer 直接 kill，日志里干干净净。
 *
 * 【排查链路】
 *   RSS 远大于 -Xmx、且堆还很空 → 基本就是堆外。
 *   ① `jcmd <pid> VM.native_memory`（需先开 -XX:NativeMemoryTracking=detail）
 *   ② MAT 里搜 `java.nio.DirectByteBuffer`，看**谁持有**它
 *   ③ Netty 项目开 leakDetection 定位泄漏点
 *   ④ 修复方向：显式释放 / 池化复用，而不是把 MaxDirectMemorySize 一味调大
 */
public class DirectMemoryOomDemo {

    public static void main(String[] args) {
        System.out.println("开始分配堆外内存（每次 1MB），上限由 -XX:MaxDirectMemorySize 控制...");
        List<ByteBuffer> buffers = new ArrayList<>();
        int count = 0;
        try {
            while (true) {
                buffers.add(ByteBuffer.allocateDirect(1024 * 1024));
                if (++count % 4 == 0) {
                    System.out.println("  已分配 " + count + " MB 堆外内存");
                }
            }
        } catch (OutOfMemoryError e) {
            // 先解除大部分强引用；堆外内存何时真正释放仍取决于 GC/Cleaner，
            // 不能把 remove() 本身当作 freeMemory()。
            while (buffers.size() > 1) {
                buffers.remove(buffers.size() - 1);
            }
            System.out.println();
            System.out.println(">>> 触发 OOM：" + e.getClass().getName() + "：" + e.getMessage());
            System.out.println(">>> 分配了 " + count + " MB 后堆外内存耗尽");
            System.out.println(">>> 关键：堆还很空的时候堆外也能溢出，因为两者是两套独立的额度");
            System.out.println(">>> 对比实验：去掉 -XX:MaxDirectMemorySize，默认额度约等于 -Xmx");
        }
    }
}
