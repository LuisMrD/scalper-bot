package br.com.tradingbot.strategy.independent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Prevent duplicate versioned migrations from silently reaching Spring startup. */
class FlywayMigrationVersionTest {
    private static final Pattern FILE = Pattern.compile("^V([0-9]+)__(.+)\\.sql$");

    @Test
    void sourceMigrationVersionsMustBeUniqueAndContainTheResearchSequence() throws Exception {
        Path migrations = Path.of("src/main/resources/db/migration");
        assertTrue(Files.isDirectory(migrations), "Missing Flyway source migrations");
        Map<Integer,String> versions = new HashMap<>();
        try (var paths = Files.list(migrations)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                String name = path.getFileName().toString();
                var match = FILE.matcher(name);
                if (!match.matches()) return;
                int version = Integer.parseInt(match.group(1));
                assertNull(versions.putIfAbsent(version,name),
                        () -> "Duplicate Flyway version V" + version
                                + ": " + versions.get(version) + " and " + name);
            });
        }
        assertEquals("V2__add_futures_kline_metrics.sql",versions.get(2));
        assertEquals("V3__create_independent_setup_research.sql",versions.get(3));
        assertEquals("V4__allow_daily_independent_research_runs.sql",versions.get(4));
        assertEquals("V5__allow_15m_independent_research_runs.sql",versions.get(5));

    @Test
    void fifteenMinutePaperResearchConstraintIsIncludedInTheLatestFlywayMigration()
            throws Exception {
        String sql=Files.readString(Path.of("src/main/resources/db/migration/"
                + "V5__allow_15m_independent_research_runs.sql"));
        assertTrue(sql.contains("DROP CHECK ck_independent_research_timeframe"));
        assertTrue(sql.contains("ADD CONSTRAINT ck_independent_research_timeframe"));
        assertTrue(Pattern.compile(
                "timeframe\\s+IN\\s*\\(\\s*'5m'\\s*,\\s*'15m'\\s*,\\s*'1d'\\s*\\)",
                Pattern.CASE_INSENSITIVE).matcher(sql).find(),
                "Must preserve 5m and 1d, and explicitly allow 15m paper runs");
        // Earlier Flyway migration versions/checksums cannot be overwritten
        // when users already have V3/V4 applied to their local MySQL.
    }

}