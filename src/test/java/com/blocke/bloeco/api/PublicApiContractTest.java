package com.blocke.bloeco.api;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicApiContractTest {

    @Test
    void currentVersionSupportsOnlyItsMajorContract() {
        assertEquals(new ApiVersion(1, 0, 0), ApiVersion.CURRENT);
        assertTrue(ApiVersion.CURRENT.supportsMajor(1));
        assertFalse(ApiVersion.CURRENT.supportsMajor(2));
    }

    @Test
    void moneyRepresentsZeroBalancesButRejectsNegativeMinorUnits() {
        assertEquals(0L, new MoneyAmount(0).minorUnits());
        assertEquals(1250L, MoneyAmount.positive(1250).minorUnits());
        assertThrows(IllegalArgumentException.class, () -> new MoneyAmount(-1));
        assertThrows(IllegalArgumentException.class, () -> MoneyAmount.positive(0));
    }

    @Test
    void descriptorNormalizesSafeIdsAndDefensivelyCopiesCapabilitiesAndPurposes() {
        Set<InstitutionCapability> capabilities = new LinkedHashSet<>();
        capabilities.add(InstitutionCapability.CHARGE_PLAYER);
        Set<String> purposes = new LinkedHashSet<>();
        purposes.add("Sales");

        InstitutionDescriptor descriptor = new InstitutionDescriptor(
                " Bloeco.Shop ", "Bloeco 商店", capabilities, purposes);
        capabilities.clear();
        purposes.clear();

        assertEquals("bloeco.shop", descriptor.clientId());
        assertEquals(Set.of(InstitutionCapability.CHARGE_PLAYER), descriptor.capabilities());
        assertEquals(Set.of("sales", "operating"), descriptor.accountPurposes());
        assertThrows(UnsupportedOperationException.class,
                () -> descriptor.capabilities().add(InstitutionCapability.PAY_PLAYER));
        assertThrows(IllegalArgumentException.class,
                () -> InstitutionDescriptor.of("../shop", "商店"));
    }

    @Test
    void operationContextAcceptsChineseMemoAndRejectsUnsafeOrOversizedFields() {
        OperationContext context = new OperationContext(
                "shop.purchase", "order-20261012-1", "购买 16 个钻石", "order:20261012:1:pay");

        assertEquals("shop.purchase", context.businessType());
        assertEquals("购买 16 个钻石", context.displayMemo());
        assertThrows(IllegalArgumentException.class,
                () -> new OperationContext("shop.purchase", "order-1", " ", "order:1"));
        assertThrows(IllegalArgumentException.class,
                () -> new OperationContext("shop.purchase", "order-1", "好".repeat(257), "order:1"));
        assertThrows(IllegalArgumentException.class,
                () -> new OperationContext("shop purchase", "order-1", "购买物品", "order:1"));
        assertThrows(IllegalArgumentException.class,
                () -> new OperationContext("shop.purchase", "order-1", "购买物品", "bad key"));
    }

    @Test
    void resultRequiresExactlyOneOfValueOrStableFailure() {
        EconomyResult<String> success = EconomyResult.success("ok");
        EconomyResult<String> failure = EconomyResult.failure(EconomyError.RESULT_UNKNOWN, "结果待确认");

        assertTrue(success.isSuccess());
        assertEquals("ok", success.value());
        assertFalse(failure.isSuccess());
        assertEquals(EconomyError.RESULT_UNKNOWN, failure.error());
        assertThrows(IllegalArgumentException.class,
                () -> new EconomyResult<>("value", EconomyError.INTERNAL_ERROR, "冲突"));
    }
}
