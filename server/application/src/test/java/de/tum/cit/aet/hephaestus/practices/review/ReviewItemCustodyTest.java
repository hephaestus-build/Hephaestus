package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class ReviewItemCustodyTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final ReviewItemCustody custodies =
            new ReviewItemCustody(mock(ReviewItemCopiedIdentityRepository.class), json);

    private static PersonCopyIdentity copy(String subject) {
        return new PersonCopyIdentity("GITHUB", "https://github.com", subject, null);
    }

    @Test
    void shouldReadTheCopiedIdentitiesOnlyFromTheRows() {
        // A list in the detail is never read: the migration moved every list into the rows and removed it.
        var stored = json.createObjectNode()
                .put("complete", true)
                .put("refused", false)
                .set("copiedIdentities", json.valueToTree(List.of(copy("detail"))));
        stored.set("repositoryIds", json.createArrayNode());
        stored.set("sourceJobIds", json.createArrayNode());

        assertThat(custodies.read(stored, List.of(copy("row"))).copiedIdentities())
                .containsExactly(copy("row"));
    }

    @Test
    void shouldReadOnlyTheRowsOfADetailWrittenAfterTheMove() {
        var stored = json.createObjectNode().put("complete", true).put("refused", false);
        stored.set("repositoryIds", json.createArrayNode());
        stored.set("sourceJobIds", json.createArrayNode());

        assertThat(custodies.read(stored, List.of(copy("row"))).copiedIdentities())
                .containsExactly(copy("row"));
    }
}
