package org.example.stockwatch247.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * One-shot production migration entry point. It intentionally does not start
 * Spring, JPA, the web server, schedulers, or the normal application process.
 */
public final class DatabaseMigrationMain {
    private DatabaseMigrationMain() {
    }

    public static void main(String[] args) {
        String url = requiredEnvironment("DB_URL");
        if (!url.startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException("DB_URL must use jdbc:postgresql:// for the migration job.");
        }
        String username = requiredEnvironment("DB_MIGRATION_USERNAME");
        String password = requiredEnvironment("DB_MIGRATION_PASSWORD");
        boolean baselineOnMigrate = Boolean.parseBoolean(
                System.getenv().getOrDefault("FLYWAY_BASELINE_ON_MIGRATE", "false"));

        Flyway flyway = Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(baselineOnMigrate)
                .load();
        MigrateResult result = flyway.migrate();
        if (!result.success) {
            throw new IllegalStateException("Database migration did not complete successfully.");
        }
        System.out.println("Database migration completed; migrations executed: " + result.migrationsExecuted);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the migration job.");
        }
        return value;
    }
}
