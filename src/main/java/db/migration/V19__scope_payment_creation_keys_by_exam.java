package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Makes payment creation idempotency keys unique within the explicit purchase target exam. */
public class V19__scope_payment_creation_keys_by_exam extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection=context.getConnection();
        String schema=connection.getSchema();
        Map<String,Set<String>> constraints=new LinkedHashMap<>();
        try (var query=connection.prepareStatement("""
            SELECT tc.constraint_name,kcu.column_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON kcu.constraint_catalog=tc.constraint_catalog AND kcu.constraint_schema=tc.constraint_schema
             AND kcu.constraint_name=tc.constraint_name AND kcu.table_name=tc.table_name
            WHERE UPPER(tc.table_schema)=UPPER(?) AND UPPER(tc.table_name)='PAYMENT_REQUESTS'
              AND tc.constraint_type='UNIQUE'
            ORDER BY tc.constraint_name,kcu.ordinal_position
            """)) {
            query.setString(1,schema);
            try (ResultSet rows=query.executeQuery()) {
                while(rows.next()) constraints.computeIfAbsent(rows.getString(1),ignored->new TreeSet<>())
                    .add(rows.getString(2).toLowerCase(Locale.ROOT));
            }
        }
        var matches=constraints.entrySet().stream()
            .filter(entry->entry.getValue().equals(Set.of("user_id","creation_key"))).toList();
        if(matches.size()!=1) throw new IllegalStateException("Expected the legacy payment creation key constraint exactly once");
        String name=matches.getFirst().getKey().replace("\"","\"\"");
        try(Statement statement=connection.createStatement()) {
            statement.execute("ALTER TABLE payment_requests DROP CONSTRAINT \""+name+"\"");
            statement.execute("ALTER TABLE payment_requests ADD CONSTRAINT payment_requests_user_exam_creation_key UNIQUE(user_id,target_exam_type_id,creation_key)");
        }
    }
}
