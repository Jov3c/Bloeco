package com.blocke.centraleconomy.storage.sqlite;

/** Signals that a legacy database was preserved because migration could not be proven safe. */
public final class MigrationException extends RuntimeException {
    public MigrationException(String message) { super(message); }
    public MigrationException(String message, Throwable cause) { super(message, cause); }
}
