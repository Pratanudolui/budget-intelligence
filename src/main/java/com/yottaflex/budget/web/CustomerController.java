package com.yottaflex.budget.web;

import com.yottaflex.budget.persistence.entity.AccountEntity;
import com.yottaflex.budget.persistence.entity.UserProfileEntity;
import com.yottaflex.budget.persistence.repository.AccountRepository;
import com.yottaflex.budget.persistence.repository.UserProfileRepository;
import com.yottaflex.budget.service.NotFoundException;
import com.yottaflex.budget.service.ReportService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Customer / account holder views: profile, categorized ledger, budget vs actual, recurring payments, insights. */
@RestController
@RequestMapping("/api/users/{userId}")
@Validated
public class CustomerController {

    private static final String MONTH = "^\\d{4}-\\d{2}$";

    private final UserProfileRepository users;
    private final AccountRepository accounts;
    private final ReportService reports;

    public CustomerController(UserProfileRepository users, AccountRepository accounts, ReportService reports) {
        this.users = users;
        this.accounts = accounts;
        this.reports = reports;
    }

    @GetMapping
    public Map<String, Object> profile(@PathVariable String userId) {
        UserProfileEntity u = users.findById(userId).orElseThrow(() -> new NotFoundException("User " + userId + " not found"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user_id", u.getUserId());
        out.put("display_name", u.getDisplayName());
        out.put("household_size", u.getHouseholdSize());
        out.put("timezone", u.getTimezone());
        out.put("income_band", u.getIncomeBand());
        out.put("city", u.getCity());
        out.put("created_on", u.getCreatedOn());
        out.put("status", u.getStatus());
        List<Map<String, Object>> accts = accounts.findByUser_UserIdOrderByAccountId(userId).stream().map(this::account).toList();
        out.put("accounts", accts);
        return out;
    }

    @GetMapping("/transactions")
    public List<Map<String, Object>> transactions(@PathVariable String userId,
                                                  @RequestParam(required = false) @Pattern(regexp = MONTH) String month,
                                                  @RequestParam(required = false) String runId,
                                                  @RequestParam(defaultValue = "0") @Min(0) int page,
                                                  @RequestParam(defaultValue = "50") @Min(1) @Max(500) int size) {
        return reports.transactions(userId, month, runId, page, size);
    }

    @GetMapping("/budget-report")
    public Map<String, Object> budgetReport(@PathVariable String userId,
                                            @RequestParam @Pattern(regexp = MONTH) String month,
                                            @RequestParam(required = false) String runId) {
        return reports.budgetReport(userId, month, runId);
    }

    @GetMapping("/recurring")
    public List<Map<String, Object>> recurring(@PathVariable String userId, @RequestParam(required = false) String runId) {
        return reports.recurring(userId, runId);
    }

    @GetMapping("/insights")
    public List<Map<String, Object>> insights(@PathVariable String userId,
                                              @RequestParam(required = false) @Pattern(regexp = MONTH) String month,
                                              @RequestParam(required = false) String runId) {
        return reports.insights(userId, month, runId);
    }

    private Map<String, Object> account(AccountEntity a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("account_id", a.getAccountId());
        m.put("account_type", a.getAccountType());
        m.put("status", a.getStatus());
        m.put("opened_on", a.getOpenedOn());
        m.put("opening_balance", a.getOpeningBalance());
        m.put("closing_balance", a.getClosingBalance());
        return m;
    }
}
