package de.tum.cit.aet.hephaestus.agent.scan;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemExclusionReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemLister;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemPage;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.core.spi.ListedItem;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScanItemVersions;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeReadSource;
import de.tum.cit.aet.hephaestus.practices.PracticeReads;
import de.tum.cit.aet.hephaestus.practices.ScanDefinition;
import de.tum.cit.aet.hephaestus.practices.spi.PinnedPractice;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewScanStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ScanExcludedItem;
import de.tum.cit.aet.hephaestus.practices.spi.ScanListedItem;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScanServiceTest extends BaseUnitTest {
    private static final ItemKind FILE = new ItemKind("scm.file");
    private final ItemLister lister = mock(ItemLister.class);
    private final ScanItemVersions versions = mock(ScanItemVersions.class);
    private final ItemIndexService writer = mock(ItemIndexService.class);
    private final ReviewScanStore store = mock(ReviewScanStore.class);

    private ScanService service(int entries) {
        return new ScanService(
                List.of(lister),
                List.of(versions),
                writer,
                store,
                new ScanProperties(entries, 10000, Duration.ofMinutes(1), Duration.ofHours(1)),
                Clock.systemUTC());
    }

    private ReviewSnapshot review() {
        var base = RuntimeReviewTestData.assignment(null, null).practice();
        var d = base.definition();
        var scan = new ScanDefinition(
                FILE,
                List.of("**/*.java"),
                List.of("generated/**"),
                List.of("pom.xml"),
                new PracticeReads(
                        "3.0.0",
                        List.of(new PracticeReadSource(new SourceKind("scm.repository.tree"), ReadNeed.COMPLETE))));
        var definition = new PracticeDefinition(
                d.name(),
                d.reviewOn(),
                d.appliesWhen(),
                d.about(),
                d.reads(),
                d.reviewedBy(),
                d.criteria(),
                d.script(),
                scan,
                d.lineNotes(),
                d.guidance());
        var review = new ReviewSnapshot(
                RuntimeReviewTestData.REVIEW,
                1,
                RuntimeReviewTestData.ITEM,
                ReviewKind.SCAN,
                new ItemVersion("tree:one", null),
                ReviewOrigin.REQUEST,
                1,
                List.of(new PinnedPractice(31, 32, definition, base.reviewDigest(), base.method())));
        when(lister.supports(review.item())).thenReturn(true);
        when(lister.memberKinds()).thenReturn(Set.of(FILE));
        when(versions.kinds()).thenReturn(Set.of(FILE));
        return review;
    }

    private ListedItem listed(long workspace, String path) {
        return new ListedItem(new ItemRegistration(workspace, FILE, null, 2L, path), new ItemVersion("blob:one", null));
    }

    @Test
    void rejectsAnIncompleteListingWithoutRecordingInventory() {
        var review = review();
        when(lister.list(any())).thenReturn(new ItemPage(List.of(), null, false, null));
        assertThatThrownBy(() -> service(10).prepare(review)).isInstanceOf(ScanService.EnumerationFailure.class);
        verifyNoInteractions(writer, store);
    }

    @Test
    void rejectsForeignWorkspaceItemsBeforeAnyWrite() {
        var review = review();
        when(lister.list(any())).thenReturn(new ItemPage(List.of(listed(99, "src/A.java")), null, true, null));
        assertThatThrownBy(() -> service(10).prepare(review)).isInstanceOf(ScanService.EnumerationFailure.class);
        verifyNoInteractions(writer, store);
    }

    @Test
    void boundsTheWholeTreeBeforeApplyingApplicability() {
        var review = review();
        when(lister.list(any()))
                .thenReturn(new ItemPage(List.of(listed(1, "a.txt"), listed(1, "b.txt")), null, true, null));
        assertThatThrownBy(() -> service(1).prepare(review)).isInstanceOf(ScanService.EnumerationFailure.class);
        verifyNoInteractions(writer, store);
    }

    @Test
    void recordsAllFilesButJudgesOnlyApplicableFilesWithDeclaredContext() {
        var review = review();
        var eligible = listed(1, "src/A.java");
        var excluded = listed(1, "generated/B.java");
        var file = new ItemRef(1, 3, FILE, null, 2L, "src/A.java");
        var generated = new ItemRef(1, 4, FILE, null, 2L, "generated/B.java");
        when(lister.list(any())).thenReturn(new ItemPage(List.of(eligible, excluded), null, true, null));
        when(writer.upsert(eligible.item())).thenReturn(file);
        when(writer.upsert(excluded.item())).thenReturn(generated);
        var version = new ItemVersion("blob:one:context", null);
        when(versions.compose(List.of(file), Objects.requireNonNull(review.version()), List.of("pom.xml")))
                .thenReturn(Map.of(file.itemId(), version));
        var service = service(10);
        var prepared = service.prepare(review);
        verifyNoInteractions(store);
        service.record(review, prepared);
        verify(store).inventory(1, review.id(), List.of(3L, 4L), true);
        verify(store).list(1, review.id(), 31, List.of(new ScanListedItem(file, version)), true);
    }

    @Test
    void shouldKeepIneligibleFilesInTheInventoryAndRecordExclusionsWithoutAssignments() {
        var review = review();
        var oversized = new ListedItem(
                listed(1, "Large.java").item(),
                new ItemVersion("large", null),
                ItemExclusionReason.FILE_TOO_LARGE,
                2_097_153);
        var binary = new ListedItem(
                listed(1, "Binary.java").item(), new ItemVersion("binary", null), ItemExclusionReason.BINARY_FILE, 3);
        var largeFile = new ItemRef(1, 3, FILE, null, 2L, "Large.java");
        var binaryFile = new ItemRef(1, 4, FILE, null, 2L, "Binary.java");
        when(lister.list(any())).thenReturn(new ItemPage(List.of(oversized, binary), null, true, null));
        when(writer.upsert(oversized.item())).thenReturn(largeFile);
        when(writer.upsert(binary.item())).thenReturn(binaryFile);
        var service = service(10);
        var prepared = service.prepare(review);
        service.record(review, prepared);
        verify(store).inventory(1, review.id(), List.of(3L, 4L), true);
        verify(store)
                .exclusions(
                        1,
                        review.id(),
                        List.of(
                                new ScanExcludedItem(largeFile, ItemExclusionReason.FILE_TOO_LARGE, 2_097_153),
                                new ScanExcludedItem(binaryFile, ItemExclusionReason.BINARY_FILE, 3)));
        verify(store).list(1, review.id(), 31, List.of(), true);
    }
}
