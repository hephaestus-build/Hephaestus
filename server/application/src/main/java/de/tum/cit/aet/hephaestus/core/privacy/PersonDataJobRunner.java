package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic("Instance-wide resumable rights-request backlog")
class PersonDataJobRunner {
    private final JdbcTemplate jdbc;
    private final PersonDataService service;

    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(name = "personDataErasure", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1S")
    public void run() {
        service.expirePreviews();
        for (UUID id : jdbc.query(
                "SELECT id FROM person_data_request WHERE state='ERASING' ORDER BY created_at LIMIT 10",
                (rs, row) -> rs.getObject(1, UUID.class))) service.run(id);
    }
}
