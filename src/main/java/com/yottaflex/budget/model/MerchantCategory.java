package com.yottaflex.budget.model;

/** Bridge row for the Merchant N:M Category relationship. */
public record MerchantCategory(String merchantId, String categoryCode, boolean primary) {
}
