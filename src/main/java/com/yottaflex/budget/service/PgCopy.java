package com.yottaflex.budget.service;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/** Thin wrapper around PostgreSQL COPY for bulk loads (500k rows load in seconds instead of minutes). */
public final class PgCopy {

    private PgCopy() {
    }

    public static long copyCsvFile(Connection connection, String table, Path csv) throws Exception {
        String header;
        try (var lines = Files.lines(csv, StandardCharsets.UTF_8)) {
            header = lines.findFirst().orElseThrow(() -> new IllegalArgumentException("Empty file " + csv));
        }
        CopyManager copy = connection.unwrap(PGConnection.class).getCopyAPI();
        try (Reader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            return copy.copyIn("COPY " + table + " (" + header + ") FROM STDIN WITH (FORMAT csv, HEADER true)", reader);
        }
    }

    /** Streams rows into a table; values are CSV-escaped and flushed to the server in ~4 MB chunks. */
    public static final class RowWriter implements AutoCloseable {
        private static final int FLUSH_BYTES = 4 << 20;
        private final CopyIn copyIn;
        private final StringBuilder buffer = new StringBuilder(FLUSH_BYTES + 4096);
        private long rows;

        public RowWriter(Connection connection, String table, String columns) throws SQLException {
            CopyManager copy = connection.unwrap(PGConnection.class).getCopyAPI();
            this.copyIn = copy.copyIn("COPY " + table + " (" + columns + ") FROM STDIN WITH (FORMAT csv)");
        }

        public void row(Object... values) throws SQLException {
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    buffer.append(',');
                }
                Object v = values[i];
                if (v != null) {
                    String s = v.toString();
                    buffer.append('"').append(s.replace("\"", "\"\"")).append('"');
                }
            }
            buffer.append('\n');
            rows++;
            if (buffer.length() >= FLUSH_BYTES) {
                flush();
            }
        }

        private void flush() throws SQLException {
            byte[] bytes = buffer.toString().getBytes(StandardCharsets.UTF_8);
            copyIn.writeToCopy(bytes, 0, bytes.length);
            buffer.setLength(0);
        }

        public long rows() {
            return rows;
        }

        @Override
        public void close() throws SQLException {
            flush();
            copyIn.endCopy();
        }
    }
}
