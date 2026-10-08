package vn.edu.wallet.report;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.wallet.api.ApiException;
import vn.edu.wallet.service.WalletQueries;

@Service
public class StatementService {
    private static final int MAX_LINES = 10_000;
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private final JdbcTemplate db;
    private final WalletQueries wallets;
    private final CsvStatementCreator csv;
    private final PdfStatementCreator pdf;

    public StatementService(JdbcTemplate db, WalletQueries wallets,
                            CsvStatementCreator csv, PdfStatementCreator pdf) {
        this.db = db;
        this.wallets = wallets;
        this.csv = csv;
        this.pdf = pdf;
    }

    @Transactional(readOnly = true)
    public ExportedFile export(UUID userId, String from, String to, String requestedFormat) throws IOException {
        return export(userId, from, to, requestedFormat, null, null);
    }

    @Transactional(readOnly = true)
    public ExportedFile export(UUID userId, String from, String to, String requestedFormat,
                               Integer page, Integer size) throws IOException {
        return export(userId, null, from, to, requestedFormat, page, size);
    }

    @Transactional(readOnly = true)
    public ExportedFile export(UUID userId, UUID accountId, String from, String to, String requestedFormat,
                               Integer page, Integer size) throws IOException {
        DateRange range = DateRange.parse(from, to);
        String format = requestedFormat == null ? "" : requestedFormat.toLowerCase(Locale.ROOT);
        StatementExporterCreator creator = switch (format) {
            case "csv" -> csv;
            case "pdf" -> pdf;
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FORMAT", "Định dạng sao kê phải là csv hoặc pdf");
        };
        boolean paged = page != null || size != null;
        if (paged && (page == null || size == null || page < 0 || page > 1000 || size < 1 || size > 1000)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE",
                    "page phải từ 0 đến 1000 và size từ 1 đến 1000");
        }
        WalletQueries.WalletRow wallet = wallets.ownedBy(userId, accountId);
        List<Statement.Line> lines = db.query("""
                SELECT t.id,t.created_at,t.amount_dong,t.sender_wallet_id,
                       t.sender_balance_after_dong,t.recipient_balance_after_dong,
                       sw.wallet_code AS sender_code,rw.wallet_code AS recipient_code
                FROM transfers t JOIN wallets sw ON sw.id=t.sender_wallet_id
                JOIN wallets rw ON rw.id=t.recipient_wallet_id
                WHERE (t.sender_wallet_id=? OR t.recipient_wallet_id=?)
                  AND t.created_at>=? AND t.created_at<?
                ORDER BY t.created_at,t.id LIMIT ? OFFSET ?
                """, (rs, row) -> {
            boolean outgoing = wallet.id().equals(rs.getObject("sender_wallet_id", UUID.class));
            return new Statement.Line(rs.getTimestamp("created_at").toInstant(),
                    rs.getObject("id", UUID.class), outgoing ? "OUTGOING" : "INCOMING",
                    rs.getString(outgoing ? "recipient_code" : "sender_code"),
                    rs.getLong("amount_dong"), rs.getLong(outgoing
                            ? "sender_balance_after_dong" : "recipient_balance_after_dong"));
        }, wallet.id(), wallet.id(),
                Timestamp.from(range.from().atStartOfDay(ZoneOffset.UTC).toInstant()),
                Timestamp.from(range.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()),
                paged ? size + 1 : MAX_LINES + 1, paged ? (long) page * size : 0L);
        if (!paged && lines.size() > MAX_LINES) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "STATEMENT_TOO_LARGE",
                    "Sao kê vượt quá 10000 giao dịch");
        }
        boolean hasMore = paged && lines.size() > size;
        if (hasMore) lines = lines.subList(0, size);
        long incoming = 0;
        long outgoing = 0;
        for (Statement.Line line : lines) {
            if (line.direction().equals("INCOMING")) incoming = Math.addExact(incoming, line.amountDong());
            else outgoing = Math.addExact(outgoing, line.amountDong());
        }
        Statement statement = new Statement(wallet.code(), range.from(), range.to(),
                List.copyOf(lines), incoming, outgoing);
        byte[] bytes = creator.export(statement);
        String filename = "statement-" + FILE_DATE.format(range.from()) + "-"
                + FILE_DATE.format(range.to()) + (paged ? "-page-" + (page + 1) : "") + "." + format;
        return new ExportedFile(filename, format.equals("csv") ? "text/csv; charset=UTF-8" : "application/pdf",
                bytes, paged ? page : null, paged ? size : null, hasMore);
    }

    public record ExportedFile(String filename, String contentType, byte[] bytes,
                               Integer page, Integer size, boolean hasMore) {}
}
