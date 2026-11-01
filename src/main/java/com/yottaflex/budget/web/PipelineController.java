package com.yottaflex.budget.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yottaflex.budget.persistence.entity.PipelineRunEntity;
import com.yottaflex.budget.persistence.repository.PipelineRunRepository;
import com.yottaflex.budget.service.NotFoundException;
import com.yottaflex.budget.service.PipelineService;
import com.yottaflex.budget.service.ReportService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Operations analyst: trigger runs, view run summaries and evaluation metrics. */
@RestController
@RequestMapping("/api/pipeline/runs")
public class PipelineController {

    private final PipelineService pipeline;
    private final PipelineRunRepository runs;
    private final ReportService reports;
    private final ObjectMapper mapper;

    public PipelineController(PipelineService pipeline, PipelineRunRepository runs, ReportService reports,
                              ObjectMapper mapper) {
        this.pipeline = pipeline;
        this.runs = runs;
        this.reports = reports;
        this.mapper = mapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> run(@RequestHeader(value = "X-Actor", defaultValue = "ops-analyst") String actor) {
        return view(pipeline.run(actor), false);
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return runs.findAllByOrderByStartedAtDesc().stream().map(r -> view(r, false)).toList();
    }

    @GetMapping("/{runId}")
    public Map<String, Object> get(@PathVariable String runId) {
        Map<String, Object> out = view(find(runId), false);
        out.put("summary", reports.runSummary(runId));
        return out;
    }

    @GetMapping("/{runId}/evaluation")
    public Map<String, Object> evaluation(@PathVariable String runId) {
        return view(find(runId), true);
    }

    private PipelineRunEntity find(String runId) {
        return runs.findById(runId).orElseThrow(() -> new NotFoundException("Run " + runId + " not found"));
    }

    private Map<String, Object> view(PipelineRunEntity r, boolean withMetrics) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", r.getRunId());
        out.put("status", r.getStatus());
        out.put("triggered_by", r.getTriggeredBy());
        out.put("started_at", r.getStartedAt());
        out.put("finished_at", r.getFinishedAt());
        out.put("transactions", r.getTransactions());
        out.put("exceptions", r.getExceptions());
        out.put("insights", r.getInsights());
        out.put("recurring_series", r.getRecurringSeries());
        out.put("error_message", r.getErrorMessage());
        out.put("evaluated_against_ground_truth", r.getMetricsJson() != null);
        if (withMetrics && r.getMetricsJson() != null) {
            try {
                JsonNode metrics = mapper.readTree(r.getMetricsJson());
                out.put("metrics", metrics);
            } catch (Exception e) {
                out.put("metrics_error", e.getMessage());
            }
        }
        return out;
    }
}
