package de.tum.cit.aet.hephaestus.integration.core.events;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewCommandTest extends BaseUnitTest {

    @ParameterizedTest
    @ValueSource(strings = {"/hephaestus review", "  /Hephaestus Review\n", "/HEPHAESTUS REVIEW"})
    void shouldMatchWhenTheCommentIsOnlyTheCommand(String comment) {
        assertThat(ReviewCommand.matches(comment)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "/hephaestus review please",
                "/hephaestus review now",
                "please /hephaestus review",
                "/hephaestus",
                "/hephaestus  review",
                "/hephaestus reviews",
                "@hephaestus review"
            })
    void shouldNotMatchWhenTheCommentIsAnythingElse(@Nullable String comment) {
        assertThat(ReviewCommand.matches(comment)).isFalse();
    }
}
