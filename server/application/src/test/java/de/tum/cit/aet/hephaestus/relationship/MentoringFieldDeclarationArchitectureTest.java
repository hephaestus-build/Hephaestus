package de.tum.cit.aet.hephaestus.relationship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class MentoringFieldDeclarationArchitectureTest {

    private static final Pattern KEY = Pattern.compile("`([^`]+)`");

    @Test
    void shouldOnlyDeclareFieldsFromThePersonalDataMap() throws IOException {
        checkDeclarations(declaredFields(), mappedFields());
    }

    @Test
    void shouldRejectAnAccountIdDeclaredAsACorrelationHandle() throws IOException {
        var fields = new HashMap<>(declaredFields());
        fields.put("account.id", MentoringFieldClass.CORRELATION_HANDLE);
        var allowlist = mappedFields();
        assertThatThrownBy(() -> checkDeclarations(fields, allowlist))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("account.id");
    }

    @Test
    void shouldNameEveryEventForTheRetentionSelector() {
        assertThat(MentoringLogEvent.values())
                .allSatisfy(event -> assertThat(event.eventName()).startsWith("mentoring."));
    }

    @Test
    void shouldReadCorrelationFromTheSharedLogContext() {
        assertThat(MentoringLogKey.TRACE_ID.key()).isEqualTo(StructuredLogKeys.TRACE_ID);
        assertThat(MentoringLogKey.SPAN_ID.key()).isEqualTo(StructuredLogKeys.SPAN_ID);
        assertThat(MentoringLogKey.WORKSPACE_ID.key()).isEqualTo(StructuredLogKeys.WORKSPACE_ID);
        assertThat(MentoringLogKey.RUN_ID.key()).isEqualTo(StructuredLogKeys.RUN_ID);
        assertThat(MentoringLogKey.RUN_EPOCH.key()).isEqualTo(StructuredLogKeys.RUN_EPOCH);
    }

    private static Map<String, MentoringFieldClass> declaredFields() {
        return Arrays.stream(MentoringLogKey.values())
                .collect(Collectors.toMap(MentoringLogKey::key, MentoringLogKey::fieldClass));
    }

    private static void checkDeclarations(
            Map<String, MentoringFieldClass> fields, Map<String, MentoringFieldClass> mapped) {
        assertThat(fields).containsExactlyInAnyOrderEntriesOf(mapped);
    }

    private static Map<String, MentoringFieldClass> mappedFields() throws IOException {
        var rows = Files.readString(Path.of("../../docs/admin/dsms/personal-data-map.mdx"))
                .lines()
                .dropWhile(line -> !line.equals("## Mentoring telemetry"))
                .skip(1)
                .takeWhile(line -> !line.startsWith("## "))
                .toList();
        var mapped = new HashMap<String, MentoringFieldClass>();
        for (var fieldClass : MentoringFieldClass.values()) {
            var prefix = "| " + fieldClass.name().toLowerCase(Locale.ROOT).replace('_', ' ') + " |";
            var row = rows.stream()
                    .filter(line -> line.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .findFirst()
                    .orElseThrow();
            KEY.matcher(row.split("[|]", -1)[2]).results().forEach(key -> mapped.put(key.group(1), fieldClass));
        }
        return mapped;
    }
}
