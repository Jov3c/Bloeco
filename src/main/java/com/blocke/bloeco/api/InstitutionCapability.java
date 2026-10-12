package com.blocke.bloeco.api;

/** Explicit operations granted to one registered institution. */
public enum InstitutionCapability {
    CHARGE_PLAYER,
    PAY_PLAYER,
    REFUND,
    RESERVE_PLAYER_FUNDS,
    READ_OWN_BALANCE,
    READ_OWN_JOURNAL
}
