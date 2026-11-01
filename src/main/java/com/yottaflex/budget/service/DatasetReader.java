package com.yottaflex.budget.service;

import com.yottaflex.budget.model.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

/** Loads the engine's input {@link Dataset} from PostgreSQL. */
@Component
public class DatasetReader {

    private final JdbcTemplate jdbc;

    public DatasetReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Dataset read() {
        Dataset d = new Dataset();
        jdbc.query("SELECT * FROM user_profile ORDER BY user_id", rs -> {
            d.users.add(new UserProfile(rs.getString("user_id"), rs.getString("display_name"),
                    rs.getInt("household_size"), rs.getString("timezone"), rs.getString("income_band"),
                    cents(rs, "annual_income"), rs.getString("city"), date(rs, "created_on"), rs.getString("status")));
        });
        jdbc.query("SELECT * FROM account ORDER BY account_id", rs -> {
            d.accounts.add(new Account(rs.getString("account_id"), rs.getString("user_id"), rs.getString("account_type"),
                    date(rs, "opened_on"), date(rs, "closed_on"), rs.getString("status"), rs.getString("currency"),
                    cents(rs, "opening_balance"), cents(rs, "closing_balance")));
        });
        jdbc.query("SELECT * FROM merchant ORDER BY merchant_id", rs -> {
            d.merchants.add(new Merchant(rs.getString("merchant_id"), rs.getString("canonical_name"),
                    rs.getString("merchant_type"), rs.getString("default_category_code"), rs.getString("mcc"),
                    rs.getString("status")));
        });
        jdbc.query("SELECT * FROM category ORDER BY category_code", rs -> {
            d.categories.add(new Category(rs.getString("category_code"), rs.getString("name"),
                    rs.getString("parent_code"), rs.getString("kind"), rs.getBoolean("essential"),
                    date(rs, "effective_from"), rs.getString("status")));
        });
        jdbc.query("SELECT * FROM category_alias ORDER BY alias_code", rs -> {
            d.categoryAliases.add(new CategoryAlias(rs.getString("alias_code"), rs.getString("category_code")));
        });
        jdbc.query("SELECT * FROM category_rule ORDER BY rule_id", rs -> {
            d.categoryRules.add(new CategoryRule(rs.getString("rule_id"), rs.getInt("priority"),
                    rs.getString("match_type"), rs.getString("merchant_id"), rs.getString("pattern"),
                    rs.getString("mcc"), rs.getString("txn_type"), nullableCents(rs, "min_abs_amount"),
                    nullableCents(rs, "max_abs_amount"), rs.getString("category_code"), date(rs, "effective_from"),
                    date(rs, "effective_to"), rs.getString("status")));
        });
        jdbc.setFetchSize(10_000);
        jdbc.query("SELECT * FROM ledger_transaction ORDER BY transaction_id", rs -> {
            d.transactions.add(new Transaction(rs.getString("transaction_id"), rs.getString("account_id"),
                    rs.getString("merchant_id"), rs.getTimestamp("posted_at_utc").toInstant(), cents(rs, "amount"),
                    rs.getString("currency"), rs.getString("descriptor"), rs.getString("txn_type"),
                    rs.getString("mcc"), cents(rs, "balance_after")));
        });
        jdbc.query("SELECT * FROM budget ORDER BY budget_id", rs -> {
            d.budgets.add(new Budget(rs.getString("budget_id"), rs.getString("user_id"), rs.getString("budget_month"),
                    rs.getString("category_code"), cents(rs, "budget_amount"), rs.getString("currency"),
                    date(rs, "effective_from"), rs.getString("status")));
        });
        return d;
    }

    private static long cents(ResultSet rs, String col) throws SQLException {
        return rs.getBigDecimal(col).movePointRight(2).longValueExact();
    }

    private static Long nullableCents(ResultSet rs, String col) throws SQLException {
        BigDecimal v = rs.getBigDecimal(col);
        return v == null ? null : v.movePointRight(2).longValueExact();
    }

    private static LocalDate date(ResultSet rs, String col) throws SQLException {
        Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }
}
