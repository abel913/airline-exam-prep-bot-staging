package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V16__Increase_import_batch_row_limit extends BaseJavaMigration {
 @Override
 public void migrate(Context context) throws Exception {
  Connection connection=context.getConnection();
  String product=connection.getMetaData().getDatabaseProductName();
  String constraint;
  if(product.toLowerCase().contains("postgresql")) {
   try(Statement statement=connection.createStatement();ResultSet result=statement.executeQuery(
     "SELECT conname FROM pg_constraint WHERE conrelid=to_regclass('question_import_batches') AND contype='c' AND pg_get_constraintdef(oid) ILIKE '%total_rows%500%'")) {
    if(!result.next()) throw new IllegalStateException("The existing import row limit constraint was not found.");
    constraint=result.getString(1);
   }
  } else if(product.equalsIgnoreCase("H2")) {
   try(Statement statement=connection.createStatement();ResultSet result=statement.executeQuery(
     "SELECT tc.constraint_name FROM information_schema.table_constraints tc JOIN information_schema.check_constraints cc ON cc.constraint_catalog=tc.constraint_catalog AND cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name WHERE tc.table_schema=CURRENT_SCHEMA AND tc.table_name='QUESTION_IMPORT_BATCHES' AND tc.constraint_type='CHECK' AND cc.check_clause LIKE '%TOTAL_ROWS%500%'")) {
    if(!result.next()) throw new IllegalStateException("The existing import row limit constraint was not found.");
    constraint=result.getString(1);
   }
  } else throw new IllegalStateException("Unsupported database for import batch row-limit migration: "+product);
  try(Statement statement=connection.createStatement()) {
   statement.execute("ALTER TABLE question_import_batches DROP CONSTRAINT \""+constraint.replace("\"","\"\"")+"\"");
   statement.execute("ALTER TABLE question_import_batches ADD CONSTRAINT question_import_batch_row_limit CHECK(total_rows BETWEEN 0 AND 5000)");
  }
 }
}
