package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.exception.JournalPendingException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class PrivacyJournalShippingTest {

    @Test
    void shouldKeepAnAcknowledgementPendingIfRecordingTheProofFails() {
        var jdbc = mock(JdbcTemplate.class);
        var bucket = mock(PrivacyJournalObjectStore.class);
        var mapper = new ObjectMapper();
        var id = UUID.randomUUID();
        var entry = new PrivacyJournalObject(
                id, PrivacyJournalKind.ENTRY_DELETED, mapper.createObjectNode(), Instant.EPOCH);
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<PrivacyJournalObject>>any(), eq(id)))
                .thenReturn(List.of(entry));
        when(bucket.store(eq(entry.relativeKey()), any())).thenReturn(true);
        when(jdbc.update(anyString(), eq(id)))
                .thenThrow(new TransientDataAccessResourceException("connection lost"))
                .thenReturn(1);

        var meters = new SimpleMeterRegistry();
        try {
            var shipping = new PrivacyJournalShipping(jdbc, mapper, Optional.of(bucket), meters);
            assertThatThrownBy(() -> shipping.confirm(id)).isInstanceOf(JournalPendingException.class);
            shipping.confirm(id);
        } finally {
            meters.close();
        }
        verify(bucket, times(2)).store(eq(entry.relativeKey()), any());
    }
}
