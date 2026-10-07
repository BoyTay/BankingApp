package vn.edu.wallet.auth;

import java.util.UUID;

public record Principal(UUID userId, UUID sessionId, String role) {}
