package com.yottaflex.budget.web;

import com.yottaflex.budget.persistence.entity.ExceptionEventEntity;
import com.yottaflex.budget.persistence.entity.ExceptionRecordEntity;
import com.yottaflex.budget.service.ExceptionReviewService;
import com.yottaflex.budget.web.dto.TransitionRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Risk / compliance reviewer: browse the exception queue and move items through their lifecycle. */
@RestController
@RequestMapping("/api/exceptions")
@Validated
public class ExceptionController {

    private final ExceptionReviewService service;

    public ExceptionController(ExceptionReviewService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> search(@RequestParam(required = false) String runId,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String type,
                                      @RequestParam(required = false) String userId,
                                      @RequestParam(defaultValue = "0") @Min(0) int page,
                                      @RequestParam(defaultValue = "50") @Min(1) @Max(500) int size) {
        Page<ExceptionRecordEntity> p = service.search(runId, status, type, userId, page, size);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", p.getTotalElements());
        out.put("page", page);
        out.put("items", p.getContent());
        return out;
    }

    @PostMapping("/{exceptionId}/transitions")
    public ExceptionRecordEntity transition(@PathVariable String exceptionId,
                                            @RequestParam(required = false) String runId,
                                            @Valid @RequestBody TransitionRequest request,
                                            @RequestHeader(value = "X-Actor", defaultValue = "reviewer") String actor) {
        return service.transition(runId, exceptionId, request.toStatus(), actor, request.note());
    }

    @GetMapping("/{exceptionId}/events")
    public List<ExceptionEventEntity> events(@PathVariable String exceptionId,
                                             @RequestParam(required = false) String runId) {
        return service.history(runId, exceptionId);
    }
}
