package db.migration;

import java.sql.Connection;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** A receipt for every delivery response, including updates that resume an existing delivery. */
public class V17__Practice_delivery_update_idempotency extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE practice_update_receipts (
                    update_id BIGINT PRIMARY KEY CHECK (update_id >= 0),
                    user_id BIGINT NOT NULL,
                    question_id BIGINT NOT NULL,
                    delivery_id BIGINT NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    FOREIGN KEY (delivery_id,user_id,question_id)
                        REFERENCES practice_deliveries(id,user_id,question_id)
                )
                """);
            statement.execute("CREATE INDEX practice_update_receipts_delivery_idx ON practice_update_receipts(delivery_id,user_id,question_id)");
            if (connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL")) {
                statement.execute("ALTER TABLE practice_update_receipts ENABLE ROW LEVEL SECURITY");
                statement.execute("REVOKE ALL ON TABLE practice_update_receipts FROM PUBLIC");
                // Standalone PostgreSQL test databases do not have Supabase's API roles.
                for (String role : new String[]{"anon", "authenticated"}) {
                    try (var roles = connection.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?")) {
                        roles.setString(1, role);
                        try (var result = roles.executeQuery()) {
                            if (result.next()) statement.execute("REVOKE ALL ON TABLE practice_update_receipts FROM " + role);
                        }
                    }
                }
            }
        }
    }
}
