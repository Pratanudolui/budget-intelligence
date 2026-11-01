package com.yottaflex.budget.util;

import java.math.BigDecimal;

public final class Money {

    private Money() {
    }

    public static String format(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }

    public static long parse(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return new BigDecimal(value.trim()).movePointRight(2).longValueExact();
    }

    public static BigDecimal toDecimal(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }
}
