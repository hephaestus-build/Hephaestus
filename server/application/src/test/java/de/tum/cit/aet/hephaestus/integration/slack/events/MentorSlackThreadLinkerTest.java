package de.tum.cit.aet.hephaestus.integration.slack.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorSlackThreadService;
import de.tum.cit.aet.hephaestus.integration.slack.domain.MentorSlackThread;
import de.tum.cit.aet.hephaestus.integration.slack.domain.MentorSlackThreadRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;

class MentorSlackThreadLinkerTest extends BaseUnitTest {

    private static final long WORKSPACE = 42L;
    private static final String TEAM = "T1";
    private static final String CHANNEL = "D9";
    private static final String THREAD = "100.1";
    private static final String USER = "U1";

    @Mock
    private MentorSlackThreadRepository mentorSlackThreadRepository;

    @Mock
    private MentorSlackThreadService mentorSlackThreadService;

    @InjectMocks
    private MentorSlackThreadLinker linker;

    @Test
    void existingMapping_returnsItsThreadId_withoutProvisioning() {
        UUID existing = UUID.randomUUID();
        MentorSlackThread mapping = new MentorSlackThread();
        mapping.setChatThreadId(existing);
        when(mentorSlackThreadRepository.findByWorkspaceIdAndSlackChannelIdAndSlackThreadTs(WORKSPACE, CHANNEL, THREAD))
                .thenReturn(Optional.of(mapping));

        UUID result = linker.findOrCreateThread(WORKSPACE, TEAM, CHANNEL, THREAD, USER, 314L);

        assertThat(result).isEqualTo(existing);
        verifyNoInteractions(mentorSlackThreadService);
        verify(mentorSlackThreadRepository, never()).save(ArgumentMatchers.any());
    }

    @Test
    void missingMapping_provisionsThreadAndPersistsMapping() {
        UUID created = UUID.randomUUID();
        when(mentorSlackThreadRepository.findByWorkspaceIdAndSlackChannelIdAndSlackThreadTs(WORKSPACE, CHANNEL, THREAD))
                .thenReturn(Optional.empty());
        when(mentorSlackThreadService.ensureSlackThread(eq(WORKSPACE), isNull(), eq(314L)))
                .thenReturn(created);

        UUID result = linker.findOrCreateThread(WORKSPACE, TEAM, CHANNEL, THREAD, USER, 314L);

        assertThat(result).isEqualTo(created);
        ArgumentCaptor<MentorSlackThread> saved = ArgumentCaptor.forClass(MentorSlackThread.class);
        verify(mentorSlackThreadRepository).save(saved.capture());
        MentorSlackThread mapping = saved.getValue();
        assertThat(mapping.getWorkspaceId()).isEqualTo(WORKSPACE);
        assertThat(mapping.getChatThreadId()).isEqualTo(created);
        assertThat(mapping.getSlackTeamId()).isEqualTo(TEAM);
        assertThat(mapping.getSlackChannelId()).isEqualTo(CHANNEL);
        assertThat(mapping.getSlackThreadTs()).isEqualTo(THREAD);
        assertThat(mapping.getSlackUserId()).isEqualTo(USER);
        assertThat(mapping.getId()).isNotNull();
    }
}
