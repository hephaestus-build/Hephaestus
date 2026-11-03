package de.tum.cit.aet.hephaestus.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import tools.jackson.databind.json.JsonMapper;

class OffsetPageDTOTest extends BaseUnitTest {

    private final JsonMapper mapper = new JsonMapper();

    @Test
    void shouldWriteOnlyTheFiveFieldsOfTheOffsetShape() {
        var page = new PageImpl<>(
                List.of("entry"), PageRequest.of(1, 2, Sort.by("id").descending()), 5);
        var json = mapper.readTree(mapper.writeValueAsString(OffsetPageDTO.from(page)));

        assertThat(json).isEqualTo(mapper.readTree("""
                {"content":["entry"],"page":1,"size":2,"totalElements":5,"totalPages":3}
                """));
    }

    @Test
    void shouldRepresentAnEmptyPageWithoutInventingContentOrPages() {
        var page = new PageImpl<String>(List.of(), PageRequest.of(0, 20), 0);
        var result = OffsetPageDTO.from(page);

        assertThat(result.content()).isEmpty();
        assertThat(result.totalPages()).isZero();
        assertThat(result.totalElements()).isZero();
    }
}
