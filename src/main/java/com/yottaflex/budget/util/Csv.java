package com.yottaflex.budget.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Minimal RFC-4180 CSV reader/writer. Kept dependency-free so the generator runs without Spring. */
public final class Csv {

    private Csv() {
    }

    public static <T> void write(Path file, String header, List<T> rows, Function<T, Object[]> mapper) {
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write(header);
                w.write('\n');
                StringBuilder sb = new StringBuilder(256);
                for (T row : rows) {
                    sb.setLength(0);
                    Object[] values = mapper.apply(row);
                    for (int i = 0; i < values.length; i++) {
                        if (i > 0) {
                            sb.append(',');
                        }
                        appendValue(sb, values[i]);
                    }
                    sb.append('\n');
                    w.write(sb.toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing " + file, e);
        }
    }

    private static void appendValue(StringBuilder sb, Object value) {
        if (value == null) {
            return;
        }
        String s = value.toString();
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0) {
            sb.append('"').append(s.replace("\"", "\"\"")).append('"');
        } else {
            sb.append(s);
        }
    }

    /** Reads a CSV file into rows; empty fields become null. The header row is skipped. */
    public static List<String[]> read(Path file) {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line = r.readLine();
            if (line == null) {
                return rows;
            }
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty()) {
                    rows.add(parseLine(line));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading " + file, e);
        }
        return rows;
    }

    public static String[] parseLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.length() == 0 ? null : cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.length() == 0 ? null : cur.toString());
        return out.toArray(new String[0]);
    }
}
