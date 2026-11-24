package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.PracticesAcrossWorkspaceDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceGroupSplitDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspacePracticeSplitDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceRangeDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceTileDTO;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStanding;
import de.tum.cit.aet.hephaestus.practices.spi.StandingBasis;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WorkspaceOverviewWireTest extends BaseUnitTest {
    @Test
    void shouldCarryOnlyCountsAndTheReadersOwnStandingWhenSerialized() {
        var split = WorkspaceSplits.shown(WorkspaceSplits.split(List.of(
                PracticeStanding.DEVELOPING,
                PracticeStanding.DEVELOPING,
                PracticeStanding.DEVELOPING,
                PracticeStanding.STRENGTH,
                PracticeStanding.STRENGTH,
                PracticeStanding.STRENGTH)));
        var practice = new WorkspacePracticeSplitDTO(
                "practice", "Practice", PracticeStanding.STRENGTH, StandingBasis.EARLIER_REVIEWS, split);
        var group = new WorkspaceGroupSplitDTO(
                "group",
                "Group",
                null,
                null,
                PracticeStanding.STRENGTH,
                StandingBasis.EARLIER_REVIEWS,
                split,
                List.of(practice));
        var dto = new PracticesAcrossWorkspaceDTO(
                new WorkspaceTileDTO(1, new WorkspaceRangeDTO(0, 2)), true, List.of(group));
        var tree = new JsonMapper().valueToTree(dto);
        assertThat(tree.path("groups").path(0).path("split").path("developers").asInt())
                .isEqualTo(6);
        assertThat(tree.path("includesEarlierReviews").asBoolean()).isTrue();
        assertThat(tree.toString())
                .doesNotContain("developerId", "accountId", "login", "feedbackBody", "toWorkOn", "summary");
    }

    @Test
    void shouldCopyItsListsWhenBuilt() {
        var input = new ArrayList<WorkspaceGroupSplitDTO>();
        var dto = new PracticesAcrossWorkspaceDTO(new WorkspaceTileDTO(0, null), false, input);
        input.clear();
        assertThatThrownBy(() -> dto.groups().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }
}
