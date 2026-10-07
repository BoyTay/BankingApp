package vn.edu.wallet.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** GoF Creator: the shared export workflow calls a factory method supplied by a concrete creator. */
public abstract class StatementExporterCreator {
    protected abstract StatementExporter createExporter();

    public final byte[] export(Statement statement) throws IOException {
        if (statement == null || statement.walletCode() == null || statement.lines() == null) {
            throw new IllegalArgumentException("Statement chưa đầy đủ");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        createExporter().write(statement, output);
        return output.toByteArray();
    }
}
