package vn.edu.wallet.report;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

public final class CsvStatementExporter implements StatementExporter {
    private static final String[] HEADER = {
            "Thời gian UTC", "Mã giao dịch", "Loại", "Ví đối ứng", "Số tiền (VND)", "Số dư sau (VND)"
    };

    @Override
    public void write(Statement statement, OutputStream output) throws IOException {
        output.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        try (CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(output, StandardCharsets.UTF_8),
                CSVFormat.DEFAULT.builder().setHeader(HEADER).get())) {
            for (Statement.Line line : statement.lines()) {
                printer.printRecord(line.occurredAt(), line.transferId(),
                        line.direction().equals("OUTGOING") ? "Chuyển đi" : "Nhận vào",
                        line.counterpartyWalletCode(), line.amountDong(), line.myBalanceAfterDong());
            }
            printer.flush();
        }
    }
}
