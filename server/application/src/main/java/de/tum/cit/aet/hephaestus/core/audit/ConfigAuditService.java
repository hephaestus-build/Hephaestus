package de.tum.cit.aet.hephaestus.core.audit;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditActorRefDTO;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntryViewDTO;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditFilter;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditQuery;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    public Page<ConfigAuditEntryViewDTO> listForWorkspace(
            Long workspaceId, ConfigAuditFilter filter, Pageable pageable) {
        return withIdentities(repository.findForWorkspace(workspaceId, filter, pageable));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ConfigAuditEntryViewDTO> listForAdmin(
            @Nullable Long workspaceId, ConfigAuditFilter filter, Pageable pageable) {
        return withIdentities(repository.findForAdmin(workspaceId, filter, pageable));
    }

    private Page<ConfigAuditEntryViewDTO> withIdentities(Page<ConfigAuditEvent> events) {
        Set<Long> ids = new HashSet<>();
        for (ConfigAuditEvent e : events.getContent()) {
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
        return events.map(e -> toView(e, identities));
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
