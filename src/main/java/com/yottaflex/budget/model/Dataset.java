package com.yottaflex.budget.model;

import java.util.ArrayList;
import java.util.List;

/** Input-side dataset: everything the engine is allowed to see. Ground truth lives in {@link GroundTruth}. */
public class Dataset {
    public final List<UserProfile> users = new ArrayList<>();
    public final List<Account> accounts = new ArrayList<>();
    public final List<Merchant> merchants = new ArrayList<>();
    public final List<MerchantCategory> merchantCategories = new ArrayList<>();
    public final List<Category> categories = new ArrayList<>();
    public final List<CategoryAlias> categoryAliases = new ArrayList<>();
    public final List<CategoryRule> categoryRules = new ArrayList<>();
    public final List<Transaction> transactions = new ArrayList<>();
    public final List<Budget> budgets = new ArrayList<>();
}
