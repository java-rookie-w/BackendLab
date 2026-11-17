package org.wang.jvmlab.memory;

/**
 * 【考点 1】栈帧与 StackOverflowError
 *
 * 【运行】
 *   java org.wang.jvmlab.memory.StackOverflowDemo
 *   java -Xss256k org.wang.jvmlab.memory.StackOverflowDemo   （同一环境下对比更小栈）
 *
 * 【预期现象】
 *   打印本次递归深度，直到抛出 StackOverflowError。深度不是“默认 1 万～2 万”的通用常量，
 *   它受 JDK、操作系统、架构、解释/JIT 执行、内联和 -Xss 等影响。
 *
 * 【面试要点】
 *   1. 栈是线程私有的，方法调用需要栈帧；递归过深导致线程栈容量耗尽，可能抛出 SOE。
 *   2. 栈帧包含局部变量表、操作数栈、动态连接和返回地址等信息。
 *      class 文件的 max_locals/max_stack 给出编译期信息，但实际运行时帧布局还受
 *      解释器、C1/C2 和内联影响，不能据此承诺固定深度或固定每帧字节数。
 *   3. StackOverflowError 通常对应栈容量耗尽；OutOfMemoryError 可能出现在创建新线程、
 *      栈扩展或其他内存分配失败场景，不能只用“递归深浅”区分所有情况。
 *   4. 修复重点是递归出口、隐式递归和合理的栈容量评估，而不是背某个深度数字。
 */
public class StackOverflowDemo {

    private static int depth = 0;

    public static void main(String[] args) {
        depth = 0;
        try {
            recurse();
        } catch (StackOverflowError e) {
            System.out.println(">>> 抛出 StackOverflowError，本次递归调用深度约 = " + depth);
            System.out.println(">>> 该数字只描述当前方法、JDK 和参数，不能外推为通用栈帧数量或大小");
        }
    }

    /** 每层放几个局部变量；实际运行时如何使用它们由解释器/JIT 决定。 */
    static void recurse() {
        long a = depth;
        long b = depth + 1;
        long c = a + b;
        depth++;
        recurse();
        if (c == Long.MIN_VALUE) {
            System.out.println(a + b);
        }
    }
}
