package com.yottaflex.budget.web;

import com.yottaflex.budget.persistence.entity.PipelineRunEntity;
import com.yottaflex.budget.service.DatasetLoaderService;
import com.yottaflex.budget.service.PipelineService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in: loads the full 500k-transaction dataset into PostgreSQL and runs the pipeline.
 * Run with: mvn test -Dtest=FullScaleDatabaseTest -DfullScale=data/full
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "fullScale", matches = ".+")
class FullScaleDatabaseTest {

    private static EmbeddedPostgres pg;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        pg = EmbeddedPostgres.builder().start();
        r.add("spring.datasource.url", () -> pg.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
    }

    @AfterAll
    static void stop() throws Exception {
        pg.close();
    }

    @Autowired
    DatasetLoaderService loader;
    @Autowired
    PipelineService pipeline;

    @Test
    void loadsAndRunsFullDataset() throws Exception {
        long t0 = System.currentTimeMillis();
        Map<String, Long> counts = loader.load(Path.of(System.getProperty("fullScale")), "full-scale-test");
        long t1 = System.currentTimeMillis();
        PipelineRunEntity run = pipeline.run("full-scale-test");
        long t2 = System.currentTimeMillis();
        System.out.printf("FULL SCALE: load %d ms, pipeline %d ms, counts %s, run %s exceptions=%d insights=%d series=%d%n",
                t1 - t0, t2 - t1, counts, run.getStatus(), run.getExceptions(), run.getInsights(), run.getRecurringSeries());
        assertThat(counts.get("ledger_transaction")).isEqualTo(500_000L);
        assertThat(run.getStatus()).isEqualTo("COMPLETED");
        assertThat(run.getMetricsJson()).contains("\"transfers\"");
    }
}
