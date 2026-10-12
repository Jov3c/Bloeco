package com.blocke.bloeco.api;

/** Central-policy settlement scenarios; consumers cannot submit arbitrary tax amounts. */
public enum SettlementKind {
    PURCHASE,
    SERVICE_FEE,
    PAYOUT,
    REWARD
}
