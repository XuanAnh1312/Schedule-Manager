package org.dacnpm.schedulemanager.schedule.shift;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Test dùng H2 và tắt Flyway nên không phát hiện được cột thiếu trong migration. Test này đọc thẳng các file
 * migration và bảo đảm mọi cột shifts mà ShiftRepository dùng đều được tạo ra.
 */
class ShiftSchemaMigrationTest {

    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");
    private static final Pattern CREATE_SHIFTS = Pattern.compile(
            "CREATE\\s+TABLE\\s+shifts\\s*\\((.*?)\\n\\);", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ALTER_ADD = Pattern.compile(
            "ALTER\\s+TABLE\\s+shifts\\s+ADD\\s+(?!CONSTRAINT\\b)(\\w+)\\s", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLUMN_LINE = Pattern.compile("^\\s*(\\w+)\\s+\\w+", Pattern.MULTILINE);

    @Test
    void everyShiftColumnUsedByTheRepository_isCreatedByAMigration() throws IOException {
        Set<String> created = shiftColumnsFromMigrations();

        assertThat(created).containsAll(ShiftRepository.SHIFT_COLUMNS);
    }

    private static Set<String> shiftColumnsFromMigrations() throws IOException {
        Set<String> columns = new HashSet<>();
        List<Path> files;
        try (Stream<Path> stream = Files.list(MIGRATIONS)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
        }
        for (Path file : files) {
            String sql = Files.readString(file, StandardCharsets.UTF_8);
            Matcher create = CREATE_SHIFTS.matcher(sql);
            if (create.find()) {
                Matcher line = COLUMN_LINE.matcher(create.group(1));
                while (line.find()) {
                    String name = line.group(1).toLowerCase(Locale.ROOT);
                    if (!name.equals("constraint")) {
                        columns.add(name);
                    }
                }
            }
            Matcher add = ALTER_ADD.matcher(sql);
            while (add.find()) {
                columns.add(add.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }
}
