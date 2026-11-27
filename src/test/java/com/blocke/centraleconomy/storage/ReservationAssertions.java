package com.blocke.centraleconomy.storage;

import com.blocke.bloeco.api.OperationContext;
import com.blocke.centraleconomy.application.integration.FundReservationService;
import com.blocke.centraleconomy.application.integration.ReservationRecord;
import com.blocke.centraleconomy.domain.ledger.LedgerException;
import com.blocke.centraleconomy.domain.money.Money;
import java.time.Clock;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class ReservationAssertions {
    private ReservationAssertions() {}

    public static void assertExpiredRelease(FundReservationService service, String client, UUID player, Clock clock) {
        for (boolean queryBeforeRelease : new boolean[]{false, true}) {
            String key = UUID.randomUUID().toString();
            var context = new OperationContext("test.release", key, "取消冻结", key);
            var expired = service.reserve(client, player, Money.ofMinor(100), clock.instant().minusSeconds(1),
                    new OperationContext("test.reserve", key, "竞拍冻结", "reserve-" + key));
            UUID id = expired.reservationId();
            if (queryBeforeRelease) assertEquals(ReservationRecord.Status.EXPIRED, service.reservation(client, id).status());
            var released = service.release(client, id, context);
            assertEquals(ReservationRecord.Status.EXPIRED, released.status());
            assertEquals(released, service.release(client, id, context));
            var changed = new OperationContext("test.release", key, "更改原因", key);
            assertEquals(LedgerException.Code.IDEMPOTENCY_CONFLICT,
                    assertThrows(LedgerException.class, () -> service.release(client, id, changed)).code());
        }
    }
}
