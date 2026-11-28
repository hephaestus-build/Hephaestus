package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.exception.JournalPendingException;
import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
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
    private final PersonDataScopeResolver scopes;
    private final PersonDataRegistry registry;
    private final PersonDataRequestRepository requests;
    private final PersonDataErasureHold hold;
    private final IssuedJwtRepository issuedTokens;
    private final PlatformTransactionManager transactions;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    private final PrivacyJournal journal;
    private final PrivacyJournalShipping shipping;

    @PostConstruct
    void registerFailureCounter() {
        meters.counter(CoreMetrics.PERSON_DATA_ERASURE_FAILURES);
        meters.counter(CoreMetrics.PERSON_DATA_ERASURE_DEFERRALS);
    }

    public record Snapshot(
            PersonDataRequest request, List<PersonDataContributor.ExternalDelivery> externalDeliveries) {}

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Snapshot preview(Long administratorId, @Nullable Long accountId, List<PersonIdentity> identities) {
        PersonScope scope = scopes.resolve(accountId, identities);
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

    /**
     * The preview's frozen records, in the caller's transaction, for the store tests. No route returns them: an
     * instance admin never downloads a person's data (ADR 0164).
     */
    JsonNode bundle(UUID id) {
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
        if (initial.state().equals("COMPLETE") || initial.state().equals("ERASING")) {
            shipping.pendingErasureReply(id);
            return;
        }
        if (!initial.state().equals("PREVIEW") && !initial.state().equals("FAILED"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This preview is no longer available.");
        PersonScope person = mapper.readValue(Objects.requireNonNull(initial.scopeJson()), PersonScope.class);
        if (Objects.equals(person.accountId(), administratorId))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Another instance admin must authorize this erasure.");
        hold.acquire(person);
        requireActiveAdministratorAndLockAccount(administratorId, person.accountId());
        PersonDataRequest r = requests.lock(id).orElseThrow(() -> notFound());
        if (r.getState() == PersonDataRequest.State.COMPLETE || r.getState() == PersonDataRequest.State.ERASING) {
            shipping.pendingErasureReply(id);
            return;
        }
        if (r.getState() != PersonDataRequest.State.FAILED) requirePreview(r);
        if (!scope(r).equals(person))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The request scope changed. Create a new preview.");
        if (r.getState() == PersonDataRequest.State.FAILED) {
            requireExternalRemoval(selections(r), externalCopiesRemoved);
            if (externalCopiesRemoved && "EXTERNAL_COPIES_REQUIRE_REMOVAL".equals(r.getFailureCode())) {
                ScopedValue.where(PrivacyJournalShipping.ERASURE_REQUEST, id)
                        .run(() -> journal.record(PrivacyJournalKind.PERSON_ERASED, erasureReferences(person, true)));
            }
            r.startErasure(administratorId);
            shipping.pendingErasureReply(id);
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
        hold.suppress(person, id, externalCopiesRemoved);
        if (person.accountId() != null) {
            jdbc.update(
                    "UPDATE account SET status='DELETING',deleted_at=CURRENT_TIMESTAMP,version=version+1"
                            + " WHERE id=? AND status<>'DELETED'",
                    person.accountId());
            issuedTokens.revokeAllForAccount(
                    person.accountId(), Instant.now(), IssuedJwt.RevokedReason.ACCOUNT_DELETED);
        }
        r.startErasure(administratorId);
    }

    private Map<String, Object> erasureReferences(PersonScope person, boolean externalCopiesRemoved) {
        Map<String, Object> refs = new HashMap<>();
        if (person.accountId() != null) refs.put("accountId", person.accountId());
        refs.put("identities", person.identities());
        refs.put("externalCopiesRemoved", externalCopiesRemoved);
        return refs;
    }

    private record FrozenErasure(
            Map<String, PersonDataSelection> selections, PersonScope scope, Set<String> receipts) {}

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

    @Transactional(propagation = Propagation.MANDATORY)
    @Nullable
    public PersonDataRequest admitAccountErasure(long accountId) {
        return admitAccount(accountId);
    }

    private @Nullable PersonDataRequest admitAccount(long accountId) {
        PersonScope person = scopes.resolve(accountId, List.of());
        hold.acquire(person);
        var accountStatus =
                jdbc.queryForObject("SELECT status FROM account WHERE id=? FOR NO KEY UPDATE", String.class, accountId);
        var active = jdbc.query(
                """
                SELECT id FROM person_data_request WHERE state IN ('ERASING','FAILED')
                AND scope_json IS NOT NULL AND (scope_json::jsonb)->>'accountId'=?
                ORDER BY created_at FOR UPDATE
                """, (rs, row) -> Objects.requireNonNull(rs.getObject(1, UUID.class)), Long.toString(accountId));
        if (!active.isEmpty()) {
            var id = active.getFirst();
            shipping.pendingErasureReply(id);
            return require(id);
        }
        if ("DELETED".equals(accountStatus)) return null;

        var request = new PersonDataRequest();
        request.setScopeJson(mapper.writeValueAsString(person));
        var selected = registry.select(person);
        request.setSelectionsJson(mapper.writeValueAsString(selected));
        request.setCountsJson(mapper.writeValueAsString(registry.counts(selected)));
        request.startErasure(null);
        requests.saveAndFlush(request);
        hold.suppress(person, request.getId(), false);
        if (!deliveries(selected).isEmpty()) {
            request.fail("EXTERNAL_COPIES_REQUIRE_REMOVAL");
            requests.saveAndFlush(request);
        }
        return request;
    }

    /** Resumes accepted deletion off the request thread. Older deletions may still need their first frozen request. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void eraseForAccount(long accountId) {
        if (shipping.accountErasurePending(accountId)) throw new JournalPendingException();
        var tx = new TransactionTemplate(transactions);
        var request = tx.execute(status -> {
            var admitted = admitAccount(accountId);
            // A direct purge can admit an active account without going through the HTTP deletion admission.
            if (admitted != null
                    && jdbc.update(
                                    "UPDATE account SET status='DELETING',"
                                            + "deleted_at=COALESCE(deleted_at,CURRENT_TIMESTAMP),version=version+1"
                                            + " WHERE id=? AND status NOT IN ('DELETING','DELETED')",
                                    accountId)
                            != 0) {
                issuedTokens.revokeAllForAccount(accountId, Instant.now(), IssuedJwt.RevokedReason.ACCOUNT_DELETED);
            }
            return admitted;
        });
        if (request == null) return;
        var id = request.getId();
        runSteps(id, false);
        var state = Objects.requireNonNull(tx.execute(status -> require(id).getState()));
        if (state != PersonDataRequest.State.COMPLETE) {
            throw new IllegalStateException("Account erasure has not completed");
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void run(UUID id) {
        try {
            runSteps(id, false);
        } catch (JournalPendingException pending) {
            // The committed store receipt and its privacy facts will be retried independently.
        }
    }

    /** The recovery role replays as an isolated process with the shipment schedule disabled. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void runRecovery(UUID id) {
        runSteps(id, true);
    }

    private void requireJournalProof(UUID id, boolean recovery) {
        if (recovery) shipping.confirmRecoveryErasure(id);
        else if (shipping.erasurePending(id)) throw new JournalPendingException();
    }

    private void runSteps(UUID id, boolean recovery) {
        requireJournalProof(id, recovery);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        String activeStore = "inventory";
        try {
            var admission = hold.erase();
            try (admission) {
                // Another pass can commit a store receipt and a new fact while this pass waits for the lease.
                requireJournalProof(id, recovery);
                var admitted = tx.execute(status -> {
                    PersonDataRequest r = lockAdmittedErasure(id);
                    return r == null
                            ? null
                            : new FrozenErasure(
                                    selections(r),
                                    scope(r),
                                    Set.copyOf(completed(r).keySet()));
                });
                if (admitted == null) return;
                Map<String, PersonDataSelection> frozen = admitted.selections();
                Set<String> inventory = registry.stores().stream()
                        .map(PersonDataContributor::store)
                        .collect(Collectors.toUnmodifiableSet());
                // A replica of a newer release froze or erased a store that this replica does not know. Leave the
                // request to that release instead of failing it, so a rolling release never stops an erasure (#5382).
                if (deferredToNewerRelease(id, inventory, frozen.keySet(), admitted.receipts())) return;
                if (frozen.isEmpty() || !frozen.keySet().equals(inventory))
                    throw new IllegalStateException("Erasure contributor inventory changed");
                for (var store : registry.stores()) {
                    activeStore = store.store();
                    store.prepareErasure(Objects.requireNonNull(frozen.get(store.store())), admitted.scope());
                }
                for (var store : registry.stores()) {
                    activeStore = store.store();
                    Boolean proceed = tx.execute(status -> {
                        PersonDataRequest r = lockAdmittedErasure(id);
                        if (r == null) return false;
                        var completed = completed(r);
                        // A newer replica erased a store of its own while this pass ran; leave the rest to it.
                        if (deferredToNewerRelease(id, inventory, Set.of(), completed.keySet())) return false;
                        if (!completed.containsKey(store.store()) || !store.selectionMustStayUnchanged()) {
                            long count = ScopedValue.where(PrivacyJournalShipping.ERASURE_REQUEST, id)
                                    .call(() ->
                                            store.erase(Objects.requireNonNull(frozen.get(store.store())), scope(r)));
                            // The database clock, which every runtime shares: PersonDataCopyFence.erasedSince
                            // compares
                            // it with the start of a Hephaestus turn, which is on the database clock too.
                            Instant erasedAt = Objects.requireNonNull(
                                            jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class))
                                    .toInstant();
                            var previous = completed.get(store.store());
                            long total = count + (previous == null ? 0 : previous.count());
                            completed.put(
                                    store.store(),
                                    new PersonDataStoreReceipt(total, erasedAt, r.getAdministratorAccountId()));
                            r.setCompletedJson(mapper.writeValueAsString(completed));
                            requests.saveAndFlush(r);
                        }
                        return true;
                    });
                    if (!Boolean.TRUE.equals(proceed)) return;
                    requireJournalProof(id, recovery);
                }
                tx.executeWithoutResult(status -> {
                    PersonDataRequest r = requests.lock(id).orElseThrow(() -> notFound());
                    if (r.getState() != PersonDataRequest.State.ERASING) return;
                    var receipts = completed(r).keySet();
                    // Only a replica that knows every store with a receipt may complete the request.
                    if (deferredToNewerRelease(id, inventory, Set.of(), receipts)) return;
                    if (!receipts.equals(inventory))
                        throw new IllegalStateException("Erasure store receipts are incomplete");
                    hold.release(id);
                    r.complete(Instant.now());
                    requests.saveAndFlush(r);
                });
            }
        } catch (JournalPendingException pending) {
            throw pending;
        } catch (RuntimeException exception) {
            boolean temporary = isTemporaryFailure(exception);
            PersonDataRequest.@Nullable State failureState = null;
            try {
                failureState = tx.execute(status -> {
                    var r = requests.lock(id).orElseThrow(() -> notFound());
                    if (r.getState() != PersonDataRequest.State.ERASING) return r.getState();
                    boolean retryPending = temporary && !"STORE_ERASURE_RETRY_PENDING".equals(r.getFailureCode());
                    if (retryPending) r.setFailureCode("STORE_ERASURE_RETRY_PENDING");
                    else r.fail("STORE_ERASURE_FAILED");
                    requests.saveAndFlush(r);
                    return r.getState();
                });
            } finally {
                boolean retry = failureState == PersonDataRequest.State.ERASING;
                boolean failed = failureState == null || failureState == PersonDataRequest.State.FAILED;
                if (failed)
                    meters.counter(CoreMetrics.PERSON_DATA_ERASURE_FAILURES).increment();
                var event = failed ? log.atError() : log.atWarn();
                event.addKeyValue(
                                "event.name",
                                retry
                                        ? "person_data.erasure.retry_pending"
                                        : failed ? "person_data.erasure.failed" : "person_data.erasure.cleanup_failed")
                        .addKeyValue("person_data.request.id", id)
                        .addKeyValue("person_data.store", activeStore)
                        .addKeyValue("error.type", exception.getClass().getName())
                        .addKeyValue("person_data.failure_recorded", failureState != null)
                        .log(
                                failureState == null
                                        ? "Person data erasure status could not be recorded; check database"
                                                + " availability"
                                        : retry
                                                ? "Person data erasure will retry once after a temporary store failure"
                                                : failed
                                                        ? "Person data erasure stopped; an instance admin must resume"
                                                                + " it"
                                                        : "Person data erasure pass cleanup failed; check the request"
                                                                + " state");
            }
        }
    }

    private static boolean isTemporaryFailure(RuntimeException failure) {
        @Nullable Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof TransientDataAccessException
                    || cause instanceof RecoverableDataAccessException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof CannotCreateTransactionException
                    || cause instanceof SQLTransientException
                    || cause instanceof SQLRecoverableException
                    || (cause instanceof SQLException sql
                            && sql.getSQLState() != null
                            && (sql.getSQLState().startsWith("08")
                                    || sql.getSQLState().startsWith("53")
                                    || sql.getSQLState().startsWith("57")))) {
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
        if (!scope.equals(scopes.resolve(scope.accountId(), scope.identities()))
                || !registry.unchanged(scope, selected))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The preview scope changed. Create a new preview.");
    }

    private PersonScope scope(PersonDataRequest r) {
        return mapper.readValue(Objects.requireNonNull(r.getScopeJson()), PersonScope.class);
    }

    private Map<String, PersonDataSelection> selections(PersonDataRequest r) {
        Map<String, PersonDataSelection> selected = mapper.readValue(
                Objects.requireNonNull(r.getSelectionsJson()),
                new TypeReference<Map<String, PersonDataSelection>>() {});
        // A request frozen before a store was registered has no keys for it (#5382). The journal then has no recovery
        // facts, and a live store erases its current matches without keys, so both take an empty selection. A store
        // whose selection must stay unchanged still fails the inventory check: its frozen keys are missing.
        String journalStore = CorePrivacyPersonDataCatalog.PRIVACY_JOURNAL;
        for (var store : registry.stores()) {
            if (!selected.containsKey(store.store())
                    && (store.store().equals(journalStore) || !store.selectionMustStayUnchanged())) {
                selected.put(store.store(), new PersonDataSelection(List.of()));
            }
        }
        return selected;
    }

    /**
     * Whether a selection or a receipt names a store that this replica's inventory does not know. Then the pass stops
     * without a failure and leaves the request for a replica of the release that added the store; the receipts of the
     * stores it already erased stay, so the next scheduled pass on a newer replica continues from them. Outside a
     * rollout this never happens, and the alert on the counter fires when it keeps happening.
     */
    private boolean deferredToNewerRelease(UUID id, Set<String> inventory, Set<String> selected, Set<String> receipts) {
        var unknown = new TreeSet<String>();
        for (String store : selected) if (!inventory.contains(store)) unknown.add(store);
        for (String store : receipts) if (!inventory.contains(store)) unknown.add(store);
        if (unknown.isEmpty()) return false;
        meters.counter(CoreMetrics.PERSON_DATA_ERASURE_DEFERRALS).increment();
        log.atWarn()
                .addKeyValue("event.name", "person_data.erasure.deferred")
                .addKeyValue("person_data.request.id", id)
                .addKeyValue("person_data.stores", String.join(",", unknown))
                .log("Person data erasure left for a replica of a newer release that knows its stores");
        return true;
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
        scopes.bind(person);
        return request;
    }

    private record StepAdmission(
            @Nullable Long administratorAccountId, @Nullable String scopeJson) {}

    private Map<String, PersonDataStoreReceipt> completed(PersonDataRequest r) {
        return PersonDataStoreReceipt.read(mapper, r.getCompletedJson());
    }

    private List<PersonDataContributor.ExternalDelivery> deliveries(Map<String, PersonDataSelection> selected) {
        // A preview that an earlier release made has no selection for a store added since. It still opens, and its
        // erasure is refused because its stores differ from the inventory.
        return registry.stores().stream()
                .filter(s -> selected.containsKey(s.store()))
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
