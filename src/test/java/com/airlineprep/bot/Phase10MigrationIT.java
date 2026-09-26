package com.airlineprep.bot;
import java.util.*;
import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class Phase10MigrationIT {
 @Test void upgradeFromThirteenPreservesExistingDataAndDefaults() throws Exception {
  String host=System.getenv("DB_HOST"),db=System.getenv("DB_NAME");
  assertThat(host).isIn("127.0.0.1","localhost");assertThat(db).startsWith("airline_phase9_phase10");
  String url="jdbc:postgresql://"+host+":"+System.getenv("DB_PORT")+"/"+db+"_upgrade?sslmode=require";
  String user=System.getenv("DB_USERNAME"),password=System.getenv("DB_PASSWORD");
  var old=Flyway.configure().dataSource(url,user,password).cleanDisabled(true).target("13").load();old.migrate();
  try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()) {s.executeUpdate("UPDATE app_settings SET free_practice_limit=77 WHERE id=1");}
  var latest=Flyway.configure().dataSource(url,user,password).cleanDisabled(true).load();latest.migrate();latest.validate();
  try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement();var r=s.executeQuery("SELECT free_practice_limit,maintenance_enabled FROM app_settings WHERE id=1")) {assertThat(r.next()).isTrue();assertThat(r.getInt(1)).isEqualTo(77);assertThat(r.getBoolean(2)).isFalse();}
  assertThat(latest.info().applied()).hasSize(14);
 }
}
