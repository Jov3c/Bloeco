package com.blocke.centraleconomy.storage.sqlite;

import java.nio.file.Path;

/** Evidence produced by a legacy-ledger migration attempt. */
public record MigrationReport(boolean migrated, Path backupPath, int migratedEntries) {}
