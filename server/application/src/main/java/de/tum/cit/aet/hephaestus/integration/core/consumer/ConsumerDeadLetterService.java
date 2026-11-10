package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.CursorPageDTO;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Lists the provider events that failed for good and wait for a replay. */
@Service
@ConditionalOnServerRole
public class ConsumerDeadLetterService {
    private static final int MAX_PAGE = 100;

    private final ProviderEventStore store;

    public ConsumerDeadLetterService(ProviderEventStore store) {
        this.store = store;
    }

    @Transactional(readOnly = true)
    public CursorPageDTO<ConsumerDeadLetterDTO> listWorkspace(Long workspaceId, @Nullable String cursor, int size) {
        int capped = Math.clamp(size, 1, MAX_PAGE);
        return page(store.deadLetters(workspaceId, idAfter(cursor), capped + 1), capped);
    }

    @Transactional(readOnly = true)
    @WorkspaceAgnostic("Instance administrators can read only unassigned installation rows (workspace IS NULL)")
    public CursorPageDTO<ConsumerDeadLetterDTO> listInstallation(@Nullable String cursor, int size) {
        int capped = Math.clamp(size, 1, MAX_PAGE);
        return page(store.deadLetters(null, idAfter(cursor), capped + 1), capped);
    }

    private static CursorPageDTO<ConsumerDeadLetterDTO> page(List<ProviderEventStore.DeadLetter> rows, int size) {
        boolean more = rows.size() > size;
        var page = rows.subList(0, Math.min(size, rows.size()));
        return new CursorPageDTO<>(
                page.stream().map(ConsumerDeadLetterDTO::from).toList(),
                more ? String.valueOf(page.getLast().id()) : null);
    }

    private static long idAfter(@Nullable String cursor) {
        if (cursor == null) {
            return 0;
        }
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The cursor is not valid.", e);
        }
    }
}
