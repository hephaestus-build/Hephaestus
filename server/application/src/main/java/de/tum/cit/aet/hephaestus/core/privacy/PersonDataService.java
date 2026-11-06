package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonEvidenceErasure;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic("Verified instance-admin rights requests span all of a person's workspaces")
public class PersonDataService {
    private static final Logger log = LoggerFactory.getLogger(PersonDataService.class);
    private final PersonIdentityResolver resolver;
    private final PersonDataRegistry registry;
    private final List<PersonEvidenceErasure> evidenceStores;
    private final PersonDataRequestRepository requests;
    private final IdentityRestrictionService suppression;
    private final PersonDataWriteFence writeFence;
    private final PersonDataCopyFence copyFence;
    private final IssuedJwtRepository issuedTokens;
    private final PlatformTransactionManager transactions;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    public record Snapshot(
            PersonDataRequest request, List<PersonDataContributor.ExternalDelivery> externalDeliveries) {}

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Snapshot preview(Long administratorId, @Nullable Long accountId, List<PersonIdentity> identities) {
        PersonScope scope = withEvidenceJobs(resolver.resolve(accountId, identities));
        PersonDataRequest request = new PersonDataRequest();
        request.setAdministratorAccountId(administratorId);
        request.setScopeJson(mapper.writeValueAsString(scope));
        requests.saveAndFlush(request);
        var selected = registry.select(scope);
        request.setSelectionsJson(mapper.writeValueAsString(selected));
        request.setCountsJson(mapper.writeValueAsString(registry.counts(selected)));
        requests.saveAndFlush(request);
        return new Snapshot(request, deliveries(selected));
    }

    @Transactional(readOnly = true)
    public Snapshot get(UUID id) {
        PersonDataRequest r = require(id);
        return new Snapshot(r, r.getSelectionsJson() == null ? List.of() : deliveries(selections(r)));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public JsonNode export(UUID id) {
        PersonDataRequest r = require(id);
        requirePreview(r);
        var selected = selections(r);
        requireUnchanged(r, selected);
        var frozenScope = scope(r);
        var bundle = mapper.createObjectNode();
        bundle.put("schemaVersion", "1.0");
        bundle.put("generatedAt", Instant.now().toString());
        bundle.set("scope", mapper.valueToTree(frozenScope));
        var stores = bundle.putObject("stores");
        for (var store : registry.stores())
            stores.set(
                    store.store(),
                    mapper.valueToTree(store.export(Objects.requireNonNull(selected.get(store.store())), frozenScope)));
        return bundle;
    }

    @Transactional
    public void requestErasure(UUID id, long administratorId, boolean externalCopiesRemoved) {
        // Acquire global admission, native keys and accounts before the request row.
        // The unlocked snapshot contains only the immutable admission scope; revalidate after
        // locking.
        var admission = jdbc.query(
                "SELECT state,scope_json FROM person_data_request WHERE id=?",
                (rs, row) -> new ErasureAdmission(Objects.requireNonNull(rs.getString(1)), rs.getString(2)),
                id);
        if (admission.isEmpty()) throw notFound();
        var initial = admission.getFirst();
        if (initial.state().equals("COMPLETE") || initial.state().equals("ERASING")) return;
        if (!initial.state().equals("PREVIEW") && !initial.state().equals("FAILED"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This preview is no longer available.");
        PersonScope person = mapper.readValue(Objects.requireNonNull(initial.scopeJson()), PersonScope.class);
        if (Objects.equals(person.accountId(), administratorId))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Another instance admin must authorize this erasure.");
        copyFence.holdForErasure();
        writeFence.holdForErasure(person.identities());
        requireActiveAdministratorAndLockAccount(administratorId, person.accountId());
        PersonDataRequest r = requests.lock(id).orElseThrow(() -> notFound());
        if (r.getState() == PersonDataRequest.State.COMPLETE || r.getState() == PersonDataRequest.State.ERASING) return;
        if (r.getState() != PersonDataRequest.State.FAILED) requirePreview(r);
        if (!scope(r).equals(person))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The request scope changed. Create a new preview.");
        if (r.getState() == PersonDataRequest.State.FAILED) {
            requireExternalRemoval(selections(r), externalCopiesRemoved);
            r.setAdministratorAccountId(administratorId);
            r.setState(PersonDataRequest.State.ERASING);
            r.setFailureCode(null);
            return;
        }
        var selected = selections(r);
        requireUnchanged(r, selected);
        requireExternalRemoval(selected, externalCopiesRemoved);
        if (person.accountId() != null) {
            Boolean busy = jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM person_data_request WHERE id<>? AND state"
                            + " IN ('ERASING','FAILED') AND scope_json IS NOT NULL AND"
                            + " (scope_json::jsonb)->>'accountId'=?)",
                    Boolean.class,
                    id,
                    person.accountId().toString());
            if (Boolean.TRUE.equals(busy))
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "An erasure request already owns this account. Resume that request.");
        }
        suppression.suppress(person, id);
        if (person.accountId() != null) {
            jdbc.update(
                    "UPDATE account SET status='DELETING',deleted_at=CURRENT_TIMESTAMP WHERE id=?"
                            + " AND status<>'DELETED'",
                    person.accountId());
            issuedTokens.revokeAllForAccount(
                    person.accountId(), Instant.now(), IssuedJwt.RevokedReason.ACCOUNT_DELETED);
        }
        r.setAdministratorAccountId(administratorId);
        r.setState(PersonDataRequest.State.ERASING);
        r.setFailureCode(null);
    }

    private record ErasureAdmission(String state, @Nullable String scopeJson) {}

    private void requireActiveAdministratorAndLockAccount(long administratorId, @Nullable Long personAccountId) {
        // Lock both accounts in one order. Concurrent requests must not erase each other's
        // administrators after both have accepted a token validated before either erasure.
        List<Boolean> eligible = jdbc.query(
                "SELECT id,(status='ACTIVE' AND app_role='APP_ADMIN') FROM account WHERE id"
                        + " IN (?,?) ORDER BY id FOR NO KEY UPDATE",
                (rs, row) -> rs.getLong(1) == administratorId && rs.getBoolean(2),
                administratorId,
                Objects.requireNonNullElse(personAccountId, -1L));
        if (!eligible.contains(true))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "An active instance admin must authorize erasure.");
    }

    /**
     * Account deletion uses the same frozen store inventory and physical-copy custody as admin
     * erasure.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void eraseForAccount(long accountId) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        UUID requestId = tx.execute(status -> {
            PersonScope person = withEvidenceJobs(resolver.resolve(accountId, List.of()));
            copyFence.holdForErasure();
            writeFence.holdForErasure(person.identities());
            var accountStatus = jdbc.queryForObject(
                    "SELECT status FROM account WHERE id=? FOR NO KEY UPDATE", String.class, accountId);
            if ("DELETED".equals(accountStatus)) return null;
            var active = jdbc.query(
                    """
                    SELECT id FROM person_data_request WHERE state IN ('ERASING','FAILED')
                    AND scope_json IS NOT NULL AND (scope_json::jsonb)->>'accountId'=?
                    ORDER BY created_at FOR UPDATE
                    """, (rs, row) -> Objects.requireNonNull(rs.getObject(1, UUID.class)), Long.toString(accountId));
            if (!active.isEmpty()) return active.getFirst();
            var request = new PersonDataRequest();
            request.setScopeJson(mapper.writeValueAsString(person));
            var selected = registry.select(person);
            request.setSelectionsJson(mapper.writeValueAsString(selected));
            request.setCountsJson(mapper.writeValueAsString(registry.counts(selected)));
            request.setState(PersonDataRequest.State.ERASING);
            requests.saveAndFlush(request);
            suppression.suppress(person, request.getId());
            jdbc.update(
                    "UPDATE account SET"
                            + " status='DELETING',deleted_at=COALESCE(deleted_at,CURRENT_TIMESTAMP)"
                            + " WHERE id=?",
                    accountId);
            issuedTokens.revokeAllForAccount(accountId, Instant.now(), IssuedJwt.RevokedReason.ACCOUNT_DELETED);
            if (!deliveries(selected).isEmpty()) {
                request.setState(PersonDataRequest.State.FAILED);
                request.setFailureCode("EXTERNAL_COPIES_REQUIRE_REMOVAL");
                requests.saveAndFlush(request);
            }
            return request.getId();
        });
        if (requestId == null) return;
        runSteps(requestId);
        var state = Objects.requireNonNull(
                tx.execute(status -> requests.findById(requestId).orElseThrow().getState()));
        if (state != PersonDataRequest.State.COMPLETE)
            throw new IllegalStateException("Account erasure has unfinished copy or store custody: " + requestId);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void run(UUID id) {
        runSteps(id);
    }

    private void runSteps(UUID id) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        var admission = copyFence.erase();
        String activeStore = "inventory";
        try (admission) {
            Map<String, PersonDataSelection> frozen = tx.execute(status -> {
                PersonDataRequest r = lockAdmittedErasure(id);
                return r == null ? Map.<String, PersonDataSelection>of() : selections(r);
            });
            if (frozen == null || frozen.isEmpty()) return;
            Set<String> inventory = registry.stores().stream()
                    .map(PersonDataContributor::store)
                    .collect(Collectors.toUnmodifiableSet());
            if (!frozen.keySet().equals(inventory))
                throw new IllegalStateException("Erasure contributor inventory changed");
            for (var store : registry.stores()) {
                activeStore = store.store();
                store.prepareErasure(Objects.requireNonNull(frozen.get(store.store())));
            }
            for (var store : registry.stores()) {
                activeStore = store.store();
                Boolean proceed = tx.execute(status -> {
                    PersonDataRequest r = lockAdmittedErasure(id);
                    if (r == null) return false;
                    var completed = completed(r);
                    if (!inventory.containsAll(completed.keySet()))
                        throw new IllegalStateException("Erasure contributor inventory changed");
                    if (!completed.containsKey(store.store())) {
                        long count = store.erase(Objects.requireNonNull(frozen.get(store.store())), scope(r));
                        // The database clock, which every runtime shares: PersonDataCopyFence.erasedSince compares
                        // it with the start of a Heph turn, which is on the database clock too.
                        Instant erasedAt = Objects.requireNonNull(
                                        jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class))
                                .toInstant();
                        completed.put(
                                store.store(),
                                new PersonDataStoreReceipt(count, erasedAt, r.getAdministratorAccountId()));
                        r.setCompletedJson(mapper.writeValueAsString(completed));
                        requests.saveAndFlush(r);
                    }
                    return true;
                });
                if (!Boolean.TRUE.equals(proceed)) return;
            }
            tx.executeWithoutResult(status -> {
                PersonDataRequest r = requests.lock(id).orElseThrow(() -> notFound());
                if (r.getState() != PersonDataRequest.State.ERASING) return;
                suppression.release(id);
                r.setState(PersonDataRequest.State.COMPLETE);
                r.setCompletedAt(Instant.now());
                r.setScopeJson(null);
                r.setSelectionsJson(null);
                r.setFailureCode(null);
                requests.saveAndFlush(r);
            });
        } catch (RuntimeException exception) {
            boolean temporary = isTemporaryFailure(exception);
            @Nullable Boolean retry = null;
            try {
                retry = tx.execute(status -> {
                    var r = requests.lock(id).orElseThrow(() -> notFound());
                    if (r.getState() != PersonDataRequest.State.ERASING) return false;
                    boolean retryPending = temporary && !"STORE_ERASURE_RETRY_PENDING".equals(r.getFailureCode());
                    if (!retryPending) r.setState(PersonDataRequest.State.FAILED);
                    r.setFailureCode(retryPending ? "STORE_ERASURE_RETRY_PENDING" : "STORE_ERASURE_FAILED");
                    requests.saveAndFlush(r);
                    return retryPending;
                });
            } finally {
                var event = Boolean.TRUE.equals(retry) ? log.atWarn() : log.atError();
                event.addKeyValue("event.name", "person_data.erasure.failed")
                        .addKeyValue("person_data.request.id", id)
                        .addKeyValue("person_data.store", activeStore)
                        .addKeyValue("error.type", exception.getClass().getName())
                        .addKeyValue("person_data.failure_recorded", retry != null)
                        .log(
                                retry == null
                                        ? "Person data erasure status could not be recorded; check database availability"
                                        : Boolean.TRUE.equals(retry)
                                                ? "Person data erasure will retry once after a temporary store failure"
                                                : "Person data erasure stopped; an instance admin must resume it");
            }
        }
    }

    private static boolean isTemporaryFailure(RuntimeException failure) {
        @Nullable Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof TransientDataAccessException
                    || cause instanceof RecoverableDataAccessException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof CannotCreateTransactionException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    @Transactional
    public void expirePreviews() {
        jdbc.update("UPDATE person_data_request SET"
                + " state='EXPIRED',scope_json=NULL,selections_json=NULL WHERE state='PREVIEW'"
                + " AND expires_at<CURRENT_TIMESTAMP");
    }

    private void requireExternalRemoval(Map<String, PersonDataSelection> selected, boolean externalCopiesRemoved) {
        if (!externalCopiesRemoved && !deliveries(selected).isEmpty())
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Use the un-deliver runbook to remove the provider feedback. Then confirm the" + " removal.");
    }

    private void requireUnchanged(PersonDataRequest r, Map<String, PersonDataSelection> selected) {
        PersonScope scope = scope(r);
        if (!scope.equals(withEvidenceJobs(resolver.resolve(scope.accountId(), scope.identities())))
                || !selected.equals(registry.select(scope)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The preview scope changed. Create a new preview.");
    }

    private PersonScope withEvidenceJobs(PersonScope resolved) {
        var derivedJobs = Stream.concat(
                        resolved.derivedJobIds().stream(),
                        evidenceStores.stream().flatMap(store -> store.jobsContaining(resolved).stream()))
                .distinct()
                .sorted()
                .toList();
        return new PersonScope(
                resolved.accountId(),
                resolved.identities(),
                resolved.userIds(),
                resolved.conversationIds(),
                resolved.outlineDocumentIds(),
                resolved.scmArtifactIds(),
                derivedJobs);
    }

    private PersonScope scope(PersonDataRequest r) {
        return mapper.readValue(Objects.requireNonNull(r.getScopeJson()), PersonScope.class);
    }

    private Map<String, PersonDataSelection> selections(PersonDataRequest r) {
        return mapper.readValue(
                Objects.requireNonNull(r.getSelectionsJson()),
                new TypeReference<Map<String, PersonDataSelection>>() {});
    }

    private @Nullable PersonDataRequest lockAdmittedErasure(UUID id) {
        var actors = jdbc.query(
                "SELECT administrator_account_id,scope_json FROM person_data_request WHERE"
                        + " id=? AND state='ERASING'",
                (rs, row) -> new StepAdmission(rs.getObject(1, Long.class), rs.getString(2)),
                id);
        if (actors.isEmpty()) return null;
        var actor = actors.getFirst();
        var person = mapper.readValue(Objects.requireNonNull(actor.scopeJson()), PersonScope.class);
        if (actor.administratorAccountId() != null) {
            requireActiveAdministratorAndLockAccount(actor.administratorAccountId(), person.accountId());
        } else {
            long accountId = Objects.requireNonNull(person.accountId());
            var accountStatus = jdbc.queryForObject(
                    "SELECT status FROM account WHERE id=? FOR NO KEY UPDATE", String.class, accountId);
            if (!"DELETING".equals(accountStatus) && !"DELETED".equals(accountStatus))
                throw new IllegalStateException("Account deletion admission is no longer valid");
        }
        PersonDataRequest request = requests.lock(id).orElseThrow(() -> notFound());
        if (request.getState() != PersonDataRequest.State.ERASING) return null;
        if (!Objects.equals(request.getAdministratorAccountId(), actor.administratorAccountId())
                || !scope(request).equals(person)) throw new IllegalStateException("Erasure admission changed");
        return request;
    }

    private record StepAdmission(
            @Nullable Long administratorAccountId, @Nullable String scopeJson) {}

    private Map<String, PersonDataStoreReceipt> completed(PersonDataRequest r) {
        return PersonDataStoreReceipt.read(mapper, r.getCompletedJson());
    }

    private List<PersonDataContributor.ExternalDelivery> deliveries(Map<String, PersonDataSelection> selected) {
        return registry.stores().stream()
                .flatMap(s -> s.externalDeliveries(Objects.requireNonNull(selected.get(s.store()))).stream())
                .distinct()
                .toList();
    }

    private PersonDataRequest require(UUID id) {
        return requests.findById(id).orElseThrow(() -> notFound());
    }

    private void requirePreview(PersonDataRequest r) {
        if (r.getState() != PersonDataRequest.State.PREVIEW || r.getExpiresAt().isBefore(Instant.now()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This preview is no longer available.");
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "We could not find that request. It may have been deleted. Reload the page to see"
                        + " what is current.");
    }
}
