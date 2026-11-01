package com.yottaflex.budget.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yottaflex.budget.cli.Cli;
import com.yottaflex.budget.generator.GeneratorConfig;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end against a real PostgreSQL (embedded binaries, no Docker): Flyway migration, COPY seeding,
 * pipeline run, customer/ops/reviewer endpoints, lifecycle rules and error handling.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiIntegrationTest {

    private static final EmbeddedPostgres PG;
    private static final Path DATA;

    static {
        try {
            PG = EmbeddedPostgres.builder().start();
            DATA = Files.createTempDirectory("bi-it-");
            Cli.generate(GeneratorConfig.forScale("tiny", GeneratorConfig.DEFAULT_SEED), "tiny", DATA);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
    }

    @AfterAll
    static void stop() throws Exception {
        PG.close();
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;

    private JsonNode json(MvcResult r) throws Exception {
        return mapper.readTree(r.getResponse().getContentAsString());
    }

    @Test
    @Order(1)
    void pipelineBeforeLoadIsAConflict() throws Exception {
        mvc.perform(post("/api/pipeline/runs")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("No transactions loaded")));
    }

    @Test
    @Order(2)
    void loadsDatasetWithCopy() throws Exception {
        mvc.perform(post("/api/admin/datasets/load").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"" + DATA.toString().replace("\\", "\\\\") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ledger_transaction").value(10_000))
                .andExpect(jsonPath("$.user_profile").value(20));
    }

    @Test
    @Order(3)
    void runsPipelineAndStoresEvaluation() throws Exception {
        // RUN-000001 failed in test 1 (empty database) and is kept as history; this is RUN-000002.
        JsonNode run = json(mvc.perform(post("/api/pipeline/runs").header("X-Actor", "it-ops"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(run.get("run_id").asText()).isEqualTo("RUN-000002");
        assertThat(run.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.get("transactions").asInt()).isEqualTo(10_000);

        JsonNode eval = json(mvc.perform(get("/api/pipeline/runs/RUN-000002/evaluation")).andExpect(status().isOk()).andReturn());
        assertThat(eval.at("/metrics/transfers/recall").asDouble()).isEqualTo(1.0);
        assertThat(eval.at("/metrics/timezone/engine_accuracy").asDouble()).isEqualTo(1.0);

        JsonNode summary = json(mvc.perform(get("/api/pipeline/runs/RUN-000002")).andExpect(status().isOk()).andReturn());
        JsonNode recon = summary.at("/summary/ledger_reconciliation");
        assertThat(recon.get("reconciled").asLong()).isEqualTo(recon.get("accounts").asLong());

        mvc.perform(get("/api/pipeline/runs")).andExpect(jsonPath("$[1].status").value("FAILED"));
    }

    @Test
    @Order(4)
    void customerViews() throws Exception {
        mvc.perform(get("/api/users/USR-000001")).andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts[0].account_id").value("ACC-USR-000001-01"));
        JsonNode report = json(mvc.perform(get("/api/users/USR-000001/budget-report").param("month", "2026-03"))
                .andExpect(status().isOk()).andReturn());
        assertThat(report.get("budgets").size()).isGreaterThan(0);
        mvc.perform(get("/api/users/USR-000001/recurring")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].series_id").exists());
        mvc.perform(get("/api/users/USR-000001/transactions").param("month", "2026-03").param("size", "5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5));
        mvc.perform(get("/api/users/USR-000001/insights")).andExpect(status().isOk());
    }

    @Test
    @Order(5)
    void traceShowsDecisionTrail() throws Exception {
        JsonNode trace = json(mvc.perform(get("/api/transactions/TXN-2026-00000001/trace")).andExpect(status().isOk()).andReturn());
        assertThat(trace.at("/classification/category_code").asText()).isNotBlank();
        assertThat(trace.at("/classification/method").asText()).isNotBlank();
    }

    @Test
    @Order(6)
    void exceptionLifecycleIsEnforcedAndAudited() throws Exception {
        JsonNode page = json(mvc.perform(get("/api/exceptions").param("status", "OPEN").param("size", "1"))
                .andExpect(status().isOk()).andReturn());
        assertThat(page.get("total").asLong()).isGreaterThan(0);
        String id = page.at("/items/0/exceptionId").asText();

        mvc.perform(post("/api/exceptions/" + id + "/transitions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"toStatus\":\"RESOLVED\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/exceptions/" + id + "/transitions").header("X-Actor", "reviewer-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"toStatus\":\"IN_REVIEW\",\"note\":\"checking\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_REVIEW"))
                .andExpect(jsonPath("$.assignedTo").value("reviewer-1"));
        mvc.perform(post("/api/exceptions/" + id + "/transitions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"toStatus\":\"RESOLVED\",\"note\":\"merchant added\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/exceptions/" + id + "/events")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].fromStatus").value("OPEN"))
                .andExpect(jsonPath("$[1].toStatus").value("RESOLVED"));
        mvc.perform(post("/api/exceptions/" + id + "/transitions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"toStatus\":\"BOGUS\"}")).andExpect(status().isBadRequest());
    }

    @Test
    @Order(7)
    void budgetChangesKeepHistory() throws Exception {
        String budgetId = "BUD-USR-000001-2026-03-GROC";
        mvc.perform(put("/api/budgets/" + budgetId + "/amount").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 999.00, \"reason\": \"raise\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/budgets/" + budgetId + "/history")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].newAmount").value(999.00))
                .andExpect(jsonPath("$[0].oldAmount").exists());
        mvc.perform(put("/api/budgets/" + budgetId + "/amount").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": -5}")).andExpect(status().isBadRequest());
    }

    @Test
    @Order(8)
    void errorsAreStructured() throws Exception {
        mvc.perform(get("/api/users/USR-999999")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404)).andExpect(jsonPath("$.path").value("/api/users/USR-999999"));
        mvc.perform(get("/api/users/USR-000001/budget-report").param("month", "March"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/pipeline/runs/RUN-424242")).andExpect(status().isNotFound());
    }
}
