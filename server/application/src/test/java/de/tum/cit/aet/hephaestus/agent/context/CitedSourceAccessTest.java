package de.tum.cit.aet.hephaestus.agent.context;

import static de.tum.cit.aet.hephaestus.practices.result.ResultTestFixtures.facts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.spi.ResultProofAccess;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CitedSourceAccessTest extends BaseUnitTest {
    @Test
    void shouldRecheckExactWorkspaceResultsAndPurposeWithoutInventingAViewer() {
        var results = mock(ResultProofAccess.class);
        var rows = List.of(Result.builder().id(UUID.randomUUID()).workspaceId(1).build());
        var id = rows.getFirst().getId();
        var purpose = SourceUsePurpose.CONVERSATIONAL_MENTORING;
        var access = new CitedSourceAccess(results);
        when(results.permitsAll(1, facts(rows), purpose)).thenReturn(Set.of(id));
        assertThat(access.permitsAll(1, facts(rows), purpose)).containsExactly(id);
        when(results.permitsAll(1, facts(rows), purpose)).thenReturn(Set.of());
        assertThat(access.permitsAll(1, facts(rows), purpose)).isEmpty();
        assertThat(access.permitsAll(9, facts(rows), purpose)).isEmpty();
        assertThat(access.permitsAll(1, facts(rows), SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .isEmpty();
        when(results.permitsAllHistory(1, facts(rows), SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .thenReturn(Set.of(id));
        assertThat(access.permitsAllHistory(1, facts(rows), SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .containsExactly(id);
        assertThat(access.permitsAllHistory(1, facts(rows), purpose)).isEmpty();
    }
}
