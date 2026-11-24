package de.tum.cit.aet.hephaestus.core.privacy;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import de.tum.cit.aet.hephaestus.architecture.HephaestusArchitectureTest;
import org.junit.jupiter.api.Test;

class PrivacyJournalStorageArchitectureTest extends HephaestusArchitectureTest {

    private static final ArchRule SDK_STAYS_IN_ADAPTER = noClasses()
            .that()
            .areInterfaces()
            .or()
            .areNotAssignableTo(PrivacyJournalObjectStore.class)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("software.amazon.awssdk..")
            .because("only the privacy journal store owns the S3 client and credentials");

    @Test
    void onlyTheJournalStoreMayDependOnTheAwsSdk() {
        assertThat(classes.stream()
                        .filter(type -> !type.isInterface() && type.isAssignableTo(PrivacyJournalObjectStore.class))
                        .toList())
                .hasSize(1);
        SDK_STAYS_IN_ADAPTER.check(classes);
    }

    @Test
    void rejectsAnSdkDependencyOutsideTheAdapter() {
        var fixture = new ClassFileImporter().importClasses(AnotherS3Client.class, StoreWithSdk.class);
        assertThatThrownBy(() -> SDK_STAYS_IN_ADAPTER.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("AnotherS3Client")
                .hasMessageContaining("StoreWithSdk");
    }

    static final class AnotherS3Client {

        // The deliberate SDK dependency belongs only to this negative fixture.
        @SuppressWarnings("UnnecessarilyFullyQualified")
        String region() {
            return software.amazon.awssdk.regions.Region.US_EAST_1.id();
        }
    }

    interface StoreWithSdk extends PrivacyJournalObjectStore {

        @SuppressWarnings("UnnecessarilyFullyQualified")
        default String configuredRegion() {
            return software.amazon.awssdk.regions.Region.US_EAST_1.id();
        }
    }
}
