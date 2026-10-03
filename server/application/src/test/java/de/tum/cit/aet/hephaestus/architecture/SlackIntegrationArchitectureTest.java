package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Structural invariants of the {@code integration.slack} module that a careless commit would otherwise break
 * silently:
 *
 * <ul>
 *   <li>every REST controller in the module is either workspace-scoped or an {@code @Hidden} inbound webhook
 *       receiver (an explicitly allowlisted user-scoped controller aside) — an unscoped Slack endpoint would
 *       bypass tenancy authorization;</li>
 *   <li>every NATS consumer follows one of the two envelope-handler bases — a hand-rolled handler would skip the
 *       shared deserialization/teamId discipline (and, for the channel path, the transaction boundary);</li>
 *   <li>every Slack repository finder carries the workspace predicate in its signature — the compile-time
 *       complement to the runtime tenancy {@code StatementInspector} (deliberately unscoped fleet-enumeration
 *       queries are individually allowlisted and guarded by {@code @WorkspaceAgnostic} callers); and</li>
 *   <li>a class whose name promises a stereotype ({@code *Service}/{@code *Repository}/{@code *Controller}) is not
 *       lying about it — directional on purpose (see {@link #slackServiceNamesAreServices()}).</li>
 * </ul>
 *
 * <p>The manifest ↔ handler parity check lives with the manifest test
 * ({@code integration.slack.webhook.SlackManifestTemplateTest}) because it reads the YAML template, not classes.
 */
class SlackIntegrationArchitectureTest extends HephaestusArchitectureTest {

    private static final String SLACK = "de.tum.cit.aet.hephaestus.integration.slack..";

    /**
     * User-scoped (not workspace-scoped) controllers, allowed by name: the cross-workspace preferences read is
     * keyed on the authenticated account, not a workspace path.
     */
    private static final Set<String> USER_SCOPED_CONTROLLERS = Set.of("SlackUserPreferencesUserController");

    @Test
    @DisplayName("every Slack REST controller is workspace-scoped or a hidden webhook receiver")
    void slackControllersAreWorkspaceScopedOrHiddenReceivers() {
        classes()
                .that()
                .resideInAPackage(SLACK)
                .and()
                .areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .and(DescribedPredicate.describe(
                        "are not explicitly allowlisted user-scoped controllers",
                        javaClass -> !USER_SCOPED_CONTROLLERS.contains(javaClass.getSimpleName())))
                .should(new ArchCondition<>("be @WorkspaceScopedController or @Hidden") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        boolean workspaceScoped = javaClass.isAnnotatedWith(
                                "de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController");
                        boolean hiddenReceiver = javaClass.isAnnotatedWith("io.swagger.v3.oas.annotations.Hidden");
                        if (!workspaceScoped && !hiddenReceiver) {
                            events.add(SimpleConditionEvent.violated(
                                    javaClass,
                                    javaClass.getName()
                                            + " is a Slack @RestController that is neither @WorkspaceScopedController nor"
                                            + " an @Hidden webhook receiver"));
                        }
                    }
                })
                .check(classes);
    }

    @Test
    @DisplayName("every Slack NATS consumer extends one of the two envelope-handler bases")
    void slackMessageHandlersExtendTheSharedBases() {
        // Two bases by design: AbstractSlackEnvelopeHandler for plain envelope routing, and the core
        // AbstractIntegrationMessageHandler for the channel-message path, which additionally needs the shared
        // TransactionTemplate boundary around its DB-writing ingest.
        classes()
                .that()
                .resideInAPackage("de.tum.cit.aet.hephaestus.integration.slack.webhook..")
                .and()
                .haveSimpleNameEndingWith("MessageHandler")
                .and()
                .doNotHaveModifier(JavaModifier.ABSTRACT)
                .should(new ArchCondition<>("be assignable to a Slack/integration envelope-handler base") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        boolean slackBase = javaClass.isAssignableTo(
                                "de.tum.cit.aet.hephaestus.integration.slack.webhook.AbstractSlackEnvelopeHandler");
                        boolean coreBase = javaClass.isAssignableTo(
                                "de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler");
                        if (!slackBase && !coreBase) {
                            events.add(SimpleConditionEvent.violated(
                                    javaClass,
                                    javaClass.getName() + " is a Slack *MessageHandler outside both handler bases"));
                        }
                    }
                })
                .check(classes);
    }

    /**
     * Deliberately unscoped repository methods: fleet-wide enumeration a scheduler must run before it can scope to
     * a workspace. Their callers carry {@code @WorkspaceAgnostic}; everything else must take a workspace id.
     */
    private static final Set<String> UNSCOPED_ALLOWLIST = Set.of(
            // Instance-admin person scope spans workspaces but pins an exact provider/native key.
            "SlackThreadRepository.findPersonSourceIds",
            "SlackMessageRepository.findDistinctWorkspaceIds",
            "SlackMonitoredChannelRepository.findDistinctWorkspaceIdsByConsentState",
            // The agent-owned ConversationCandidateSource SPI's settled-thread scan is an inherently cross-workspace
            // sweep (each returned candidate carries its own workspace_id); its caller chain
            // (SlackConversationCandidateSource.settledCandidates → agent's ConversationThreadTriggerScheduler) is
            // itself @WorkspaceAgnostic for exactly this reason, mirroring the two entries above.
            "SlackThreadRepository.findSettledCandidateRows");

    @Test
    @DisplayName("Slack repository finders carry the workspace predicate in their signature")
    void slackRepositoryFindersAreWorkspaceScopedAtCompileTime() {
        classes()
                .that()
                .resideInAPackage("de.tum.cit.aet.hephaestus.integration.slack.domain..")
                .and()
                .areInterfaces()
                .and()
                .haveSimpleNameEndingWith("Repository")
                .should(new ArchCondition<>("declare only workspace-scoped finders (or allowlisted enumerators)") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        for (JavaMethod method : javaClass.getMethods()) {
                            if (!method.getOwner().equals(javaClass)) {
                                continue;
                            }
                            String name = method.getName();
                            if (!name.matches("(find|count|delete|exists).*")) {
                                continue;
                            }
                            if (UNSCOPED_ALLOWLIST.contains(javaClass.getSimpleName() + "." + name)) {
                                continue;
                            }
                            boolean scoped = nameCarriesWorkspaceParam(method);
                            if (!scoped) {
                                events.add(SimpleConditionEvent.violated(
                                        method,
                                        javaClass.getSimpleName() + "."
                                                + name
                                                + " has no workspaceId parameter and is not allowlisted"));
                            }
                        }
                    }
                })
                .check(classes);
    }

    /**
     * A finder counts as workspace-scoped when its derived-query name references WorkspaceId or its (annotated)
     * parameter list contains a workspaceId. Spring Data derived names make the name check the reliable signal;
     * {@code @Param}-annotated native/JPQL methods are covered by the parameter scan.
     */
    private static boolean nameCarriesWorkspaceParam(JavaMethod method) {
        // "ByWorkspaceId" (not bare "WorkspaceId") so fleet enumerators like findDistinctWorkspaceIdsByConsentState
        // do NOT count as scoped — they must go through the explicit allowlist.
        if (method.getName().contains("ByWorkspaceId")) {
            return true;
        }
        return method.getParameters().stream()
                .anyMatch(p -> p.getAnnotations().stream()
                        .anyMatch(
                                a -> a.getRawType().getName().equals("org.springframework.data.repository.query.Param")
                                        && a.get("value")
                                                .map(v -> "workspaceId".equals(v))
                                                .orElse(false)));
    }

    @Test
    @DisplayName("the workspace-scope detector accepts scoped shapes and rejects an unscoped finder")
    void workspaceScopeDetectorSelfTest() {
        // The rule above delegates to nameCarriesWorkspaceParam; if that helper rotted (e.g. the @Param annotation
        // lookup broke), every finder would count as unscoped — the allowlist and the rule's real violations would
        // be indistinguishable from noise. This pins both directions cheaply on the live class graph.
        JavaClass repo = classes.stream()
                .filter(c -> c.getSimpleName().equals("SlackMonitoredChannelRepository"))
                .findFirst()
                .orElseThrow();
        JavaMethod scopedDerived = repo.getMethods().stream()
                .filter(m -> m.getName().equals("findByWorkspaceIdAndSlackChannelId"))
                .findFirst()
                .orElseThrow();
        JavaMethod unscopedEnumerator = repo.getMethods().stream()
                .filter(m -> m.getName().equals("findDistinctWorkspaceIdsByConsentState"))
                .findFirst()
                .orElseThrow();
        assertThat(nameCarriesWorkspaceParam(scopedDerived)).isTrue();
        // The enumerator is unscoped BY DESIGN — the helper must say so, forcing it through the explicit allowlist.
        assertThat(nameCarriesWorkspaceParam(unscopedEnumerator)).isFalse();
    }

    /**
     * Pre-existing exception: {@code SlackChannelHistorySyncService} predates this rule and lives in the
     * {@code sync} package (out of scope for the raw-JDBC-cleanup change that introduced this test). Tracked here
     * rather than silently reclassified so the naming debt stays visible instead of disappearing into a diff this
     * rule didn't ask for.
     */
    private static final Set<String> SERVICE_STEREOTYPE_ALLOWLIST = Set.of("SlackChannelHistorySyncService");

    /**
     * Directional stereotype-suffix discipline: a name that PROMISES a stereotype must not lie about it.
     *
     * <p>Deliberately NOT the reverse direction ("every {@code @Service} must be named {@code *Service}"): the
     * module already has a legitimate "zoo" of {@code @Component} names by role — {@code *Gate}, {@code *Resolver},
     * {@code *Verifier}, {@code *Linker}, {@code *Publisher}, {@code *Sweeper}, {@code *Handler}, … — and forcing
     * all of them into one suffix would be a mass rename with no correctness payoff. What the maintainer's review
     * actually asked for is that {@code *Service}/{@code *Repository}/{@code *Controller} — names that make a
     * concrete stereotype promise to a reader — keep that promise.
     */
    @Test
    @DisplayName("*Service/*Repository/*Controller names keep their stereotype promise")
    void slackServiceNamesAreServices() {
        classes()
                .that()
                .resideInAPackage(SLACK)
                .and()
                .haveSimpleNameEndingWith("Service")
                .and(DescribedPredicate.describe(
                        "are not explicitly allowlisted pre-existing exceptions",
                        javaClass -> !SERVICE_STEREOTYPE_ALLOWLIST.contains(javaClass.getSimpleName())))
                .should(new ArchCondition<>("be annotated @Service (or be an interface)") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        boolean isService = javaClass.isAnnotatedWith("org.springframework.stereotype.Service");
                        if (!isService && !javaClass.isInterface()) {
                            events.add(SimpleConditionEvent.violated(
                                    javaClass,
                                    javaClass.getName() + " is named *Service but is not annotated @Service"));
                        }
                    }
                })
                .check(classes);

        classes()
                .that()
                .resideInAPackage(SLACK)
                .and()
                .haveSimpleNameEndingWith("Repository")
                .should(
                        new ArchCondition<>(
                                "be an interface extending org.springframework.data.repository.Repository") {
                            @Override
                            public void check(JavaClass javaClass, ConditionEvents events) {
                                boolean isRepositoryInterface = javaClass.isInterface()
                                        && javaClass.isAssignableTo("org.springframework.data.repository.Repository");
                                if (!isRepositoryInterface) {
                                    events.add(
                                            SimpleConditionEvent.violated(
                                                    javaClass,
                                                    javaClass.getName()
                                                            + " is named *Repository but is not an interface extending Repository"));
                                }
                            }
                        })
                .check(classes);

        classes()
                .that()
                .resideInAPackage(SLACK)
                .and()
                .haveSimpleNameEndingWith("Controller")
                .and()
                // an abstract *Controller base (shared plumbing for concrete controllers) makes no runtime promise
                .doNotHaveModifier(JavaModifier.ABSTRACT)
                .should(new ArchCondition<>("be @RestController, directly or via a meta-annotation") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        boolean isRestController =
                                javaClass.isMetaAnnotatedWith("org.springframework.web.bind.annotation.RestController");
                        if (!isRestController) {
                            events.add(SimpleConditionEvent.violated(
                                    javaClass,
                                    javaClass.getName() + " is named *Controller but is not @RestController"));
                        }
                    }
                })
                .check(classes);
    }
}
