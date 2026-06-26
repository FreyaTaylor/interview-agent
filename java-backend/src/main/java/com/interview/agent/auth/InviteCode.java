package com.interview.agent.auth;

import java.time.LocalDateTime;

/** invite_code 表实体。 */
public record InviteCode(
        Long id,
        String codeHash,
        String note,
        Long createdBy,
        Long usedByUserId,
        LocalDateTime usedAt,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {
}