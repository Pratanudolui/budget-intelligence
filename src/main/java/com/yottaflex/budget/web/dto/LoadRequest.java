package com.yottaflex.budget.web.dto;

import jakarta.validation.constraints.NotBlank;

public record LoadRequest(@NotBlank String path) {
}
