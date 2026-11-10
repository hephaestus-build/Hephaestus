package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventService;
import de.tum.cit.aet.hephaestus.integration.core.connection.ExactPersonIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.core.spi.CitationVerifier;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScanItemVersions;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class ScmCaptureRoleContextTest extends BaseUnitTest {
    @ParameterizedTest
    @CsvSource({"true,1", "false,0"})
    void shouldRegisterSourcesOnlyWhereReviewInputsArePrepared(boolean server, int expected) {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        ScmItemService.class,
                        ScmCitationVerifier.class,
                        ScmFileVersionComposer.class,
                        ScmCaptureConsent.class,
                        ExactPersonIdentityResolver.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(ScmItemAccess.class, () -> mock(ScmItemAccess.class))
                .withBean(ScmRepositoryAccess.class, () -> mock(ScmRepositoryAccess.class))
                .withBean(ScmWorkLookup.class, () -> mock(ScmWorkLookup.class))
                .withBean(IssueEvidenceVersion.class, () -> mock(IssueEvidenceVersion.class))
                .withBean(ScmGitTree.class, () -> mock(ScmGitTree.class))
                .withBean(ScmFileVersions.class, () -> mock(ScmFileVersions.class))
                .withBean(ScmWorkCapture.class, () -> mock(ScmWorkCapture.class))
                .withBean(ScmCaptureContributors.class, () -> mock(ScmCaptureContributors.class))
                .withBean(RepositoryRepository.class, () -> mock(RepositoryRepository.class))
                .withBean(PullRequestReviewRepository.class, () -> mock(PullRequestReviewRepository.class))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(AccountDirectory.class, () -> mock(AccountDirectory.class))
                .withBean(ItemEventService.class, () -> mock(ItemEventService.class))
                .withBean(PersonDataWriteFence.class, () -> mock(PersonDataWriteFence.class))
                .withBean(PersonDataCopyRecorder.class, () -> mock(PersonDataCopyRecorder.class))
                .withBean(PersonProcessingSuppression.class, () -> mock(PersonProcessingSuppression.class))
                .withPropertyValues(RuntimeRole.SERVER_PROPERTY + "=" + server)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(ItemSource.class)).hasSize(expected);
                    assertThat(context.getBeansOfType(ScanItemVersions.class)).hasSize(expected);
                    assertThat(context.getBeansOfType(CitationVerifier.class)).hasSize(expected == 0 ? 0 : 1);
                    assertThat(context.getBeansOfType(ScmCaptureConsent.class)).hasSize(expected == 0 ? 0 : 1);
                    assertThat(context.getBeansOfType(ExactPersonIdentityResolver.class))
                            .hasSize(expected == 0 ? 0 : 1);
                    if (expected > 0) {
                        var source = context.getBean(ItemSource.class);
                        for (var kind : ScmItemEvents.ALL)
                            assertThat(source.supports(new ItemRef(
                                            1,
                                            2,
                                            kind,
                                            kind.equals(ScmItemEvents.FILE) ? null : 3L,
                                            kind.equals(ScmItemEvents.FILE) ? 4L : null,
                                            kind.equals(ScmItemEvents.FILE) ? "a.txt" : null)))
                                    .isTrue();
                    }
                });
    }
}
