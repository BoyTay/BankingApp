package vn.edu.wallet.report;

import org.springframework.stereotype.Component;

@Component
public final class CsvStatementCreator extends StatementExporterCreator {
    @Override protected StatementExporter createExporter() { return new CsvStatementExporter(); }
}
