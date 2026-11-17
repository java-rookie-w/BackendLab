package org.wang.jvmlab.memory;

import com.sun.management.HotSpotDiagnosticMXBean;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;

/**
 * 【考点 4】对象内存布局：读取真实 VM 配置，识别估算边界
 *
 * 【运行】
 *   java org.wang.jvmlab.memory.ObjectLayoutDemo
 *   java -XX:+UseSerialGC -XX:-UseCompressedOops org.wang.jvmlab.memory.ObjectLayoutDemo
 *
 * 【为什么不再用堆占用差估算对象大小】
 *   旧版在本机稳定出现 Empty=20B、LongAndInt=28B 等不符合对象对齐的结果。
 *   批量分配的堆差会混入其他堆占用与 GC/测量因素，不能据此反推精确对象布局。
 *   本版本删除伪精确数字，只读取真实 VM Option 和字段声明；精确偏移与浅堆大小应使用 JOL。
 *
 * 【面试要点】
 *   1. 常见布局 = 对象头 + 实例数据 + 对齐填充；数组还包含长度信息。
 *   2. Mark Word、类型指针和字段布局是实现细节，受 JDK、位数和 VM 参数影响；
 *      JDK 25 还可能使用紧凑对象头，不能只背“12 字节对象头”。
 *   3. 压缩指针失效与堆大小、对象对齐和 JVM 配置有关，约 32GB 只是常见经验边界，
 *      应以本进程真实 VM Option 为准。
 *   4. 字段声明不等于内存偏移；JVM 可能重排字段，对齐也会使相同字段组合呈现不同大小。
 */
public class ObjectLayoutDemo {

    public static void main(String[] args) {
        printVmLayoutOptions();
        printFields(Empty.class);
        printFields(OneInt.class);
        printFields(LongAndInt.class);
        printFields(WithRef.class);

        System.out.println("\n========== 怎么读 ==========");
        System.out.println("  1) UseCompressedOops / UseCompactObjectHeaders 以本次 VM Option 为准");
        System.out.println("  2) ObjectAlignmentInBytes 影响对齐，但不能仅凭它手算所有 JVM 的对象大小");
        System.out.println("  3) 字段列表只说明数据需求，不声明实际偏移、对象头大小或最终浅堆大小");
        System.out.println("  4) 需要精确布局时使用 JOL；本仓库保持纯 JDK，不用错误估算代替测量");
    }

    static void printVmLayoutOptions() {
        HotSpotDiagnosticMXBean vm = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        if (vm == null) {
            System.out.println("当前 JVM 不支持 HotSpotDiagnosticMXBean，无法读取布局相关 VM Option");
            return;
        }
        printVmOption(vm, "UseCompressedOops");
        printVmOption(vm, "UseCompactObjectHeaders");
        printVmOption(vm, "ObjectAlignmentInBytes");
        System.out.println();
    }

    static void printVmOption(HotSpotDiagnosticMXBean vm, String name) {
        try {
            System.out.println(name + " = " + vm.getVMOption(name).getValue());
        } catch (IllegalArgumentException e) {
            System.out.println(name + " = 当前 JVM 不提供该选项");
        }
    }

    static void printFields(Class<?> type) {
        System.out.print(type.getSimpleName() + " 声明字段：");
        Field[] fields = type.getDeclaredFields();
        if (fields.length == 0) {
            System.out.println("无");
            return;
        }
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                System.out.print(", ");
            }
            System.out.print(fields[i].getType().getSimpleName() + " " + fields[i].getName());
        }
        System.out.println();
    }

    static class Empty {
    }

    static class OneInt {
        int a;
    }

    static class LongAndInt {
        long l;
        int i;
    }

    static class WithRef {
        Object o;
    }
}
