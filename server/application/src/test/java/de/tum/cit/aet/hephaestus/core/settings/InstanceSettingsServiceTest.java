package de.tum.cit.aet.hephaestus.core.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventWriter;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import tools.jackson.databind.ObjectMapper;

class InstanceSettingsServiceTest extends BaseUnitTest {

    @Mock
    private InstanceSettingsRepository repository;

    private InstanceSettingsService service;

    private AuthEventWriter authEventWriter;

    @BeforeEach
    void setUp() {
        authEventWriter = mock(AuthEventWriter.class);
        service = new InstanceSettingsService(
                repository, Optional.of(new AuthEventLogger(authEventWriter)), new ObjectMapper());
    }

    @Test
    void toggleIsRecordedOnTheAuditTrail() {
        givenEngagedRow(new InstanceSettings());

        service.updateSilentMode(true, "incident #42", 42L, null);

        verify(authEventWriter)
                .write(argThat(data -> data.type() == AuthEvent.EventType.SILENT_MODE_CHANGED
                        && data.details() != null
                        && data.details().contains("incident #42")));
    }

    @Test
    void shouldReturnRepositoryDecisionWhenSilentModeStateIsRead() {
        when(repository.readSilentModeEngaged()).thenReturn(true);
        assertThat(service.isSilentModeEngaged()).isTrue();
        when(repository.readSilentModeEngaged()).thenReturn(false);
        assertThat(service.isSilentModeEngaged()).isFalse();
    }

    @Test
    void engage_recordsTrimmedReasonAndActor() {
        givenEngagedRow(new InstanceSettings());

        InstanceSettings updated = service.updateSilentMode(true, "  incident #42  ", 42L, null);

        assertThat(updated.isSilentModeEngaged()).isTrue();
        assertThat(updated.getSilentModeReason()).isEqualTo("incident #42");
        assertThat(updated.getSilentModeChangedByAccountId()).isEqualTo(42L);
        assertThat(updated.getSilentModeChangedAt()).isNotNull();
    }

    @Test
    void engage_blankReasonBecomesNull() {
        givenEngagedRow(new InstanceSettings());
        assertThat(service.updateSilentMode(true, "   ", 42L, null).getSilentModeReason())
                .isNull();
    }

    @Test
    void release_clearsTheReason() {
        InstanceSettings engaged = new InstanceSettings();
        engaged.setSilentModeEngaged(true);
        engaged.setSilentModeReason("incident #42");
        givenReleasedRow(engaged);

        InstanceSettings released =
                service.updateSilentMode(false, "ignored on release", 42L, EntityTagPrecondition.parse("\"0\""));

        assertThat(released.isSilentModeEngaged()).isFalse();
        assertThat(released.getSilentModeReason()).isNull();
    }

    private void givenEngagedRow(InstanceSettings row) {
        row.setId(InstanceSettings.SINGLETON_ID);
        when(repository.findById(InstanceSettings.SINGLETON_ID)).thenReturn(Optional.of(row));
        when(repository.engageSilentMode(nullable(String.class), any(), nullable(Long.class)))
                .thenAnswer(call -> {
                    row.setSilentModeEngaged(true);
                    row.setSilentModeReason(call.getArgument(0));
                    row.setSilentModeChangedAt(call.getArgument(1));
                    row.setSilentModeChangedByAccountId(call.getArgument(2));
                    return 1;
                });
    }

    private void givenReleasedRow(InstanceSettings row) {
        row.setId(InstanceSettings.SINGLETON_ID);
        when(repository.findById(InstanceSettings.SINGLETON_ID)).thenReturn(Optional.of(row));
        when(repository.saveAndFlush(any(InstanceSettings.class))).thenAnswer(call -> call.getArgument(0));
    }
}
