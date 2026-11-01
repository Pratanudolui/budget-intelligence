package com.yottaflex.budget.cli;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yottaflex.budget.engine.BudgetIntelligenceEngine;
import com.yottaflex.budget.engine.EngineResult;
import com.yottaflex.budget.evaluation.Evaluator;
import com.yottaflex.budget.generator.GeneratorConfig;
import com.yottaflex.budget.generator.SyntheticDataGenerator;
import com.yottaflex.budget.model.Dataset;
import com.yottaflex.budget.model.GroundTruth;
import com.yottaflex.budget.util.DatasetIO;
import com.yottaflex.budget.validation.DatasetValidator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/**
 * Offline commands that do not need a database:
 * <pre>
 *   generate  --scale tiny|starter|full [--seed N] [--out DIR]
 *   validate  --data DIR [--scale S]
 *   evaluate  --data DIR            (runs the engine on CSV input and scores it against ground truth)
 *   repro     --scale S [--seed N]  (generates twice and compares checksums)
 * </pre>
 */
public final class Cli {

    public static final Set<String> COMMANDS = Set.of("generate", "validate", "evaluate", "repro");
    // Fixed "\n" line endings so file checksums are identical on Windows, macOS and Linux.
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setDefaultPrettyPrinter(new DefaultPrettyPrinter()
                    .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                    .withArrayIndenter(new DefaultIndenter("  ", "\n")));

    private Cli() {
    }

    public static int run(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        String cmd = args[0];
        long seed = Long.parseLong(opts.getOrDefault("seed", String.valueOf(GeneratorConfig.DEFAULT_SEED)));
        String scale = opts.getOrDefault("scale", "starter");
        switch (cmd) {
            case "generate" -> {
                Path out = Path.of(opts.getOrDefault("out", "data/" + scale));
                generate(GeneratorConfig.forScale(scale, seed), scale, out);
                return validate(out, GeneratorConfig.forScale(scale, seed)) ? 0 : 2;
            }
            case "validate" -> {
                Path dir = Path.of(opts.getOrDefault("data", "data/" + scale));
                GeneratorConfig cfg = opts.containsKey("scale") ? GeneratorConfig.forScale(scale, seed) : null;
                return validate(dir, cfg) ? 0 : 2;
            }
            case "evaluate" -> {
                Path dir = Path.of(opts.getOrDefault("data", "data/" + scale));
                evaluate(dir);
                return 0;
            }
            case "repro" -> {
                return repro(GeneratorConfig.forScale(scale, seed), scale) ? 0 : 3;
            }
            default -> throw new IllegalArgumentException("Unknown command " + cmd);
        }
    }

    public static void generate(GeneratorConfig cfg, String scale, Path out) throws Exception {
        long start = System.currentTimeMillis();
        System.out.printf("Generating scale=%s seed=%d users=%d transactions=%d -> %s%n", scale, cfg.seed(),
                cfg.users(), cfg.transactions(), out.toAbsolutePath());
        SyntheticDataGenerator.Result r = new SyntheticDataGenerator(cfg).generate();
        DatasetIO.writeInput(r.dataset(), out);
        DatasetIO.writeGroundTruth(r.truth(), out);
        writeTaxonomyJson(r.dataset(), out);
        writeManifest(cfg, scale, out);
        System.out.printf("Done in %.1fs%n", (System.currentTimeMillis() - start) / 1000.0);
    }

    private static void writeTaxonomyJson(Dataset d, Path out) throws IOException {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("categories", d.categories);
        json.put("category_aliases", d.categoryAliases);
        json.put("category_rules", d.categoryRules);
        JSON.writeValue(out.resolve("input").resolve("taxonomy.json").toFile(), json);
    }

    private static void writeManifest(GeneratorConfig cfg, String scale, Path out) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("generator", "com.yottaflex.budget.generator.SyntheticDataGenerator");
        m.put("generator_version", "1.0.0");
        m.put("command", "java -jar target/budget-intelligence.jar generate --scale " + scale + " --seed " + cfg.seed());
        m.put("seed", cfg.seed());
        m.put("scale", scale);
        m.put("users", cfg.users());
        m.put("transactions", cfg.transactions());
        m.put("year", cfg.year());
        m.put("data_provenance", "100% synthetic. No external datasets used; merchant and person names are invented.");
        m.put("files", fileTable(out));
        JSON.writeValue(out.resolve("manifest.json").toFile(), m);
    }

    private static List<Map<String, Object>> fileTable(Path out) throws Exception {
        List<Map<String, Object>> files = new ArrayList<>();
        for (String sub : List.of("input", "ground_truth")) {
            try (var stream = Files.list(out.resolve(sub))) {
                for (Path p : stream.sorted().toList()) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("path", sub + "/" + p.getFileName());
                    f.put("rows", p.toString().endsWith(".csv") ? countLines(p) - 1 : null);
                    f.put("sha256", sha256(p));
                    files.add(f);
                }
            }
        }
        return files;
    }

    public static boolean validate(Path dir, GeneratorConfig cfg) throws IOException {
        Dataset d = DatasetIO.readInput(dir);
        GroundTruth g = DatasetIO.readGroundTruth(dir);
        List<DatasetValidator.Check> checks = new DatasetValidator().validate(d, g,
                cfg == null ? null : cfg.users(), cfg == null ? null : cfg.transactions());
        long failed = checks.stream().filter(c -> "FAIL".equals(c.status())).count();
        System.out.println("\nVALIDATION (" + dir + ")");
        for (DatasetValidator.Check c : checks) {
            System.out.printf("  [%s] %-55s violations=%-6d %s%n", c.status(), c.name(), c.violations(), c.detail());
        }
        System.out.printf("  => %d checks, %d failed%n", checks.size(), failed);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("dataset", dir.toString());
        summary.put("checks_total", checks.size());
        summary.put("checks_failed", failed);
        summary.put("checks", checks);
        JSON.writeValue(dir.resolve("validation_summary.json").toFile(), summary);
        return failed == 0;
    }

    public static Map<String, Object> evaluate(Path dir) throws IOException {
        long start = System.currentTimeMillis();
        Dataset d = DatasetIO.readInput(dir);
        GroundTruth g = DatasetIO.readGroundTruth(dir);
        EngineResult r = new BudgetIntelligenceEngine().run(d);
        Map<String, Object> report = new Evaluator().evaluate(d, g, r);
        report.put("runtime_seconds", (System.currentTimeMillis() - start) / 1000.0);
        String json = JSON.writeValueAsString(report);
        Files.writeString(dir.resolve("evaluation_report.json"), json + "\n");
        System.out.println(json);
        return report;
    }

    private static boolean repro(GeneratorConfig cfg, String scale) throws Exception {
        Path base = Files.createTempDirectory("repro-");
        Path a = base.resolve("run1");
        Path b = base.resolve("run2");
        generate(cfg, scale, a);
        generate(cfg, scale, b);
        List<Map<String, Object>> fa = fileTable(a);
        List<Map<String, Object>> fb = fileTable(b);
        boolean same = fa.equals(fb);
        System.out.println("\nREPRODUCIBILITY CHECK (seed=" + cfg.seed() + ", scale=" + scale + ")");
        for (int i = 0; i < fa.size(); i++) {
            Map<String, Object> x = fa.get(i);
            Map<String, Object> y = fb.get(i);
            System.out.printf("  %-36s rows=%-8s run1=%s run2=%s %s%n", x.get("path"), x.get("rows"),
                    ((String) x.get("sha256")).substring(0, 16), ((String) y.get("sha256")).substring(0, 16),
                    x.equals(y) ? "MATCH" : "DIFF");
        }
        System.out.println(same ? "  => IDENTICAL row counts and checksums" : "  => MISMATCH");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("seed", cfg.seed());
        report.put("scale", scale);
        report.put("identical", same);
        report.put("run1", fa);
        report.put("run2", fb);
        Path outDir = Path.of("docs");
        Files.createDirectories(outDir);
        JSON.writeValue(outDir.resolve("reproducibility_" + scale + ".json").toFile(), report);
        return same;
    }

    private static long countLines(Path p) throws IOException {
        try (var lines = Files.lines(p)) {
            return lines.count();
        }
    }

    public static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                if (key.contains("=")) {
                    m.put(key.substring(0, key.indexOf('=')), key.substring(key.indexOf('=') + 1));
                } else if (i + 1 < args.length) {
                    m.put(key, args[++i]);
                }
            }
        }
        return m;
    }
}
