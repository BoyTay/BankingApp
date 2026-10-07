package vn.edu.wallet.expense;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.wallet.api.ApiDtos;
import vn.edu.wallet.api.ApiException;

@Service
public class ExpenseImportService {
    private static final long MAX_BYTES = 1_048_576;
    private final JdbcTemplate db;
    private final SampleFormatACsvAdapter sampleA;
    private final SampleFormatBCsvAdapter sampleB;

    public ExpenseImportService(JdbcTemplate db, SampleFormatACsvAdapter sampleA,
                                SampleFormatBCsvAdapter sampleB) {
        this.db = db;
        this.sampleA = sampleA;
        this.sampleB = sampleB;
    }

    @Transactional
    public ImportResult importFile(UUID ownerId, String formatCode, UUID requestKey, MultipartFile file) {
        if (requestKey == null || file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Thiếu khóa yêu cầu hoặc tệp CSV");
        }
        ExpenseCsvAdapter adapter = switch (formatCode == null ? "" : formatCode) {
            case "SAMPLE_A" -> sampleA;
            case "SAMPLE_B" -> sampleB;
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FORMAT",
                    "Định dạng nhập phải là SAMPLE_A hoặc SAMPLE_B");
        };
        if (file.getSize() > MAX_BYTES) throw tooLarge();
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CSV", "Không đọc được tệp CSV");
        }
        if (bytes.length > MAX_BYTES) throw tooLarge();
        String hash = sha256(bytes);
        // All imports take key lock first, then content lock. The unique constraints
        // remain a database backstop if two app instances race.
        advisoryLock(ownerId + ":import-key:" + requestKey);
        advisoryLock(ownerId + ":import-content:" + formatCode + ":" + hash);
        List<ImportRow> byKey = find("owner_user_id=? AND request_key=?", ownerId, requestKey);
        if (!byKey.isEmpty()) {
            ImportRow prior = byKey.getFirst();
            if (prior.format().equals(formatCode) && prior.hash().equals(hash)) {
                return new ImportResult(prior.view(), true);
            }
            throw new ApiException(HttpStatus.CONFLICT, "IMPORT_KEY_CONFLICT",
                    "Mã yêu cầu nhập đã dùng cho tệp khác");
        }
        List<ImportRow> byContent = find("owner_user_id=? AND format_code=? AND content_sha256=?",
                ownerId, formatCode, hash);
        if (!byContent.isEmpty()) return new ImportResult(byContent.getFirst().view(), true);
        List<ImportedExpense> expenses = adapter.read(bytes);
        UUID batchId = UUID.randomUUID();
        String sourceName = sourceName(file.getOriginalFilename());
        db.update("""
                INSERT INTO import_batches(id,owner_user_id,format_code,source_name,row_count,request_key,content_sha256)
                VALUES (?,?,?,?,?,?,?)
                """, batchId, ownerId, formatCode, sourceName, expenses.size(), requestKey, hash);
        for (ImportedExpense expense : expenses) {
            db.update("""
                    INSERT INTO imported_expenses(id,batch_id,source_row,spent_on,description,category,amount_dong)
                    VALUES (?,?,?,?,?,?,?)
                    """, UUID.randomUUID(), batchId, expense.sourceRow(), expense.spentOn(),
                    expense.description(), expense.category(), expense.amountDong());
        }
        return new ImportResult(find("id=?", batchId).getFirst().view(), false);
    }

    private void advisoryLock(String value) {
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))", value);
    }

    private List<ImportRow> find(String where, Object... values) {
        return db.query("""
                SELECT id,format_code,source_name,row_count,imported_at,content_sha256
                FROM import_batches WHERE
                """ + where, ExpenseImportService::map, values);
    }

    private static ImportRow map(ResultSet rs, int row) throws SQLException {
        return new ImportRow(new ApiDtos.ImportView(rs.getObject("id", UUID.class),
                rs.getString("format_code"), rs.getString("source_name"), rs.getInt("row_count"),
                rs.getTimestamp("imported_at").toInstant()), rs.getString("format_code"),
                rs.getString("content_sha256"));
    }

    private static String sourceName(String name) {
        if (name == null || name.isBlank()) return "upload.csv";
        String safe = name.replace('\\', '/');
        safe = safe.substring(safe.lastIndexOf('/') + 1).trim();
        if (safe.isEmpty()) return "upload.csv";
        return safe.length() > 255 ? safe.substring(0, 255) : safe;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "Tệp CSV vượt quá 1 MiB");
    }

    private record ImportRow(ApiDtos.ImportView view, String format, String hash) {}
    public record ImportResult(ApiDtos.ImportView view, boolean replayed) {}
}
