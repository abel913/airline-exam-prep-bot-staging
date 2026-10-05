package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Adds text payment proof while retaining historical receipt-backed reviews. */
public class V20__text_payment_proof extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String schema = connection.getSchema();
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE payment_requests ADD COLUMN payment_proof_text TEXT");
        }

        List<CheckConstraint> checks = readChecks(connection, schema);
        List<CheckConstraint> legacyReference = checks.stream()
            .filter(check -> check.definition().contains("normalized_reference")
                && check.definition().contains("awaiting_receipt")
                && check.definition().contains("pending_review")
                && check.definition().contains("approved")
                && check.definition().contains("rejected"))
            .toList();
        List<CheckConstraint> legacyReceipt = checks.stream()
            .filter(check -> check.definition().contains("receipt_file_id")
                && check.definition().contains("receipt_unique_id")
                && check.definition().contains("submitted_at")
                && check.definition().contains("pending_review")
                && check.definition().contains("approved")
                && check.definition().contains("rejected"))
            .toList();
        if (legacyReference.size() != 1 || legacyReceipt.size() != 1
                || legacyReference.getFirst().name().equals(legacyReceipt.getFirst().name())) {
            throw new IllegalStateException("Expected the two legacy payment proof checks exactly once.");
        }

        try (Statement statement = connection.createStatement()) {
            dropConstraint(statement, legacyReference.getFirst().name());
            dropConstraint(statement, legacyReceipt.getFirst().name());
            statement.execute("ALTER TABLE payment_requests ADD CONSTRAINT payment_requests_reference_proof_check "
                + "CHECK (status NOT IN ('AWAITING_RECEIPT','PENDING_REVIEW','APPROVED','REJECTED') "
                + "OR normalized_reference IS NOT NULL OR "
                + "(status IN ('PENDING_REVIEW','APPROVED','REJECTED') AND payment_proof_text IS NOT NULL "
                + "AND trim(payment_proof_text) <> ''))");
            statement.execute("ALTER TABLE payment_requests ADD CONSTRAINT payment_requests_review_proof_check "
                + "CHECK (status NOT IN ('PENDING_REVIEW','APPROVED','REJECTED') OR "
                + "(submitted_at IS NOT NULL AND ((payment_proof_text IS NOT NULL AND trim(payment_proof_text) <> '' "
                + "AND receipt_file_id IS NULL AND receipt_unique_id IS NULL) OR "
                + "(payment_proof_text IS NULL AND receipt_file_id IS NOT NULL AND receipt_unique_id IS NOT NULL))))");
        }
    }

    private static List<CheckConstraint> readChecks(Connection connection, String schema) throws Exception {
        String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        String sql;
        if (product.contains("postgresql")) {
            sql = "SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conrelid = '" + schema.replace("'", "''")
                + ".payment_requests'::regclass AND contype = 'c'";
        } else if (product.contains("h2")) {
            sql = "SELECT tc.constraint_name, cc.check_clause FROM information_schema.table_constraints tc "
                + "JOIN information_schema.check_constraints cc ON cc.constraint_catalog=tc.constraint_catalog "
                + "AND cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name "
                + "WHERE UPPER(tc.table_schema)=UPPER('" + schema.replace("'", "''")
                + "') AND UPPER(tc.table_name)='PAYMENT_REQUESTS' AND tc.constraint_type='CHECK'";
        } else {
            throw new IllegalStateException("V20 payment proof migration does not support this database engine.");
        }
        List<CheckConstraint> result = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.add(new CheckConstraint(rows.getString(1), rows.getString(2).toLowerCase(Locale.ROOT)));
            }
        }
        return result;
    }

    private static void dropConstraint(Statement statement, String name) throws Exception {
        String quoted = name.replace("\"", "\"\"");
        statement.execute("ALTER TABLE payment_requests DROP CONSTRAINT \"" + quoted + "\"");
    }

    private record CheckConstraint(String name, String definition) {}
}
