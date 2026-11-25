package de.tum.cit.aet.hephaestus.conversation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CheckInRepository extends JpaRepository<CheckIn, UUID> {

    @Query("""
            SELECT new de.tum.cit.aet.hephaestus.conversation.ContactFacts$AgreedScope(c.kind, c.topic, c.place)
            FROM CheckIn c
            WHERE c.id = :id AND c.workspaceId = :workspaceId AND c.accountId = :accountId
              AND c.state = de.tum.cit.aet.hephaestus.conversation.CheckIn$State.ACTIVE
            """)
    Optional<ContactFacts.AgreedScope> findActiveScope(
            @Param("id") UUID id, @Param("workspaceId") long workspaceId, @Param("accountId") long accountId);
}
