package com.yottaflex.budget.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record BudgetChangeRequest(@NotNull @DecimalMin("0.01") BigDecimal amount, @Size(max = 200) String reason) {
}
