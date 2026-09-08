package com.blocke.centraleconomy.domain.ledger;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable, balanced business transaction in the central journal. */
public record JournalEntry(
        UUID id,
        JournalType type,
        String memo,
        String clientId,
        String idempotencyKey,
        UUID reversalOf,
        Instant createdAt,
        List<Posting> postings) {

    public JournalEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(createdAt, "createdAt");
        if (memo == null || memo.isBlank() || memo.length() > 256) {
            throw new LedgerException(LedgerException.Code.INVALID_JOURNAL,
                    "journal memo must contain 1 through 256 characters");
        }
        if ((clientId == null) != (idempotencyKey == null)) {
            throw new LedgerException(LedgerException.Code.INVALID_JOURNAL,
                    "client id and idempotency key must be supplied together");
        }
        if (clientId != null && (clientId.isBlank() || clientId.length() > 64
                || idempotencyKey.isBlank() || idempotencyKey.length() > 128)) {
            throw new LedgerException(LedgerException.Code.INVALID_JOURNAL,
                    "invalid idempotency identity");
        }
        postings = List.copyOf(Objects.requireNonNull(postings, "postings"));
        if (postings.size() < 2) {
            throw new LedgerException(LedgerException.Code.INVALID_JOURNAL,
                    "journal must contain at least two postings");
        }
        long sum = 0;
        try {
            for (Posting posting : postings) {
                sum = Math.addExact(sum, posting.amountMinor());
            }
        } catch (ArithmeticException exception) {
            throw new LedgerException(LedgerException.Code.INVALID_JOURNAL,
                    "journal posting sum exceeds the supported range", exception);
        }
        if (sum != 0) {
            throw new LedgerException(LedgerException.Code.INVALID_JOURNAL,
                    "journal postings must sum to zero");
        }
        if (type == JournalType.REVERSAL && reversalOf == null) {
            throw new LedgerException(LedgerException.Code.INVALID_JOURNAL,
                    "reversal journal must reference its original entry");
        }
    }

    public static JournalEntry create(
            UUID id,
            JournalType type,
            String memo,
            String clientId,
            String idempotencyKey,
            Instant createdAt,
            List<Posting> postings) {
        return new JournalEntry(id, type, memo, clientId, idempotencyKey,
                null, createdAt, postings);
    }

    public static JournalEntry reversal(
            UUID id,
            UUID originalId,
            String memo,
            String clientId,
            String idempotencyKey,
            Instant createdAt,
            List<Posting> postings) {
        return new JournalEntry(id, JournalType.REVERSAL, memo, clientId,
                idempotencyKey, originalId, createdAt, postings);
    }
}
