package vn.edu.wallet.account;

import java.time.LocalDate;
import java.util.UUID;

public record AccountOpening(UUID id, UUID ownerId, String code, String type,
                             UUID requestKey, LocalDate feeStartsOn) {}
