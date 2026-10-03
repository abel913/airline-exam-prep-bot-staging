package com.airlineprep.bot.engine;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PracticeDeliveryMigrationTests {
    @Test
    void migratesFreshSchemaAndV16UpgradeWithRetryReceipts() throws Exception {
        String url = "jdbc:h2:mem:practice-v16-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        assertUpgrade(url, "sa");
        String freshUrl = "jdbc:h2:mem:practice-fresh-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        Flyway fresh = Flyway.configure().dataSource(freshUrl, "sa", "").load();
        assertThat(fresh.migrate().targetSchemaVersion).isEqualTo("17");
        try (var connection = DriverManager.getConnection(freshUrl, "sa", "")) {
            assertReceiptSchema(connection);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PG_MIGRATION_TEST_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:55433/[A-Za-z0-9_]+")
    void upgradesDisposableLocalPostgresV16ToV17() throws Exception {
        assertUpgrade(System.getenv("PG_MIGRATION_TEST_URL"), "postgres");
    }

    private void assertUpgrade(String url, String username) throws Exception {
        Flyway v16 = Flyway.configure().dataSource(url, username, "").target(MigrationVersion.fromVersion("16")).load();
        assertThat(v16.migrate().targetSchemaVersion).isEqualTo("16");
        try (var connection = DriverManager.getConnection(url, username, "")) {
            boolean postgres = connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL");
            try (var tables = connection.getMetaData().getTables(null, postgres ? "public" : "PUBLIC",
                    postgres ? "practice_update_receipts" : "PRACTICE_UPDATE_RECEIPTS", null)) {
                assertThat(tables.next()).isFalse();
            }
        }

        Flyway latest = Flyway.configure().dataSource(url, username, "").load();
        assertThat(latest.migrate().targetSchemaVersion).isEqualTo("17");
        latest.validate();
        try (var connection = DriverManager.getConnection(url, username, "")) {
            assertReceiptSchema(connection);
        }
    }

    private void assertReceiptSchema(Connection connection) throws Exception {
        var metadata = connection.getMetaData();
        boolean postgres = metadata.getDatabaseProductName().equalsIgnoreCase("PostgreSQL");
        String schema = postgres ? "public" : "PUBLIC";
        String table = postgres ? "practice_update_receipts" : "PRACTICE_UPDATE_RECEIPTS";
        var primaryKey = new ArrayList<String>();
        try (var keys = metadata.getPrimaryKeys(null, schema, table)) {
            while (keys.next()) primaryKey.add(keys.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
        }
        assertThat(primaryKey).containsExactly("UPDATE_ID");
        var receiptReferences = new ArrayList<String>();
        try (var references = metadata.getImportedKeys(null, schema, table)) {
            while (references.next()) {
                assertThat(references.getString("PKTABLE_NAME")).isEqualToIgnoringCase("practice_deliveries");
                receiptReferences.add(references.getString("FKCOLUMN_NAME").toUpperCase(Locale.ROOT)
                        + ":" + references.getString("PKCOLUMN_NAME").toUpperCase(Locale.ROOT));
            }
        }
        assertThat(receiptReferences).containsExactlyInAnyOrder("DELIVERY_ID:ID", "USER_ID:USER_ID", "QUESTION_ID:QUESTION_ID");
        var indexes = new ArrayList<String>();
        try (var rows = metadata.getIndexInfo(null, schema, table, false, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                if (name != null) indexes.add(name.toUpperCase(Locale.ROOT));
            }
        }
        assertThat(indexes).contains("PRACTICE_UPDATE_RECEIPTS_DELIVERY_IDX");
        if (postgres) {
            try (var statement = connection.createStatement();
                 var security = statement.executeQuery("SELECT relrowsecurity FROM pg_class WHERE oid='practice_update_receipts'::regclass")) {
                assertThat(security.next()).isTrue();
                assertThat(security.getBoolean(1)).isTrue();
            }
        }
    }
}
