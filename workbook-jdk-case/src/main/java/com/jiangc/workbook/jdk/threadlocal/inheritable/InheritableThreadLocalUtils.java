package com.jiangc.workbook.jdk.threadlocal.inheritable;

public class InheritableThreadLocalUtils {
    private static final ThreadLocal<Integer> local = new InheritableThreadLocal<>();

    public static void set(Integer t) {
        local.set(t);
    }

    public static Integer get() {
        return local.get();
    }

    public static void remove() {
        local.remove();
    }
}
