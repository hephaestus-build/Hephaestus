package de.tum.cit.aet.hephaestus.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorAdmission;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorRefusal;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.spi.AiRefusalReason;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemberAiReadinessAdapterTest extends BaseUnitTest {
    private static final long WORKSPACE = 1L;
    private static final long ACCOUNT = 10L;

    private final MentorAdmission admission = mock(MentorAdmission.class);
    private final PersonProcessingSuppression suppression = mock(PersonProcessingSuppression.class);
    private final MemberAiPreferences preferences = mock(MemberAiPreferences.class);
    private final MemberAiRoutingAdapter routing = mock(MemberAiRoutingAdapter.class);
    private final MemberAiReadinessAdapter adapter =
            new MemberAiReadinessAdapter(admission, suppression, preferences, routing);

    @BeforeEach
    void setUp() {
        lenient().when(suppression.isAccountSuppressed(ACCOUNT)).thenReturn(false);
        choose(false, AiChoice.CLOUD);
        lenient()
                .when(routing.bindingForPeople(
                        WORKSPACE, AgentPurpose.PRACTICE_REVIEW, List.<@Nullable Long>of(ACCOUNT)))
                .thenReturn(Optional.of(new WorkspaceAgentBinding()));
    }

    @Test
    void shouldAdmitTheReviewWhenAModelServesTheMembersChoice() {
        assertThat(adapter.practiceReviewRefusal(WORKSPACE, ACCOUNT)).isEmpty();
    }

    @Test
    void shouldRefuseAsErasedBeforeAnyOtherReasonWhenTheAccountIsSuppressed() {
        lenient().when(suppression.isAccountSuppressed(ACCOUNT)).thenReturn(true);
        choose(true, AiChoice.NO_AI);

        assertThat(adapter.practiceReviewRefusal(WORKSPACE, ACCOUNT)).contains(AiRefusalReason.PERSON_ERASED);
    }

    @Test
    void shouldRefuseAsNoAiWhenTheMemberChoseNoAi() {
        choose(false, AiChoice.NO_AI);

        assertThat(adapter.practiceReviewRefusal(WORKSPACE, ACCOUNT)).contains(AiRefusalReason.NO_AI);
    }

    @Test
    void shouldRefuseAsChoiceRequiredWhenTheWorkspaceRequiresAChoiceTheMemberHasNotMade() {
        choose(true, null);

        assertThat(adapter.practiceReviewRefusal(WORKSPACE, ACCOUNT)).contains(AiRefusalReason.CHOICE_REQUIRED);
    }

    @Test
    void shouldRefuseAsUnavailableWhenNoReviewModelServesTheMember() {
        choose(false, null);
        lenient()
                .when(routing.bindingForPeople(
                        WORKSPACE, AgentPurpose.PRACTICE_REVIEW, List.<@Nullable Long>of(ACCOUNT)))
                .thenReturn(Optional.empty());

        assertThat(adapter.practiceReviewRefusal(WORKSPACE, ACCOUNT)).contains(AiRefusalReason.UNAVAILABLE);
    }

    @Test
    void shouldReportHephsOwnAdmissionWhenAskedForTheConversation() {
        lenient().when(admission.refusal(WORKSPACE, ACCOUNT)).thenReturn(Optional.of(MentorRefusal.CHOICE_REQUIRED));

        assertThat(adapter.conversationRefusal(WORKSPACE, ACCOUNT)).contains(AiRefusalReason.CHOICE_REQUIRED);
    }

    private void choose(boolean required, @Nullable AiChoice choice) {
        lenient()
                .when(preferences.forDeveloper(WORKSPACE, ACCOUNT))
                .thenReturn(new MemberAiPreferences.Decision(required, choice));
    }
}
