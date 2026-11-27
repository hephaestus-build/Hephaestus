package de.tum.cit.aet.hephaestus.integration.slack.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface SlackDmProducerPinRepository extends Repository<SlackDmProducerPin, Long> {

    Optional<SlackDmProducerPin> findByWorkspaceIdAndConnectionIdAndChannelIdAndSourceTs(
            long workspaceId, long connectionId, String channelId, String sourceTs);

    SlackDmProducerPin save(SlackDmProducerPin pin);

    @Modifying
    @Query("DELETE FROM SlackDmProducerPin p WHERE p.workspaceId = :workspaceId")
    int deleteByWorkspaceId(@Param("workspaceId") long workspaceId);

    @Modifying
    @Query("DELETE FROM SlackDmProducerPin p WHERE p.workspaceId = :workspaceId AND p.sourceTs < :cutoff")
    int deleteOlderThan(@Param("workspaceId") long workspaceId, @Param("cutoff") String cutoff);
}
