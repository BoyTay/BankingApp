package vn.edu.wallet.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

public final class PdfStatementExporter implements StatementExporter {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneOffset.UTC);
    private static final String FONT_RESOURCE = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";

    @Override
    public void write(Statement statement, OutputStream output) throws IOException {
        try (PDDocument document = new PDDocument(); InputStream fontStream =
                     PdfStatementExporter.class.getResourceAsStream(FONT_RESOURCE)) {
            if (fontStream == null) throw new IOException("Thiếu font Unicode cho PDF");
            PDType0Font font = PDType0Font.load(document, fontStream, true);
            PageWriter page = new PageWriter(document, font, statement);
            try {
                for (Statement.Line line : statement.lines()) {
                    page.ensureSpace();
                    page.row(TIME.format(line.occurredAt()),
                            line.direction().equals("OUTGOING") ? "Chuyển đi" : "Nhận vào",
                            line.counterpartyWalletCode(), Long.toString(line.amountDong()),
                            Long.toString(line.myBalanceAfterDong()));
                }
                page.ensureSpace();
                page.summary(statement.totalIncomingDong(), statement.totalOutgoingDong());
            } finally {
                page.close();
            }
            document.save(output);
        }
    }

    private static final class PageWriter implements AutoCloseable {
        private final PDDocument document;
        private final PDType0Font font;
        private final Statement statement;
        private PDPageContentStream content;
        private float y;
        private int pageNumber;

        private PageWriter(PDDocument document, PDType0Font font, Statement statement) throws IOException {
            this.document = document;
            this.font = font;
            this.statement = statement;
            newPage();
        }

        private void newPage() throws IOException {
            close();
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
            pageNumber++;
            y = 795;
            text(42, y, 16, "SAO KÊ CHUYỂN TIỀN"); y -= 24;
            text(42, y, 9, "Ví: " + statement.walletCode()); y -= 15;
            text(42, y, 9, "Từ " + statement.from() + " đến " + statement.to() + " (UTC)"); y -= 20;
            text(42, y, 8, "Thời gian UTC");
            text(147, y, 8, "Loại");
            text(218, y, 8, "Ví đối ứng");
            text(370, y, 8, "Số tiền");
            text(470, y, 8, "Số dư sau");
            y -= 14;
        }

        private void ensureSpace() throws IOException {
            if (y < 65) newPage();
        }

        private void row(String time, String direction, String counterparty, String amount, String balance)
                throws IOException {
            text(42, y, 8, time);
            text(147, y, 8, direction);
            text(218, y, 8, counterparty);
            text(370, y, 8, amount);
            text(470, y, 8, balance);
            y -= 14;
        }

        private void summary(long incoming, long outgoing) throws IOException {
            y -= 8;
            text(42, y, 9, "Tổng nhận vào: " + incoming + " VND"); y -= 16;
            text(42, y, 9, "Tổng chuyển đi: " + outgoing + " VND");
        }

        private void text(float x, float y, int size, String value) throws IOException {
            content.beginText();
            content.setFont(font, size);
            content.newLineAtOffset(x, y);
            content.showText(value);
            content.endText();
        }

        @Override public void close() throws IOException {
            if (content != null) {
                text(42, 35, 8, "Trang " + pageNumber);
                content.close();
                content = null;
            }
        }
    }
}
