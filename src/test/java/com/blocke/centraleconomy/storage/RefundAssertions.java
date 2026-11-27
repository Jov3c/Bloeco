package com.blocke.centraleconomy.storage;

import com.blocke.bloeco.api.OperationContext;
import com.blocke.centraleconomy.application.integration.InstitutionSettlementService;
import com.blocke.centraleconomy.application.integration.SettlementRecord;
import com.blocke.centraleconomy.domain.money.Money;
import com.blocke.centraleconomy.domain.ledger.LedgerException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class RefundAssertions {
    private RefundAssertions() {
    }

    public static void assertRefundTaxRemainder(InstitutionSettlementService service, SettlementRecord charge) {
        var excess = assertThrows(LedgerException.class,
                () -> refund(service, charge, 1_001, "excess"));
        assertEquals(LedgerException.Code.POLICY_REJECTED, excess.code());
        assertEquals(17, refund(service, charge, 334, "refund-1").tax().minor());
        assertEquals(17, refund(service, charge, 334, "refund-2").tax().minor());
        assertEquals(16, refund(service, charge, 332, "refund-3").tax().minor());
    }

    private static SettlementRecord refund(InstitutionSettlementService service, SettlementRecord charge,
                                           long amount, String key) {
        return service.refund(charge.clientId(), charge.operationId(), Money.ofMinor(amount),
                new OperationContext("test.refund", charge.businessReference(), "分批退款", key));
    }
}
