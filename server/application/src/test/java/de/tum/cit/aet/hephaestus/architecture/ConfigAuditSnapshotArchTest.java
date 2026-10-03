package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Structural guard on config-audit snapshots. {@code config_audit_event} is append-only, so a secret
 * written into a snapshot cannot be edited out afterwards — the guard has to stop it at build time
 * rather than at review time.
 *
 * <p>This is what makes the next snapshot safe, not just today's: {@code LlmConnection.apiKey} is
 * encrypted at rest but its getter returns plaintext, so the obvious wrong implementation — snapshot
 * the field — is one line away and turns the build red here.
 */
class ConfigAuditSnapshotArchTest extends HephaestusArchitectureTest {

    private static final Pattern SECRET_LIKE = Pattern.compile("key|token|secret|password|credential|email");

    /**
     * Names that read as secret-like but carry no secret. Each entry is a deliberate exemption:
     * a presence flag or a non-sensitive enum, never the material itself.
     */
    private static final Set<String> ALLOWED = Set.of(
            // boolean: whether a key exists, never the key
            "llmApiKeySet",
            // enum: PROXY | API_KEY — how credentials are supplied, not what they are
            "credentialMode",
            // boolean: whether a workspace SCM token is present, never the token itself
            "tokenSet",
            // Integer: the model's max *output tokens* capability (an LLM sizing parameter), not a
            // credential — "token" here means the language-model unit, unrelated to auth tokens
            "maxOutputTokens");

    @Test
    void snapshotsAreRecords() {
        classes()
                .that()
                .implement(ConfigAuditSnapshot.class)
                .should()
                .beRecords()
                .because("Jackson serializes record components in declaration order; a Map's iteration order is "
                        + "not contractual, and the change diff and no-op suppression both depend on determinism")
                .check(classes);
    }

    @Test
    void snapshotsExposeNoSecretLikeComponent() {
        secretLikeComponentRule().check(classes);
    }

    /** Exposed so {@code ConfigAuditSnapshotSecretDetectionTest} can run it against fixtures. */
    static ArchRule secretLikeComponentRule() {
        return classes()
                .that()
                .implement(ConfigAuditSnapshot.class)
                .should(haveNoSecretLikeField())
                .because("config_audit_event is append-only: a leaked credential or address cannot be edited out. "
                        + "Snapshot a presence flag (e.g. llmApiKeySet) instead, or add a justified exemption");
    }

    private static ArchCondition<JavaClass> haveNoSecretLikeField() {
        return new ArchCondition<>("expose no secret-like component") {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                checkRecursively(clazz, clazz, "", new HashSet<>(), events);
            }
        };
    }

    /**
     * Walks nested record components too. A snapshot is serialized whole, so a secret one level down
     * lands in the row exactly as a top-level one would — and a check that only looked at the outer
     * record would pass it. Cycles are possible in principle, hence the visited set.
     */
    private static void checkRecursively(
            JavaClass root, JavaClass clazz, String path, Set<String> visited, ConditionEvents events) {
        if (!visited.add(clazz.getName())) {
            return;
        }
        for (JavaField field : clazz.getFields()) {
            if (field.getModifiers().contains(JavaModifier.STATIC) || ALLOWED.contains(field.getName())) {
                continue;
            }
            String fieldPath = path + "." + field.getName();
            if (SECRET_LIKE.matcher(field.getName().toLowerCase(Locale.ROOT)).find()) {
                events.add(SimpleConditionEvent.violated(
                        root, root.getName() + fieldPath + " looks like secret or contact material"));
            }
            JavaClass type = field.getRawType();
            if (type.isRecord()) {
                checkRecursively(root, type, fieldPath, visited, events);
            }
        }
    }
}
