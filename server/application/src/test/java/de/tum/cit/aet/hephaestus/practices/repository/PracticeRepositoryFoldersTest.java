package de.tum.cit.aet.hephaestus.practices.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.MonitoredRepositoryQuery;
import de.tum.cit.aet.hephaestus.integration.core.spi.MonitoredRepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryCheckoutException;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryCheckouts;
import de.tum.cit.aet.hephaestus.practices.PracticeFolderInvalidException;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeFileProblemDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class PracticeRepositoryFoldersTest extends BaseUnitTest {
    private static final long WORKSPACE = 7;
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    Path tree;

    private final PracticeRepositorySettingRepository settings = mock(PracticeRepositorySettingRepository.class);
    private final MonitoredRepositoryQuery monitored = mock(MonitoredRepositoryQuery.class);
    private final RepositoryCheckouts checkouts = mock(RepositoryCheckouts.class);
    private final PracticeRepositoryFolders folders =
            new PracticeRepositoryFolders(settings, monitored, checkouts, Clock.systemUTC());

    @BeforeEach
    void setUp() {
        var setting = new PracticeRepositorySetting();
        setting.setRepositoryItemId(42);
        when(settings.findById(WORKSPACE)).thenReturn(Optional.of(setting));
        when(monitored.repositories(WORKSPACE))
                .thenReturn(Map.of(42L, new MonitoredRepositoryRef(42, 1, "acme/practices", "main")));
        when(checkouts.checkout(WORKSPACE, "acme/practices", "main", null)).thenAnswer(call -> checkout());
    }

    @Test
    void shouldReadTheFolderOfThePracticeAtTheHeadOfTheDefaultBranch() throws IOException {
        write("explain-the-change/practice.md", "---\nname: x\n---\n");
        write("explain-the-change/figures/flow.svg", "<svg/>");
        write("another/practice.md", "not this one");

        var folder = folders.read(WORKSPACE, "explain-the-change").orElseThrow();

        assertThat(folder.commit()).isEqualTo(COMMIT);
        assertThat(folder.files()).containsOnlyKeys("practice.md", "figures/flow.svg");
    }

    @Test
    void shouldKeepAFileNameWithABackslashApartFromTheFigureItsNameResembles() throws IOException {
        write("explain-the-change/practice.md", "---\nname: x\n---\n");
        write("explain-the-change/figures/flow.svg", "<svg>figure</svg>");
        write("explain-the-change/figures\\flow.svg", "<svg>another file</svg>");

        var folder = folders.read(WORKSPACE, "explain-the-change").orElseThrow();

        assertThat(folder.files())
                .containsEntry("figures/flow.svg", "<svg>figure</svg>")
                .containsEntry("figures\\flow.svg", "<svg>another file</svg>");
    }

    @Test
    void shouldFindNoFolderForAPracticeTheBranchDoesNotHold() {
        assertThat(folders.read(WORKSPACE, "explain-the-change")).isEmpty();
    }

    @Test
    void shouldRefuseALinkInsteadOfFollowingIt() throws IOException {
        write("explain-the-change/practice.md", "---\nname: x\n---\n");
        Path outside = Files.writeString(tree.resolve("secret.txt"), "outside the folder");
        Files.createSymbolicLink(base().resolve("explain-the-change/guide.md"), outside);

        assertThatThrownBy(() -> folders.read(WORKSPACE, "explain-the-change"))
                .isInstanceOf(PracticeFolderInvalidException.class);
    }

    /** A linked folder is unreadable, not absent, so a sync can never take it for a removed practice. */
    @Test
    void shouldRefuseALinkedFolderInsteadOfFindingNoFolder() throws IOException {
        write("elsewhere/practice.md", "---\nname: x\n---\n");
        Files.createSymbolicLink(base().resolve("explain-the-change"), base().resolve("elsewhere"));

        assertThatThrownBy(() -> folders.read(WORKSPACE, "explain-the-change"))
                .isInstanceOf(PracticeFolderInvalidException.class);
    }

    @Test
    void shouldReadEveryFolderOfTheDirectoryAndSkipTheFilesBesideThem() throws IOException {
        write("explain-the-change/practice.md", "---\nname: x\n---\n");
        write("Not_A_Slug/practice.md", "---\nname: y\n---\n");
        write("README.md", "The practices of this repository.");

        var tree = folders.readAll(WORKSPACE);

        assertThat(tree.commit()).isEqualTo(COMMIT);
        // A name that is not a slug is read, so that the check of the folder reports it.
        assertThat(tree.folders()).containsOnlyKeys("Not_A_Slug", "explain-the-change");
        assertThat(tree.unreadable()).isEmpty();
        assertThat(tree.problems()).isEmpty();
    }

    /** A linked or oversized folder is unreadable, not gone, so a sync never retires its practice. */
    @Test
    void shouldReportAFolderThatCannotBeReadApartFromTheOthers() throws IOException {
        write("explain-the-change/practice.md", "---\nname: x\n---\n");
        write("elsewhere/practice.md", "---\nname: y\n---\n");
        Files.createSymbolicLink(base().resolve("linked"), base().resolve("elsewhere"));
        write("large/practice.md", "x".repeat(100 * 1024));
        write("large/guide.md", "x".repeat(100 * 1024));
        write("large/script.ts", "x".repeat(100 * 1024));

        var tree = folders.readAll(WORKSPACE);

        assertThat(tree.folders()).containsOnlyKeys("elsewhere", "explain-the-change");
        assertThat(tree.unreadable()).containsOnlyKeys("large", "linked");
        assertThat(tree.unreadable().get("large"))
                .extracting(PracticeFileProblemDTO::code)
                .containsExactly(PracticeFileProblemDTO.FOLDER_TOO_LARGE);
    }

    @Test
    void shouldReportADirectoryThatTheDefaultBranchLacks() {
        var tree = folders.readAll(WORKSPACE);

        assertThat(tree.folders()).isEmpty();
        assertThat(tree.problems())
                .extracting(PracticeFileProblemDTO::code)
                .containsExactly(PracticeFileProblemDTO.DIRECTORY_MISSING);
    }

    @Test
    void shouldStopBeforeItReadsMoreFoldersThanAWorkspaceKeeps() throws IOException {
        for (int i = 0; i <= PracticeRepositoryFolders.MAX_FOLDERS; i++)
            write("practice-" + i + "/practice.md", "---\n---\n");

        var tree = folders.readAll(WORKSPACE);

        assertThat(tree.folders()).isEmpty();
        assertThat(tree.problems())
                .extracting(PracticeFileProblemDTO::code)
                .containsExactly(PracticeFileProblemDTO.TOO_MANY_FOLDERS);
    }

    @Test
    void shouldRefuseWhenTheRepositoryIsNotMonitoredOrCannotBeRead() {
        when(checkouts.checkout(WORKSPACE, "acme/practices", "main", null))
                .thenThrow(new RepositoryCheckoutException(
                        RepositoryCheckoutException.Code.MIRROR_UNAVAILABLE, "no mirror"));
        assertThatThrownBy(() -> folders.read(WORKSPACE, "explain-the-change"))
                .isInstanceOf(ResponseStatusException.class);

        when(monitored.repositories(WORKSPACE)).thenReturn(Map.of());
        assertThatThrownBy(() -> folders.read(WORKSPACE, "explain-the-change"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void shouldRefuseAWorkspaceWithoutAPracticeRepository() {
        when(settings.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> folders.read(WORKSPACE, "explain-the-change"))
                .isInstanceOf(ResponseStatusException.class);
    }

    private Path base() {
        return tree.resolve(PracticeRepositorySetting.DEFAULT_DIRECTORY);
    }

    private void write(String path, String content) throws IOException {
        Path file = base().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private RepositoryCheckouts.Checkout checkout() {
        return new RepositoryCheckouts.Checkout() {
            @Override
            public String repository() {
                return "acme/practices";
            }

            @Override
            public String head() {
                return COMMIT;
            }

            @Override
            public @Nullable String base() {
                return null;
            }

            @Override
            public Path directory() {
                return tree;
            }

            @Override
            public void close() {}
        };
    }
}
