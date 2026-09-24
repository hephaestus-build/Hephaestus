package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import de.tum.cit.aet.hephaestus.integration.core.egress.EgressExempt;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGateway;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.SilentModeGraphQlClientFactory;
import de.tum.cit.aet.hephaestus.integration.core.egress.SilentModeGraphQlInterceptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApprovalChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScmCommentReactionSink;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.notification.email.EmailGateway;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.client.GraphQlClientInterceptor;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.graphql.client.RSocketGraphQlClient;
import org.springframework.graphql.client.WebSocketGraphQlClient;
import org.springframework.http.HttpMethod;
import org.springframework.mail.MailSender;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

class OutboundEgressArchitectureTest extends HephaestusArchitectureTest {

    @Test
    void shouldDeclareEveryScmWriterAsGateway() {
        ArchRule rule = classes()
                .that()
                .areAssignableTo(SummaryChannel.class)
                .or()
                .areAssignableTo(InlineFeedbackChannel.class)
                .or()
                .areAssignableTo(ApprovalChannel.class)
                .or()
                .areAssignableTo(ScmCommentReactionSink.class)
                .and()
                .areNotInterfaces()
                .should()
                .beAnnotatedWith(OutboundEgressGateway.class)
                .because("every SCM feedback writer must be inventoried as an enforced egress gateway");

        rule.check(classes);
    }

    @Test
    void shouldRequireEveryGatewayToConsultSharedGuard() {
        ArchRule rule = classes()
                .that()
                .areAnnotatedWith(OutboundEgressGateway.class)
                .and()
                .areNotInterfaces()
                .should()
                .callMethod(OutboundEgressGuard.class, "requireDeliveryAllowed", String.class)
                .because("a declared gateway without the shared fail-closed guard can bypass Silent Mode");

        rule.check(classes);
    }

    @Test
    void shouldRestrictGatewaysToDeclaredClientSurfaces() {
        ArchRule rule = classes()
                .that()
                .areAnnotatedWith(OutboundEgressGateway.class)
                .should()
                .beAssignableTo(SummaryChannel.class)
                .orShould()
                .beAssignableTo(InlineFeedbackChannel.class)
                .orShould()
                .beAssignableTo(ApprovalChannel.class)
                .orShould()
                .beAssignableTo(ScmCommentReactionSink.class)
                .orShould()
                .haveFullyQualifiedName(SlackMessageService.class.getName())
                .orShould()
                .haveFullyQualifiedName(EmailGateway.class.getName())
                .because("gateway status is limited to the reviewed SPI, Slack and email client surfaces");

        rule.check(classes);
    }

    @Test
    void shouldKeepSmtpWritesInsideDeclaredGateway() {
        noClasses()
                .that()
                .areNotAnnotatedWith(OutboundEgressGateway.class)
                .should()
                .callMethodWhere(target(owner(assignableTo(MailSender.class))).and(target(name("send"))))
                .because("an SMTP send outside the email gateway would bypass Silent Mode and the delivery record")
                .check(classes);

        noClasses()
                .that()
                .resideOutsideOfPackage("..notification.email..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.mail..", "jakarta.mail..")
                .because(
                        "the notification.email package is the only home of the mail API; everything else hands it an EmailMessage")
                .check(classes);
    }

    @Test
    void shouldRestrictExemptionsToReviewedControlPlanePackages() {
        ArchRule rule = classes()
                .that()
                .areAnnotatedWith(EgressExempt.class)
                .should()
                .resideInAnyPackage(
                        "..integration.scm.github.app..",
                        "..integration.scm.gitlab.common..",
                        "..integration.slack.connect..")
                .because("delivery writers must not self-exempt outside the declared control-plane allowlist");

        rule.check(classes);
    }

    @Test
    void shouldKeepSlackSdkWritesInsideDeclaredGateway() {
        ArchRule rule = noClasses()
                .that()
                .areNotAnnotatedWith(OutboundEgressGateway.class)
                .and()
                .areNotAnnotatedWith(EgressExempt.class)
                .and()
                .areNotInterfaces()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.slack.api.methods..")
                .because(
                        "Slack SDK access must stay behind Silent Mode unless a writer declares a reviewed control-plane exemption");

        rule.check(classes);
    }

    @Test
    void shouldKeepScmRestWritesInsideGatewaysOrExemptions() {
        for (String method : new String[] {"post", "put", "patch", "delete"}) {
            noClasses()
                    .that()
                    .resideInAPackage("..integration.scm..")
                    .and()
                    .areNotAnnotatedWith(OutboundEgressGateway.class)
                    .and()
                    .areNotAnnotatedWith(EgressExempt.class)
                    .should()
                    .callMethod(WebClient.class, method)
                    .because("SCM REST writes must use a declared delivery gateway or control-plane exemption")
                    .check(classes);
        }

        noClasses()
                .that()
                .resideInAPackage("..integration.scm..")
                .and()
                .areNotAnnotatedWith(OutboundEgressGateway.class)
                .and()
                .areNotAnnotatedWith(EgressExempt.class)
                .should()
                .callMethod(WebClient.class, "method", HttpMethod.class)
                .because("generic SCM HTTP methods could hide a write outside the reviewed egress surface")
                .check(classes);

        noClasses()
                .that()
                .resideInAPackage("..integration.scm..")
                .and()
                .areNotAnnotatedWith(OutboundEgressGateway.class)
                .and()
                .areNotAnnotatedWith(EgressExempt.class)
                .should()
                .dependOnClassesThat()
                .areAssignableTo(RestClient.class)
                .because("SCM REST access must use the inventoried WebClient surface")
                .check(classes);

        noClasses()
                .that()
                .resideInAPackage("..integration.scm..")
                .and()
                .areNotAnnotatedWith(OutboundEgressGateway.class)
                .and()
                .areNotAnnotatedWith(EgressExempt.class)
                .should()
                .dependOnClassesThat()
                .areAssignableTo(RestTemplate.class)
                .because("SCM REST access must not introduce an unreviewed legacy transport")
                .check(classes);
    }

    @Test
    void shouldBuildGraphQlClientsOnlyWithFailClosedFactory() {
        noClasses()
                .that()
                .doNotHaveFullyQualifiedName(SilentModeGraphQlClientFactory.class.getName())
                .should()
                .callMethod(HttpGraphQlClient.class, "builder")
                .orShould()
                .callMethod(HttpGraphQlClient.class, "builder", WebClient.class)
                .orShould()
                .callMethod(HttpGraphQlClient.class, "builder", WebClient.Builder.class)
                .orShould()
                .callMethod(HttpGraphQlClient.class, "create", WebClient.class)
                .because(
                        "the sole GraphQL client factory installs both request and per-attempt Silent Mode enforcement")
                .check(classes);

        noClasses()
                .that()
                .resideInAPackage("..integration.scm..")
                .should()
                .dependOnClassesThat()
                .areAssignableTo(WebSocketGraphQlClient.class)
                .orShould()
                .dependOnClassesThat()
                .areAssignableTo(RSocketGraphQlClient.class)
                .because("alternate GraphQL transports are outside the audited egress boundary")
                .check(classes);

        noClasses()
                .that()
                .doNotHaveFullyQualifiedName(SilentModeGraphQlClientFactory.class.getName())
                .should()
                .callMethod(HttpGraphQlClient.class, "mutate")
                .orShould()
                .callMethod(HttpGraphQlClient.Builder.class, "webClient", Consumer.class)
                .because("only the fail-closed factory may derive or customize guarded clients")
                .check(classes);

        noClasses()
                .that()
                .doNotHaveFullyQualifiedName(SilentModeGraphQlClientFactory.class.getName())
                .should()
                .callMethod(HttpGraphQlClient.Builder.class, "interceptor", GraphQlClientInterceptor[].class)
                .orShould()
                .callMethod(HttpGraphQlClient.Builder.class, "interceptors", Consumer.class)
                .because("only the fail-closed factory may configure the GraphQL interceptor chain")
                .check(classes);

        classes()
                .that()
                .haveFullyQualifiedName(SilentModeGraphQlClientFactory.class.getName())
                .should()
                .callMethod(SilentModeGraphQlInterceptor.class, "httpAttemptFilter")
                .andShould()
                .callMethod(HttpGraphQlClient.Builder.class, "interceptor", GraphQlClientInterceptor[].class)
                .because("the sole client factory must install both mutation tagging and per-attempt enforcement")
                .check(classes);

        classes()
                .that()
                .haveSimpleNameEndingWith("GraphQlConfig")
                .and()
                .resideInAnyPackage("..integration.scm.github.graphql..", "..integration.scm.gitlab.common.graphql..")
                .should()
                .dependOnClassesThat()
                .areAssignableTo(SilentModeGraphQlClientFactory.class)
                .because("SCM GraphQL configurations must use the sole fail-closed client factory")
                .check(classes);
    }
}
