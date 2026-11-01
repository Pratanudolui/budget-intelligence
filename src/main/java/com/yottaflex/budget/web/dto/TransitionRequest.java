package com.yottaflex.budget.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TransitionRequest(@NotBlank String toStatus, @Size(max = 400) String note) {
}
