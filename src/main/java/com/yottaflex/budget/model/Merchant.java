package com.yottaflex.budget.model;

public record Merchant(String merchantId, String canonicalName, String merchantType,
                       String defaultCategoryCode, String mcc, String status) {
}
