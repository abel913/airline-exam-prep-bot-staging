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
    void migratesFreshSchemaAndV16UpgradeThroughPhase5() throws Exception {
        String url = "jdbc:h2:mem:practice-v16-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        assertUpgrade(url, "sa");
        String freshUrl = "jdbc:h2:mem:practice-fresh-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        Flyway fresh = Flyway.configure().dataSource(freshUrl, "sa", "").load();
        assertThat(fresh.migrate().targetSchemaVersion).isEqualTo("19");
        try (var connection = DriverManager.getConnection(freshUrl, "sa", "")) {
            assertReceiptSchema(connection);
            assertPhase5Schema(connection);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PG_MIGRATION_TEST_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:55433/[A-Za-z0-9_]+")
    void upgradesDisposableLocalPostgresV16ThroughPhase5() throws Exception {
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

        Flyway v17 = Flyway.configure().dataSource(url, username, "")
                .target(MigrationVersion.fromVersion("17")).load();
        assertThat(v17.migrate().targetSchemaVersion).isEqualTo("17");
        try (var connection = DriverManager.getConnection(url, username, "")) {
            assertReceiptSchema(connection);
        }
        Flyway latest = Flyway.configure().dataSource(url, username, "").load();
        assertThat(latest.migrate().targetSchemaVersion).isEqualTo("19");
        latest.validate();
        try (var connection = DriverManager.getConnection(url, username, "")) {
            assertReceiptSchema(connection);
            assertPhase5Schema(connection);
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

    private void assertPhase5Schema(Connection connection) throws Exception {
        var metadata=connection.getMetaData();
        boolean postgres=metadata.getDatabaseProductName().equalsIgnoreCase("PostgreSQL");
        String schema=postgres?"public":"PUBLIC";
        for (var item : java.util.List.of(new String[]{"bot_users","phone_e164"},
                new String[]{"bot_users","phone_verification_status"},
                new String[]{"access_entitlements","exam_type_id"},
                new String[]{"payment_requests","target_exam_type_id"},
                new String[]{"lifetime_access_grants","exam_type_id"},
                new String[]{"practice_usage","exam_type_id"},
                new String[]{"practice_deliveries","exam_type_id"})) {
            try (var columns=metadata.getColumns(null,schema,postgres?item[0]:item[0].toUpperCase(Locale.ROOT),
                    postgres?item[1]:item[1].toUpperCase(Locale.ROOT))) {
                assertThat(columns.next()).as(item[0]+"."+item[1]).isTrue();
            }
        }
        var uniqueIndexes=new java.util.LinkedHashMap<String,java.util.List<String>>();
        try(var indexes=metadata.getIndexInfo(null,schema,postgres?"mock_attempts":"MOCK_ATTEMPTS",true,false)) {
            while(indexes.next()) {
                String name=indexes.getString("INDEX_NAME"),column=indexes.getString("COLUMN_NAME");
                if(name!=null&&column!=null) uniqueIndexes.computeIfAbsent(name,ignored->new java.util.ArrayList<>()).add(column.toUpperCase(Locale.ROOT));
            }
        }
        assertThat(uniqueIndexes.values()).contains(java.util.List.of("ACTIVE_USER_ID"));
    }
}
