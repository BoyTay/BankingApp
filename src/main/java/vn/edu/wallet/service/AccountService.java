package vn.edu.wallet.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.wallet.api.ApiDtos;
import vn.edu.wallet.api.ApiException;
import vn.edu.wallet.account.AccountFactory;
import vn.edu.wallet.account.AccountOpening;

@Service
public class AccountService {
    private final JdbcTemplate db;
    private final Map<String, AccountFactory> factories;
    private final SavingsService savings;

    public AccountService(JdbcTemplate db, List<AccountFactory> factories, SavingsService savings) {
        this.db = db;
        this.factories = factories.stream().collect(Collectors.toUnmodifiableMap(AccountFactory::type, Function.identity()));
        this.savings = savings;
    }

    public List<ApiDtos.AccountView> list(UUID userId) {
        return db.query("""
                SELECT id,wallet_code,account_type,account_status,is_default,balance_dong
                FROM wallets WHERE owner_id=? ORDER BY is_default DESC,created_at,id
                """, (rs, row) -> map(rs), userId);
    }

    public ApiDtos.AccountView get(UUID userId, UUID accountId) {
        List<ApiDtos.AccountView> rows = db.query("""
                SELECT id,wallet_code,account_type,account_status,is_default,balance_dong
                FROM wallets WHERE owner_id=? AND id=?
                """, (rs, row) -> map(rs), userId, accountId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Không tìm thấy tài khoản");
        return rows.getFirst();
    }

    @Transactional
    public OpenResult open(UUID userId, ApiDtos.AccountCreate request) {
        if (request == null || request.requestKey() == null || request.type() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Thiếu thông tin mở tài khoản");
        }
        String type = request.type().trim().toUpperCase(java.util.Locale.ROOT);
        AccountFactory factory = factories.get(type);
        if (factory == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ACCOUNT_TYPE_UNAVAILABLE",
                    "Loại tài khoản chưa được hỗ trợ");
        }
        if ("CHECKING".equals(type) && (request.fundingAccountId() != null || request.amountDong() != null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "Tài khoản Thanh toán không cần tài khoản nguồn hoặc tiền gửi ban đầu");
        }
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))",
                userId + ":account-open:" + request.requestKey());
        List<UUID> prior = db.query("SELECT id FROM wallets WHERE owner_id=? AND creation_request_key=?",
                (rs, row) -> rs.getObject("id", UUID.class), userId, request.requestKey());
        if (!prior.isEmpty()) {
            ApiDtos.AccountView account = get(userId, prior.getFirst());
            boolean same = account.accountType().equals(type)
                    && (!"SAVINGS".equals(type) || savings.sameOpening(account.accountId(), request));
            if (!same) throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_KEY_CONFLICT",
                    "Mã yêu cầu đã được dùng để mở tài khoản khác");
            return new OpenResult(account, true);
        }
        AccountOpening opening = factory.create(userId, request.requestKey());
        if ("SAVINGS".equals(type)) {
            savings.open(userId, opening, request);
        } else {
            db.update("""
                    INSERT INTO wallets(id,owner_id,wallet_code,account_type,is_default,creation_request_key,fee_starts_on)
                    VALUES (?,?,?,?,false,?,?)
                    """, opening.id(), opening.ownerId(), opening.code(), opening.type(),
                    opening.requestKey(), java.sql.Date.valueOf(opening.feeStartsOn()));
        }
        return new OpenResult(get(userId, opening.id()), false);
    }

    private static ApiDtos.AccountView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApiDtos.AccountView(rs.getObject("id", UUID.class), rs.getString("wallet_code"),
                rs.getString("account_type"), rs.getString("account_status"), rs.getBoolean("is_default"),
                rs.getLong("balance_dong"));
    }

    public record OpenResult(ApiDtos.AccountView account, boolean replayed) {}
}
