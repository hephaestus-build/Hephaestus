package de.tum.cit.aet.hephaestus.core.auth.audit;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.TimeIdCursor;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the {@code auth_event} audit log for the instance-admin viewer. Thin query service so
 * the controller delegates rather than touching the repository directly. Auth events are
 * account/system-scoped (not workspace-scoped), hence {@link WorkspaceAgnostic}.
 *
 * <p>Resolves the subject + actor account ids on a page to human identities in a single batch lookup,
 * so the viewer shows names/emails rather than bare primary keys. Audit rows outlive accounts (deletion,
 * GDPR redaction), so a missing account resolves to {@code null} and the UI falls back to the id.
 */
@ConditionalOnServerRole
@Service
@WorkspaceAgnostic("Auth audit events are account/system-scoped, not workspace-scoped")
public class AuthAuditService {

    private final AuthEventRepository authEventRepository;
    private final AccountRepository accountRepository;

    public AuthAuditService(AuthEventRepository authEventRepository, AccountRepository accountRepository) {
        this.authEventRepository = authEventRepository;
        this.accountRepository = accountRepository;
    }

    /** Audit-viewer filters; every field null = unfiltered. */
    public record Filter(
            @Nullable Long accountId,
            @Nullable Long actingAccountId,
            @Nullable List<AuthEvent.EventType> eventTypes,
            @Nullable List<AuthEvent.Result> results,
            @Nullable Instant from,
            @Nullable Instant to) {
        /**
         * Hibernate cannot bind an empty {@code IN} list, and an empty selection means the same thing
         * as an absent one, so both collapse to null before reaching the query.
         */
        public Filter {
            eventTypes = nullIfEmpty(eventTypes);
            results = nullIfEmpty(results);
        }

        private static <T> @Nullable List<T> nullIfEmpty(@Nullable List<T> values) {
            return values == null || values.isEmpty() ? null : values;
        }
    }

    /** A human-readable account identity for an audit row. {@code displayName}/{@code email} may be null. */
    public record AccountRef(
            long id, @Nullable String displayName, @Nullable String email) {}

    /** A page of audit events plus the resolved identities for the accounts referenced on that page. */
    public record AuditPage(List<AuthEvent> events, Map<Long, AccountRef> identities) {}

    /** Up to {@code limit} auth events newest-first after {@code after}, with their account identities resolved. */
    @Transactional(readOnly = true)
    public AuditPage list(Filter filter, @Nullable TimeIdCursor after, int limit) {
        List<AuthEvent> events = authEventRepository.findForAdmin(
                filter.accountId(),
                filter.actingAccountId(),
                filter.eventTypes(),
                filter.results(),
                filter.from(),
                filter.to(),
                after == null ? null : after.at(),
                after == null ? null : after.id(),
                PageRequest.ofSize(limit));
        Set<Long> ids = new HashSet<>();
        for (AuthEvent e : events) {
            if (e.getAccountId() != null) {
                ids.add(e.getAccountId());
            }
            if (e.getActingAccountId() != null) {
                ids.add(e.getActingAccountId());
            }
        }
        Map<Long, AccountRef> identities = ids.isEmpty()
                ? Map.of()
                : accountRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(
                                a -> Objects.requireNonNull(a.getId()),
                                a -> new AccountRef(
                                        Objects.requireNonNull(a.getId()), a.getDisplayName(), a.getPrimaryEmail()),
                                (a, b) -> a));
        return new AuditPage(events, identities);
    }

    /** How many auth events the filter matches. */
    @Transactional(readOnly = true)
    public long count(Filter filter) {
        return authEventRepository.countForAdmin(
                filter.accountId(),
                filter.actingAccountId(),
                filter.eventTypes(),
                filter.results(),
                filter.from(),
                filter.to());
    }

    /** Resolve an id (subject or actor) to its identity; null id or unknown account → null. */
    public static @Nullable AccountRef refOf(@Nullable Long id, Map<Long, AccountRef> identities) {
        return id == null ? null : identities.get(id);
    }
}
