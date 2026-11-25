package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@WorkspaceAgnostic("A person's contact meters count across every workspace (ADR 0139 decision 4)")
interface AccountMeterRepository extends JpaRepository<AccountMeter, AccountMeter.Id> {

    @Query("""
            SELECT new de.tum.cit.aet.hephaestus.conversation.ContactFacts$MeterReading(m.localDate, m.count, m.lastAt)
            FROM AccountMeter m
            WHERE m.id.accountId = :accountId AND m.id.meter = :meter
            """)
    Optional<ContactFacts.MeterReading> findReading(
            @Param("accountId") long accountId, @Param("meter") AccountMeter.Meter meter);
}
