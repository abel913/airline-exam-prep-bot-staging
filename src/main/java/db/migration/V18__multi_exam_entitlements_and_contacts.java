package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Adds exam-scoped access and safe contact metadata while mapping legacy data to its sole selected exam. */
public class V18__multi_exam_entitlements_and_contacts extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection c=context.getConnection();
        try (Statement s=c.createStatement()) {
            s.execute("ALTER TABLE bot_users ADD COLUMN phone_e164 VARCHAR(16)");
            s.execute("ALTER TABLE bot_users ADD COLUMN phone_verification_status VARCHAR(32)");
            s.execute("UPDATE bot_users SET phone_verification_status='NOT_CAPTURED' WHERE registration_status='COMPLETED'");
            s.execute("ALTER TABLE bot_users ADD CONSTRAINT bot_users_phone_e164_format_check CHECK(phone_e164 IS NULL OR (LEFT(phone_e164,1)='+' AND LENGTH(phone_e164) BETWEEN 9 AND 16))");
            s.execute("ALTER TABLE bot_users ADD CONSTRAINT bot_users_phone_verification_check CHECK((phone_e164 IS NULL AND phone_verification_status IN ('NOT_CAPTURED')) OR (phone_e164 IS NOT NULL AND phone_verification_status IN ('UNVERIFIED_TYPED','VERIFIED_TELEGRAM_CONTACT')))");

            dropUnique(c,"access_entitlements",Set.of("user_id"));
            dropUnique(c,"access_entitlements",Set.of("phone_identity_hash"));
            s.execute("ALTER TABLE access_entitlements ADD COLUMN exam_type_id BIGINT");
            s.execute("UPDATE access_entitlements a SET exam_type_id=(SELECT u.selected_exam_type_id FROM bot_users u WHERE u.id=a.user_id)");
            requireNoNull(c,"access_entitlements","exam_type_id","Existing entitlement rows could not be mapped to an exam.");
            s.execute("ALTER TABLE access_entitlements ALTER COLUMN exam_type_id SET NOT NULL");
            s.execute("ALTER TABLE access_entitlements ADD CONSTRAINT access_entitlements_exam_type_id_fkey FOREIGN KEY(exam_type_id) REFERENCES exam_types(id)");
            s.execute("ALTER TABLE access_entitlements ADD CONSTRAINT access_entitlements_user_exam_key UNIQUE(user_id,exam_type_id)");
            s.execute("ALTER TABLE access_entitlements ADD CONSTRAINT access_entitlements_user_exam_phone_key UNIQUE(user_id,exam_type_id,phone_identity_hash)");

            s.execute("ALTER TABLE payment_requests ADD COLUMN target_exam_type_id BIGINT");
            s.execute("UPDATE payment_requests p SET target_exam_type_id=(SELECT u.selected_exam_type_id FROM bot_users u WHERE u.id=p.user_id)");
            requireNoNull(c,"payment_requests","target_exam_type_id","Existing payment requests could not be mapped to an exam.");
            s.execute("ALTER TABLE payment_requests ALTER COLUMN target_exam_type_id SET NOT NULL");
            s.execute("ALTER TABLE payment_requests ADD CONSTRAINT payment_requests_target_exam_fkey FOREIGN KEY(target_exam_type_id) REFERENCES exam_types(id)");
            s.execute("ALTER TABLE payment_requests ADD CONSTRAINT payment_requests_id_user_exam_key UNIQUE(id,user_id,target_exam_type_id)");
            dropUnique(c,"payment_requests",Set.of("open_user_id"));
            s.execute("ALTER TABLE payment_requests ADD CONSTRAINT payment_requests_open_user_exam_key UNIQUE(open_user_id,target_exam_type_id)");

            dropUnique(c,"lifetime_access_grants",Set.of("user_id"));
            s.execute("ALTER TABLE lifetime_access_grants ADD COLUMN exam_type_id BIGINT");
            s.execute("UPDATE lifetime_access_grants g SET exam_type_id=(SELECT p.target_exam_type_id FROM payment_requests p WHERE p.id=g.payment_request_id AND p.user_id=g.user_id)");
            requireNoNull(c,"lifetime_access_grants","exam_type_id","Existing lifetime grants could not be mapped to an exam.");
            s.execute("ALTER TABLE lifetime_access_grants ALTER COLUMN exam_type_id SET NOT NULL");
            s.execute("ALTER TABLE lifetime_access_grants ADD CONSTRAINT lifetime_access_grants_user_exam_key UNIQUE(user_id,exam_type_id)");
            s.execute("ALTER TABLE lifetime_access_grants ADD CONSTRAINT lifetime_access_grants_payment_exam_fkey FOREIGN KEY(payment_request_id,user_id,exam_type_id) REFERENCES payment_requests(id,user_id,target_exam_type_id)");

            s.execute("ALTER TABLE practice_deliveries ADD COLUMN exam_type_id BIGINT");
            s.execute("UPDATE practice_deliveries d SET exam_type_id=(SELECT v.exam_type_id FROM question_versions v WHERE v.id=d.version_id)");
            requireNoNull(c,"practice_deliveries","exam_type_id","Existing practice deliveries could not be mapped to an exam.");
            s.execute("ALTER TABLE practice_deliveries ALTER COLUMN exam_type_id SET NOT NULL");
            s.execute("ALTER TABLE practice_deliveries ADD CONSTRAINT practice_deliveries_user_exam_fkey FOREIGN KEY(user_id,exam_type_id) REFERENCES access_entitlements(user_id,exam_type_id)");

            s.execute("ALTER TABLE practice_usage ADD COLUMN exam_type_id BIGINT");
            s.execute("UPDATE practice_usage u SET exam_type_id=(SELECT d.exam_type_id FROM practice_deliveries d WHERE d.id=u.first_delivery_id)");
            requireNoNull(c,"practice_usage","exam_type_id","Existing practice usage could not be mapped to an exam.");
            dropPrimaryKey(c,"practice_usage");
            s.execute("ALTER TABLE practice_usage ALTER COLUMN exam_type_id SET NOT NULL");
            s.execute("ALTER TABLE practice_usage ADD CONSTRAINT practice_usage_exam_fkey FOREIGN KEY(user_id,exam_type_id) REFERENCES access_entitlements(user_id,exam_type_id)");
            s.execute("ALTER TABLE practice_usage ADD CONSTRAINT practice_usage_pkey PRIMARY KEY(user_id,exam_type_id,question_id)");

            s.execute("ALTER TABLE practice_sessions ADD COLUMN exam_type_id BIGINT");
            s.execute("UPDATE practice_sessions p SET exam_type_id=(SELECT d.exam_type_id FROM practice_deliveries d WHERE d.id=p.current_delivery_id)");
            requireNoNull(c,"practice_sessions","exam_type_id","Existing practice sessions could not be mapped to an exam.");
            s.execute("ALTER TABLE practice_sessions ALTER COLUMN exam_type_id SET NOT NULL");
            s.execute("ALTER TABLE practice_sessions ADD CONSTRAINT practice_sessions_exam_fkey FOREIGN KEY(user_id,exam_type_id) REFERENCES access_entitlements(user_id,exam_type_id)");

            // Keep the existing one-active-mock-per-user guard. Exam allowances
            // are independent; a user finishes or resumes the active mock before switching.
            s.execute("CREATE INDEX payment_exam_history_idx ON payment_requests(user_id,target_exam_type_id,id)");
        }
    }

    private static void requireNoNull(Connection c,String table,String column,String message) throws Exception {
        try (Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+table+" WHERE "+column+" IS NULL")) {
            if (!r.next() || r.getLong(1)!=0) throw new IllegalStateException(message);
        }
    }

    private static void dropUnique(Connection c,String table,Set<String> targetColumns) throws Exception {
        dropConstraintWithColumns(c,table,"UNIQUE",targetColumns);
        if (hasConstraintColumns(c,table,"UNIQUE",targetColumns))
            throw new IllegalStateException("Unique constraint removal did not take effect for " + table + " columns " + targetColumns);
    }

    private static void dropPrimaryKey(Connection c,String table) throws Exception {
        dropConstraintWithColumns(c,table,"PRIMARY KEY",null);
    }

    private static void dropConstraintWithColumns(Connection c,String table,String type,Set<String> targetColumns) throws Exception {
        String schema=c.getSchema();
        Map<String,Set<String>> constraints=new LinkedHashMap<>();
        try (var q=c.prepareStatement("""
            SELECT tc.constraint_name,kcu.column_name,kcu.ordinal_position
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON kcu.constraint_catalog=tc.constraint_catalog AND kcu.constraint_schema=tc.constraint_schema
             AND kcu.constraint_name=tc.constraint_name AND kcu.table_name=tc.table_name
            WHERE UPPER(tc.table_schema)=UPPER(?) AND UPPER(tc.table_name)=UPPER(?) AND tc.constraint_type=?
            ORDER BY tc.constraint_name,kcu.ordinal_position
            """)) {
            q.setString(1,schema);q.setString(2,table);q.setString(3,type);
            try (ResultSet r=q.executeQuery()) {
                while(r.next()) constraints.computeIfAbsent(r.getString(1),ignored->new TreeSet<>())
                    .add(r.getString(2).toLowerCase(Locale.ROOT));
            }
        }
        List<String> matches=new ArrayList<>();
        for(var entry:constraints.entrySet()) if(targetColumns==null||entry.getValue().equals(targetColumns)) matches.add(entry.getKey());
        if(matches.size()!=1) throw new IllegalStateException("Expected one "+type+" constraint for "+table+" columns "+targetColumns+"; found "+matches.size());
        String name=matches.getFirst().replace("\"","\"\"");
        try(Statement s=c.createStatement()) {s.execute("ALTER TABLE "+table+" DROP CONSTRAINT \""+name+"\"");}
    }

    private static boolean hasConstraintColumns(Connection c,String table,String type,Set<String> targetColumns) throws Exception {
        try (var q=c.prepareStatement("""
            SELECT tc.constraint_name,kcu.column_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON kcu.constraint_catalog=tc.constraint_catalog AND kcu.constraint_schema=tc.constraint_schema
             AND kcu.constraint_name=tc.constraint_name AND kcu.table_name=tc.table_name
            WHERE UPPER(tc.table_schema)=UPPER(?) AND UPPER(tc.table_name)=UPPER(?) AND tc.constraint_type=?
            """)) {
            q.setString(1,c.getSchema());q.setString(2,table);q.setString(3,type);
            Map<String,Set<String>> found=new LinkedHashMap<>();
            try (ResultSet r=q.executeQuery()) {
                while(r.next()) found.computeIfAbsent(r.getString(1),ignored->new TreeSet<>()).add(r.getString(2).toLowerCase(Locale.ROOT));
            }
            return found.values().stream().anyMatch(columns->columns.equals(targetColumns));
        }
    }
}
