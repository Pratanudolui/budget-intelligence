package com.yottaflex.budget.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yottaflex.budget.util.DatasetIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Seeds PostgreSQL from a generated dataset folder (input/*.csv only - ground truth is never loaded). */
@Service
public class DatasetLoaderService {

    private static final Logger log = LoggerFactory.getLogger(DatasetLoaderService.class);

    /** Parent tables before children, so foreign keys are satisfied during COPY. */
    private static final String[][] LOAD_ORDER = {
            {"category", DatasetIO.CATEGORIES},
            {"category_alias", DatasetIO.CATEGORY_ALIASES},
            {"merchant", DatasetIO.MERCHANTS},
            {"merchant_category", DatasetIO.MERCHANT_CATEGORIES},
            {"category_rule", DatasetIO.CATEGORY_RULES},
            {"user_profile", DatasetIO.USERS},
            {"account", DatasetIO.ACCOUNTS},
            {"ledger_transaction", DatasetIO.TRANSACTIONS},
            {"budget", DatasetIO.BUDGETS}};

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DatasetLoaderService(DataSource dataSource, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Long> load(Path datasetDir, String actor) throws Exception {
        Path input = datasetDir.resolve("input");
        if (!Files.isDirectory(input)) {
            throw new NotFoundException("Dataset input folder not found: " + input.toAbsolutePath());
        }
        // Replacing the dataset invalidates run outputs that reference its rows. pipeline_run rows (including
        // failed runs) and dataset_load rows are kept as an audit trail of what ran when.
        jdbc.execute("TRUNCATE exception_event, exception_record, insight, budget_comparison, recurring_series_member, "
                + "recurring_series, refund_link, transfer_link, transaction_classification, "
                + "budget_event, budget, ledger_transaction, account, user_profile, category_rule, merchant_category, "
                + "merchant, category_alias, category RESTART IDENTITY");
        Map<String, Long> counts = new LinkedHashMap<>();
        Connection connection = DataSourceUtils.getConnection(dataSource);
        for (String[] entry : LOAD_ORDER) {
            long start = System.currentTimeMillis();
            long rows = PgCopy.copyCsvFile(connection, entry[0], input.resolve(entry[1]));
            counts.put(entry[0], rows);
            log.info("Loaded {} rows into {} in {} ms", rows, entry[0], System.currentTimeMillis() - start);
        }
        jdbc.update("INSERT INTO dataset_load (source_path, loaded_by, loaded_at, row_counts) VALUES (?, ?, ?, ?)",
                datasetDir.toAbsolutePath().normalize().toString(), actor, java.sql.Timestamp.from(Instant.now()),
                mapper.writeValueAsString(counts));
        return counts;
    }

    /** Folder of the most recently loaded dataset, used to locate its ground truth for evaluation. */
    public Path latestDatasetPath() {
        return jdbc.query("SELECT source_path FROM dataset_load ORDER BY load_id DESC LIMIT 1",
                rs -> rs.next() ? Path.of(rs.getString(1)) : null);
    }

    public Map<String, Long> tableCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : new String[]{"user_profile", "account", "merchant", "category", "category_rule",
                "ledger_transaction", "budget", "pipeline_run", "exception_record", "insight"}) {
            counts.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
        }
        return counts;
    }
}
