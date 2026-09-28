package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Pins the integration cutover so a future commit cannot silently re-introduce:
 * <ol>
 *   <li>The legacy {@code gitprovider} package (now {@code integration.scm}).</li>
 *   <li>Per-vendor webhook routes alongside the unified {@code /webhooks/{kind}}.</li>
 *   <li>Denormalised SCM columns on {@code Workspace} that the Connection registry owns.</li>
 * </ol>
 */
class IntegrationCutoverPinsTest extends HephaestusArchitectureTest {

    @Test
    @DisplayName("no class resides in the legacy gitprovider package")
    void noClassResidesInGitProviderPackage() {
        ArchRule rule = noClasses()
                .should()
                .resideInAPackage("..gitprovider..")
                .because("gitprovider/ was renamed to integration.scm/; re-introducing it forks the module graph");
        rule.check(classes);
    }

    /**
     * Forbid retired per-vendor webhook ingress routes.
     *
     * <p>Vendor event payloads use {@code WebhookController @PostMapping("/webhooks/{kind}")};
     * Slack interactivity is the only separate webhook URL. The rule fires when a @{@link PostMapping} value is <em>exactly</em>
     * {@code /github}, {@code /gitlab}, {@code /slack}, or {@code /outline} — the retired
     * top-level ingress paths the unified webhook framework replaced. Nested paths under a different
     * class-level {@code @RequestMapping} (e.g. {@code WorkspaceRegistryController}'s
     * {@code POST /workspaces/gitlab/preflight}) are admin-API surfaces, not webhook
     * ingress, and remain permitted.
     */
    @Test
    @DisplayName("no retired per-vendor webhook PostMapping route")
    void noRetiredVendorWebhookRoutes() {
        Set<String> retiredVendorRouteNames =
                Set.of("/github", "/gitlab", "/slack", "/outline", "/slack/interactivity");

        ArchCondition<JavaMethod> notDeclareRetiredVendorRoute =
                new ArchCondition<>("not declare a retired vendor webhook @PostMapping route") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        if (!method.isAnnotatedWith(PostMapping.class)) {
                            return;
                        }
                        PostMapping mapping = method.getAnnotationOfType(PostMapping.class);
                        String[] candidates = mapping.value().length > 0 ? mapping.value() : mapping.path();
                        for (String value : candidates) {
                            String trimmed = value == null ? "" : value.trim();
                            if (trimmed.isEmpty()) {
                                continue;
                            }
                            if (retiredVendorRouteNames.contains(trimmed)) {
                                events.add(SimpleConditionEvent.violated(
                                        method,
                                        String.format(
                                                "%s declares retired per-vendor webhook route '%s' — only "
                                                        + "WebhookController.@PostMapping(\"/webhooks/{kind}\") is permitted",
                                                method.getFullName(), trimmed)));
                            }
                        }
                    }
                };

        ArchRule rule = methods()
                .that()
                .areAnnotatedWith(PostMapping.class)
                .should(notDeclareRetiredVendorRoute)
                .because("all vendor webhook receivers live behind /webhooks/{kind}; re-adding /github, "
                        + "/gitlab, /slack, or /outline would bypass the unified webhook namespace");
        rule.check(classes);
    }

    /**
     * Forbid legacy denormalised connection columns from re-appearing on JPA entities
     * in {@code ..workspace..}. The Connection registry owns this data; re-declaring
     * them on the entity recreates a dual-source-of-truth bug. DTOs keep these field
     * names on the wire and are out of scope (entity-only filter below).
     */
    @Test
    @DisplayName("Workspace entity does not re-declare legacy Connection-owned fields")
    void noLegacyFieldsOnWorkspace() {
        Set<String> forbiddenFieldNames = Set.of(
                "installationId",
                "personalAccessToken",
                "gitProviderMode",
                "slackToken",
                "slackSigningSecret",
                "installationLinkedAt",
                "gitlabGroupId",
                "gitlabWebhookId",
                "serverUrl");

        ArchCondition<JavaField> notCarryLegacyName =
                new ArchCondition<>("not carry the name of a legacy Connection-owned column") {
                    @Override
                    public void check(JavaField field, ConditionEvents events) {
                        if (forbiddenFieldNames.contains(field.getName())) {
                            events.add(SimpleConditionEvent.violated(
                                    field,
                                    String.format(
                                            "%s re-introduces a legacy Connection-owned field name on a JPA "
                                                    + "entity — the Connection registry now owns this data.",
                                            field.getFullName())));
                        }
                    }
                };

        // Compose entity-AND-workspace-package via DescribedPredicate#and — chaining two
        // `.areDeclaredInClassesThat()` fluents on the ArchRule builder ORs them and would
        // sweep DTOs/context records that legitimately keep these field names on the wire.
        DescribedPredicate<JavaClass> entityInWorkspace = JavaClass.Predicates.resideInAPackage("..workspace..")
                .and(CanBeAnnotated.Predicates.annotatedWith(jakarta.persistence.Entity.class));
        ArchRule rule = fields().that()
                .areDeclaredInClassesThat(entityInWorkspace)
                .should(notCarryLegacyName)
                .because("the Connection registry owns these fields; re-declaring them on a JPA entity "
                        + "in the workspace package would re-create the dual-source-of-truth bug");
        rule.check(classes);
    }
}
