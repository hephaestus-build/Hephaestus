package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import de.tum.cit.aet.hephaestus.conversation.ConversationRepository;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * No contact rule reads a message, its read state or a reply (ADR 0139 decision 16): silence is never read as
 * dislike. The contact rules and the facts they decide on may use only the rows that hold those facts.
 */
class ContactRulesReadNoMessagesTest extends HephaestusArchitectureTest {

    private static final String CONVERSATION = "de.tum.cit.aet.hephaestus.conversation.";

    /** The contact policy, the facts it decides on, and the rows that hold them. */
    private static boolean isContactRule(JavaClass type) {
        String name = type.getName();
        return name.startsWith(CONVERSATION + "Contact")
                || name.startsWith(CONVERSATION + "CheckIn")
                || name.startsWith(CONVERSATION + "AccountMeter");
    }

    private static final Set<String> FRAMEWORKS =
            Set.of("java.", "org.jspecify.", "lombok.", "org.springframework.", "jakarta.persistence.");

    /** The ports that the facts read: switches, Silent Mode, the person's time zone and the owner binding. */
    private static final Set<String> PORTS = Set.of(
            "de.tum.cit.aet.hephaestus.core.ContinuingLifecycle",
            "de.tum.cit.aet.hephaestus.core.Lifecycle",
            "de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic",
            "de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory",
            "de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope",
            "de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope$StoredOwnerOperation",
            "de.tum.cit.aet.hephaestus.core.privacy.spi.PersonOwned",
            "de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole",
            "de.tum.cit.aet.hephaestus.core.settings.spi.SilentModeQuery",
            "de.tum.cit.aet.hephaestus.practices.spi.Severity",
            "de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceStartedMessages");

    @Test
    void shouldReadNoMessageReadStateOrReply() {
        var rules = classes.stream()
                .filter(ContactRulesReadNoMessagesTest::isContactRule)
                .toList();
        assertThat(rules)
                .extracting(JavaClass::getSimpleName)
                .contains("ContactPolicy", "ContactFacts", "CheckInRepository", "AccountMeterRepository");
        assertThat(readsBeyondTheFacts(rules, ContactRulesReadNoMessagesTest::isContactRule))
                .isEmpty();
    }

    @Test
    void shouldDetectARuleThatReadsTheConversation() {
        JavaClasses fixture = new ClassFileImporter().importClasses(RuleThatReadsReplies.class);
        Predicate<JavaClass> self = type -> type.isEquivalentTo(RuleThatReadsReplies.class);
        assertThat(readsBeyondTheFacts(fixture.stream().filter(self).toList(), self))
                .containsExactly(ConversationRepository.class.getName());
    }

    private static List<String> readsBeyondTheFacts(List<JavaClass> rules, Predicate<JavaClass> isRule) {
        return rules.stream()
                .flatMap(type -> type.getDirectDependenciesFromSelf().stream())
                .map(dependency -> dependency.getTargetClass().getBaseComponentType())
                .filter(target -> !target.isPrimitive()
                        && !isRule.test(target)
                        && !PORTS.contains(target.getName())
                        && FRAMEWORKS.stream().noneMatch(target.getName()::startsWith))
                .map(JavaClass::getName)
                .distinct()
                .sorted()
                .toList();
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class RuleThatReadsReplies {
        boolean unanswered(ConversationRepository conversations) {
            return conversations.count() > 3;
        }
    }
}
