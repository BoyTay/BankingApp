package vn.edu.wallet.report;

import java.io.IOException;
import java.io.OutputStream;

public interface StatementExporter {
    void write(Statement statement, OutputStream output) throws IOException;
}
