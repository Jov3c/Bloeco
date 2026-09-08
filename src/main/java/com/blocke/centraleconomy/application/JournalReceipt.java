package com.blocke.centraleconomy.application;

import java.util.UUID;

/** Stable acknowledgement returned after a journal has committed. */
public record JournalReceipt(UUID entryId) {}
