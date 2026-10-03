package de.tum.cit.aet.hephaestus.practices.curated;

import static de.tum.cit.aet.hephaestus.practices.curated.CuratedCatalogFixtures.practice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.practices.GroupDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class CatalogEntryTest extends BaseUnitTest {

    private static final String SLUG = "small-prs";
    private static final PracticeDefinition SHIPPED = practice("Small PRs", "Shipped criteria", "Shipped reason");

    @Test
    void anEntryNobodyHasTouchedIsWhateverTheBuildShips() {
        CatalogEntry<PracticeDefinition> entry = CatalogEntry.shippedOnly(SLUG, SHIPPED, 0);

        assertThat(entry.state()).isEqualTo(CatalogEntryState.FROM_HEPHAESTUS);
        assertThat(entry.effective()).isEqualTo(SHIPPED);
        assertThat(entry.changeKind()).isEqualTo(CatalogChangeKind.NONE);
        assertThat(entry.offered()).isTrue();
    }

    @Test
    void aNewerBuildReachesAnUntouchedEntryWithNothingHavingToRun() {
        PracticeDefinition newer = practice("Small PRs", "Newer criteria", "Shipped reason");

        assertThat(CatalogEntry.shippedOnly(SLUG, newer, 0).effective()).isEqualTo(newer);
    }

    @Test
    void anEditIsHeldAgainstTheDefinitionItWasWrittenAgainst() {
        PracticeDefinition mine = practice("Small PRs", "Our criteria", "Shipped reason");

        assertThat(entry(mine, SHIPPED, CuratedDefinitionDigest.of(SLUG, SHIPPED))
                        .state())
                .isEqualTo(CatalogEntryState.EDITED_HERE);
    }

    @Test
    void aBuildThatMovesOnLeavesTheEditInForceAndTheNewDefinitionWaiting() {
        PracticeDefinition mine = practice("Small PRs", "Our criteria", "Shipped reason");
        PracticeDefinition newer = practice("Small PRs", "Newer criteria", "Shipped reason");
        CatalogEntry<PracticeDefinition> entry = entry(mine, newer, CuratedDefinitionDigest.of(SLUG, SHIPPED));

        assertThat(entry.state()).isEqualTo(CatalogEntryState.UPDATE_WAITING);
        assertThat(entry.effective()).isEqualTo(mine);
        assertThat(entry.shipped()).isEqualTo(newer);
    }

    @Test
    void anEntryThisInstanceWroteIsItsOwn() {
        PracticeDefinition mine = practice("House rule", "Our criteria", "Our reason");

        assertThat(entry(mine, null, null).state()).isEqualTo(CatalogEntryState.YOURS);
    }

    @Test
    void anEntryTheBuildStopsShippingKeepsTheInstancesOwnDefinition() {
        PracticeDefinition mine = practice("Small PRs", "Our criteria", "Shipped reason");

        assertThat(entry(mine, null, CuratedDefinitionDigest.of(SLUG, SHIPPED)).state())
                .isEqualTo(CatalogEntryState.NO_LONGER_SHIPPED);
    }

    @Test
    void anUpdateThatOnlyChangesWhatPeopleReadIsMarkedAsSuch() {
        PracticeDefinition mine = practice("Small PRs", "Shipped criteria", "Our reason");
        PracticeDefinition newer = practice("Small PRs", "Shipped criteria", "Better reason");

        assertThat(entry(mine, newer, "0".repeat(64)).changeKind()).isEqualTo(CatalogChangeKind.WORDING);
    }

    @Test
    void anUpdateThatChangesWhatGetsDetectedIsMarkedAsSuch() {
        PracticeDefinition mine = practice("Small PRs", "Our criteria", "Shipped reason");
        PracticeDefinition newer = practice("Small PRs", "Newer criteria", "Shipped reason");

        assertThat(entry(mine, newer, "0".repeat(64)).changeKind()).isEqualTo(CatalogChangeKind.REVIEW);
    }

    @Test
    void aLocalDefinitionThatTheShippedCatalogCatchesUpWithRemainsLocal() {
        CatalogEntry<PracticeDefinition> entry = entry(SHIPPED, SHIPPED, "0".repeat(64));

        assertThat(entry.state()).isEqualTo(CatalogEntryState.EDITED_HERE);
        assertThat(entry.changeKind()).isEqualTo(CatalogChangeKind.NONE);
    }

    @Test
    void tagDistinguishesAnIdenticalLocalDefinitionFromFollowingTheDefault() {
        CatalogEntry<PracticeDefinition> local = entry(SHIPPED, SHIPPED, null);
        CatalogEntry<PracticeDefinition> following = CatalogEntry.shippedOnly(SLUG, SHIPPED, 0);

        assertThat(local.state()).isEqualTo(CatalogEntryState.EDITED_HERE);
        assertThat(local.etag()).isNotEqualTo(following.etag());
    }

    @Test
    void aGroupUpdateIsPresentationNotReview() {
        var mine = new GroupDefinition("Maintainability", "Our description", "Wrench", "sky");
        var shipped = new GroupDefinition("Maintainability", "New description", "Wrench", "sky");
        var entry = new CatalogEntry<>("maintainability", mine, shipped, mine, "0".repeat(64), false, 0, null);

        assertThat(entry.changeKind()).isEqualTo(CatalogChangeKind.PRESENTATION);
    }

    @Test
    void theTagCoversTheEntryWithoutCouplingEditsToCatalogOrder() {
        CatalogEntry<PracticeDefinition> untouched = CatalogEntry.shippedOnly(SLUG, SHIPPED, 0);
        PracticeDefinition mine = practice("Small PRs", "Our criteria", "Shipped reason");

        assertThat(untouched.etag()).isNotBlank();
        assertThat(entry(mine, SHIPPED, CuratedDefinitionDigest.of(SLUG, SHIPPED))
                        .etag())
                .isNotEqualTo(untouched.etag());
        assertThat(retired(untouched).etag()).isNotEqualTo(untouched.etag());
        assertThat(withPosition(untouched, 1).etag()).isEqualTo(untouched.etag());
    }

    private static CatalogEntry<PracticeDefinition> entry(
            @Nullable PracticeDefinition mine,
            @Nullable PracticeDefinition shipped,
            @Nullable String acceptedBundledDigest) {
        PracticeDefinition effective = mine != null ? mine : shipped;
        assertNotNull(effective);
        return new CatalogEntry<>(SLUG, effective, shipped, mine, acceptedBundledDigest, false, 0, null);
    }

    private static CatalogEntry<PracticeDefinition> retired(CatalogEntry<PracticeDefinition> entry) {
        return new CatalogEntry<>(
                entry.slug(),
                entry.effective(),
                entry.shipped(),
                entry.overridden(),
                entry.acceptedBundledDigest(),
                true,
                entry.position(),
                entry.updatedAt());
    }

    private static CatalogEntry<PracticeDefinition> withPosition(CatalogEntry<PracticeDefinition> entry, int position) {
        return new CatalogEntry<>(
                entry.slug(),
                entry.effective(),
                entry.shipped(),
                entry.overridden(),
                entry.acceptedBundledDigest(),
                entry.retired(),
                position,
                entry.updatedAt());
    }
}
