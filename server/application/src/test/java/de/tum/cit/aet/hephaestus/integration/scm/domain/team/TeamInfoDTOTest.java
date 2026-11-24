package de.tum.cit.aet.hephaestus.integration.scm.domain.team;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembership;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A team's count on the wire counts only the members it lists, so no unnamed person comes back by subtraction. */
class TeamInfoDTOTest extends BaseUnitTest {

    private static final long HIDDEN = 3L;

    @Test
    void shouldCountOnlyTheListedMembersWhenOneIsHiddenAndOneIsABot() {
        var team = new Team();
        team.setId(1L);
        team.setName("Core");
        member(team, 1L, User.Type.USER);
        member(team, 2L, User.Type.USER);
        member(team, HIDDEN, User.Type.USER);
        member(team, 4L, User.Type.BOT);

        JsonNode sent = new JsonMapper()
                .valueToTree(TeamInfoDTO.fromTeamWithScopeSettings(
                        team, false, Set.of(), Set.of(), Set.of(), Set.of(HIDDEN)));

        assertThat(sent.path("members").size()).isEqualTo(2);
        assertThat(sent.path("membershipCount").asInt()).isEqualTo(2);
    }

    private static void member(Team team, long id, User.Type type) {
        var user = new User();
        user.setId(id);
        user.setLogin("user-" + id);
        user.setType(type);
        team.addMembership(new TeamMembership(team, user, TeamMembership.Role.MEMBER));
    }
}
