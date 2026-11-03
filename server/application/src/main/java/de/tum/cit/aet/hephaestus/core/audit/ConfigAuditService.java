package de.tum.cit.aet.hephaestus.core.audit;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditActorRefDTO;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntryViewDTO;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditFilter;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditFilterParams;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditQuery;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.web.CursorPageDTO;
import de.tum.cit.aet.hephaestus.core.web.TimeIdCursor;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the config audit trail; resolves the page's actor ids to identities in one batch. */
@Service
@RequiredArgsConstructor
class ConfigAuditService implements ConfigAuditQuery {

    private final ConfigAuditEventRepository repository;
    private final AccountRepository accountRepository;

    @Override
    @Transactional(readOnly = true)
    public CursorPageDTO<ConfigAuditEntryViewDTO> listForWorkspace(
            Long workspaceId, ConfigAuditFilter filter, @Nullable String cursor, int size) {
        int capped = ConfigAuditFilterParams.pageSize(size);
        TimeIdCursor after = TimeIdCursor.decode(cursor);
        return page(
                repository.findForWorkspace(
                        workspaceId,
                        filter,
                        after == null ? null : after.at(),
                        after == null ? null : after.id(),
                        PageRequest.ofSize(capped + 1)),
                capped);
    }

    @Override
    @Transactional(readOnly = true)
    public long countForWorkspace(Long workspaceId, ConfigAuditFilter filter) {
        return repository.countForWorkspace(workspaceId, filter);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageDTO<ConfigAuditEntryViewDTO> listForAdmin(
            @Nullable Long workspaceId, ConfigAuditFilter filter, @Nullable String cursor, int size) {
        int capped = ConfigAuditFilterParams.pageSize(size);
        TimeIdCursor after = TimeIdCursor.decode(cursor);
        return page(
                repository.findForAdmin(
                        workspaceId,
                        filter,
                        after == null ? null : after.at(),
                        after == null ? null : after.id(),
                        PageRequest.ofSize(capped + 1)),
                capped);
    }

    @Override
    @Transactional(readOnly = true)
    public long countForAdmin(@Nullable Long workspaceId, ConfigAuditFilter filter) {
        return repository.countForAdmin(workspaceId, filter);
    }

    private CursorPageDTO<ConfigAuditEntryViewDTO> page(List<ConfigAuditEvent> read, int size) {
        boolean more = read.size() > size;
        List<ConfigAuditEvent> events = read.subList(0, Math.min(size, read.size()));
        return new CursorPageDTO<>(
                withIdentities(events),
                more
                        ? new TimeIdCursor(
                                        events.getLast().getOccurredAt(),
                                        Objects.requireNonNull(events.getLast().getId()))
                                .encode()
                        : null);
    }

    private List<ConfigAuditEntryViewDTO> withIdentities(List<ConfigAuditEvent> events) {
        Set<Long> ids = new HashSet<>();
        for (ConfigAuditEvent e : events) {
            if (e.getActorAccountId() != null) {
                ids.add(e.getActorAccountId());
            }
            if (e.getActingAccountId() != null) {
                ids.add(e.getActingAccountId());
            }
        }
        Map<Long, ConfigAuditActorRefDTO> identities = ids.isEmpty()
                ? Map.of()
                : accountRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(
                                a -> Objects.requireNonNull(a.getId()),
                                a -> new ConfigAuditActorRefDTO(
                                        Objects.requireNonNull(a.getId()), a.getDisplayName(), a.getPrimaryEmail()),
                                (a, b) -> a));
        return events.stream().map(e -> toView(e, identities)).toList();
    }

    private static ConfigAuditEntryViewDTO toView(ConfigAuditEvent e, Map<Long, ConfigAuditActorRefDTO> identities) {
        return new ConfigAuditEntryViewDTO(
                e.getId(),
                e.getOccurredAt(),
                e.getWorkspaceId(),
                e.getEntityType(),
                e.getEntityId(),
                e.getAction(),
                e.getActorKind(),
                e.isElevatedViaInstanceAdmin(),
                e.getActorAccountId(),
                e.getActingAccountId(),
                refOf(e.getActorAccountId(), identities),
                refOf(e.getActingAccountId(), identities),
                e.changedKeyList(),
                e.getOldValue(),
                e.getNewValue());
    }

    private static @Nullable ConfigAuditActorRefDTO refOf(
            @Nullable Long id, Map<Long, ConfigAuditActorRefDTO> identities) {
        return id == null ? null : identities.get(id);
    }
}
