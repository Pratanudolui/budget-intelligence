package com.yottaflex.budget.web;

import com.yottaflex.budget.service.ReportService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Traceability: every decision about a transaction, with the rule and links that produced it. */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final ReportService reports;

    public TransactionController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/{transactionId}/trace")
    public Map<String, Object> trace(@PathVariable String transactionId, @RequestParam(required = false) String runId) {
        return reports.trace(transactionId, runId);
    }
}
