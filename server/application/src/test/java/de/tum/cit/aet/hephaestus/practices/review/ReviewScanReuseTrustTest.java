package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.practices.spi.HostCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceiptStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewScanReuseTrustTest extends BaseUnitTest {
    HostCoverage coverage(ReadReceiptStatus status, String digest, boolean complete) {
        return new HostCoverage(
                complete,
                List.of(),
                1,
                0,
                0,
                List.of(new ReadReceipt(
                        new SourceKind("scm.repository.tree"),
                        "inputs/tree",
                        "file",
                        "commit",
                        status,
                        digest,
                        null,
                        null,
                        null)));
    }

    @Test
    void completeTextAndAttestedAbsenceAreReusable() {
        assertThat(ScanReuseCandidateQuery.trusted(coverage(ReadReceiptStatus.COMPLETE, "a".repeat(64), true)))
                .isTrue();
        assertThat(ScanReuseCandidateQuery.trusted(coverage(ReadReceiptStatus.ABSENT, "", true)))
                .isTrue();
    }

    @Test
    void missingDigestFailedBinaryAndTruncatedReadsAreNotReusable() {
        assertThat(ScanReuseCandidateQuery.trusted(coverage(ReadReceiptStatus.COMPLETE, "", true)))
                .isFalse();
        for (var status : List.of(ReadReceiptStatus.FAILED, ReadReceiptStatus.TRUNCATED, ReadReceiptStatus.BINARY))
            assertThat(ScanReuseCandidateQuery.trusted(coverage(status, "a".repeat(64), true)))
                    .isFalse();
        assertThat(ScanReuseCandidateQuery.trusted(coverage(ReadReceiptStatus.COMPLETE, "a".repeat(64), false)))
                .isFalse();
    }
}
