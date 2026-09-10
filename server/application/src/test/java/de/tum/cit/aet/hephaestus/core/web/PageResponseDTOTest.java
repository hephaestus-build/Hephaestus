package de.tum.cit.aet.hephaestus.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import tools.jackson.databind.json.JsonMapper;

class PageResponseDTOTest extends BaseUnitTest {

    private final JsonMapper mapper = new JsonMapper();

    @Test
    void shouldPreserveTheExistingFlatPageWireContract() {
        var page = new PageImpl<>(
                List.of("entry"), PageRequest.of(1, 2, Sort.by("id").descending()), 5);
        var json = mapper.readTree(mapper.writeValueAsString(PageResponseDTO.from(page)));

        assertThat(json).isEqualTo(mapper.readTree("""
                {"content":["entry"],"totalElements":5,"totalPages":3,"number":1,"size":2,
                 "numberOfElements":1,"first":false,"last":false,"empty":false,
                 "sort":{"empty":false,"sorted":true,"unsorted":false},
                 "pageable":{"pageNumber":1,"pageSize":2,"offset":2,"paged":true,"unpaged":false,
                             "sort":{"empty":false,"sorted":true,"unsorted":false}}}
                """));
    }

    @Test
    void shouldRepresentAnEmptyPageWithoutInventingContentOrPages() {
        var page = new PageImpl<String>(List.of(), PageRequest.of(0, 20), 0);
        var result = PageResponseDTO.from(page);

        assertThat(result.content()).isEmpty();
        assertThat(result.totalPages()).isZero();
        assertThat(result.first()).isTrue();
        assertThat(result.last()).isTrue();
        assertThat(result.empty()).isTrue();
        assertThat(result.sort().unsorted()).isTrue();
    }
}
