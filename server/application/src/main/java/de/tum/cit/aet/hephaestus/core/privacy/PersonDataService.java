package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
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
    private final PersonIdentityResolver resolver;
    private final PersonDataRegistry registry;
    private final List<PersonEvidenceErasure> evidenceStores;
    private final PersonDataRequestRepository requests;
    private final PersonSuppressionService suppression;
    private final PersonDataWriteFence writeFence;
    private final PersonDataCopyFence copyFence;
    private final IssuedJwtRepository issuedTokens;
    private final PlatformTransactionManager transactions;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    public record Snapshot(
            PersonDataRequest request, List<PersonDataContributor.ExternalDelivery> externalDeliveries) {}

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Snapshot preview(
            Long administratorId, @org.jspecify.annotations.Nullable Long accountId, List<PersonIdentity> identities) {
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
        var bundle = mapper.createObjectNode();
        bundle.put("schemaVersion", "1.0");
        bundle.put("generatedAt", Instant.now().toString());
        bundle.set("scope", mapper.valueToTree(scope(r)));
        var stores = bundle.putObject("stores");
        for (var store : registry.stores())
            stores.set(
                    store.store(),
                    mapper.valueToTree(store.export(Objects.requireNonNull(selected.get(store.store())))));
        return bundle;
    }

    @Transactional
    public void requestErasure(UUID id, long administratorId, boolean externalCopiesRemoved) {
        // Acquire global admission, native keys and accounts before the request row.
        // The unlocked snapshot contains only the immutable admission scope; revalidate after locking.
        var admission = jdbc.query(
                "SELECT state,scope_json FROM person_data_request WHERE id=?",
                (rs, row) -> new ErasureAdmission(Objects.requireNonNull(rs.getString(1)), rs.getString(2)),
                id);
        if (admission.isEmpty()) throw notFound();
        var initial = admission.getFirst();
        if (initial.state().equals("COMPLETE") || initial.state().equals("ERASING")) return;
        if (!initial.state().equals("PREVIEW") && !initial.state().equals("FAILED"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This preview is no longer available");
        PersonScope person = mapper.readValue(Objects.requireNonNull(initial.scopeJson()), PersonScope.class);
        if (Objects.equals(person.accountId(), administratorId))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Another administrator must authorize this erasure");
        copyFence.holdForErasure();
        writeFence.holdForErasure(person.identities());
        requireActiveAdministratorAndLockAccount(administratorId, person.accountId());
        PersonDataRequest r = requests.lock(id).orElseThrow(() -> notFound());
        if (r.getState() == PersonDataRequest.State.COMPLETE || r.getState() == PersonDataRequest.State.ERASING) return;
        if (r.getState() != PersonDataRequest.State.FAILED) requirePreview(r);
        if (!scope(r).equals(person))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The request scope changed");
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
                    "SELECT EXISTS(SELECT 1 FROM person_data_request WHERE id<>? AND state IN ('ERASING','FAILED') AND scope_json IS NOT NULL AND (scope_json::jsonb)->>'accountId'=?)",
                    Boolean.class,
                    id,
                    person.accountId().toString());
            if (Boolean.TRUE.equals(busy))
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "An erasure request already owns this account; resume that request");
        }
        suppression.suppress(person, id);
        if (person.accountId() != null) {
            jdbc.update(
                    "UPDATE account SET status='DELETING',deleted_at=CURRENT_TIMESTAMP WHERE id=? AND status<>'DELETED'",
                    person.accountId());
            issuedTokens.revokeAllForAccount(
                    person.accountId(), Instant.now(), IssuedJwt.RevokedReason.ACCOUNT_DELETED);
        }
        r.setAdministratorAccountId(administratorId);
        r.setState(PersonDataRequest.State.ERASING);
        r.setFailureCode(null);
    }

    private record ErasureAdmission(
            String state, @org.jspecify.annotations.Nullable String scopeJson) {}

    private void requireActiveAdministratorAndLockAccount(
            long administratorId, @org.jspecify.annotations.Nullable Long personAccountId) {
        // Lock both accounts in one order. Concurrent requests must not erase each other's
        // administrators after both have accepted a token validated before either erasure.
        List<Boolean> eligible = jdbc.query(
                "SELECT id,(status='ACTIVE' AND app_role='APP_ADMIN') FROM account WHERE id IN (?,?) ORDER BY id FOR UPDATE",
                (rs, row) -> rs.getLong(1) == administratorId && rs.getBoolean(2),
                administratorId,
                Objects.requireNonNullElse(personAccountId, -1L));
        if (!eligible.contains(Boolean.TRUE))
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "An active instance administrator must authorize erasure");
    }

    public void run(UUID id) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        var admission = copyFence.erase();
        try (admission) {
            Map<String, PersonDataSelection> frozen = tx.execute(status -> {
                PersonDataRequest r = lockAdmittedErasure(id);
                return r == null ? Map.<String, PersonDataSelection>of() : selections(r);
            });
            if (frozen == null || frozen.isEmpty()) return;
            Set<String> inventory = registry.stores().stream()
                    .map(PersonDataContributor::store)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!frozen.keySet().equals(inventory))
                throw new IllegalStateException("Erasure contributor inventory changed");
            for (var store : registry.stores()) store.prepareErasure(Objects.requireNonNull(frozen.get(store.store())));
            for (var store : registry.stores()) {
                Boolean proceed = tx.execute(status -> {
                    PersonDataRequest r = lockAdmittedErasure(id);
                    if (r == null) return false;
                    var completed = completed(r);
                    if (!inventory.containsAll(completed.keySet()))
                        throw new IllegalStateException("Erasure contributor inventory changed");
                    if (!completed.containsKey(store.store())) {
                        long count = store.erase(Objects.requireNonNull(frozen.get(store.store())));
                        completed.put(
                                store.store(),
                                new PersonDataStoreReceipt(count, Instant.now(), r.getAdministratorAccountId()));
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
            tx.executeWithoutResult(status -> {
                var r = requests.lock(id).orElseThrow(() -> notFound());
                if (r.getState() == PersonDataRequest.State.ERASING) {
                    r.setState(PersonDataRequest.State.FAILED);
                    r.setFailureCode("STORE_ERASURE_FAILED");
                    requests.saveAndFlush(r);
                }
            });
        }
    }

    @Transactional
    public void expirePreviews() {
        jdbc.update(
                "UPDATE person_data_request SET state='EXPIRED',scope_json=NULL,selections_json=NULL WHERE state='PREVIEW' AND expires_at<CURRENT_TIMESTAMP");
    }

    private void requireExternalRemoval(Map<String, PersonDataSelection> selected, boolean externalCopiesRemoved) {
        if (!externalCopiesRemoved && !deliveries(selected).isEmpty())
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Remove provider feedback using the un-deliver runbook and confirm removal first");
    }

    private void requireUnchanged(PersonDataRequest r, Map<String, PersonDataSelection> selected) {
        PersonScope scope = scope(r);
        if (!scope.equals(withEvidenceJobs(resolver.resolve(scope.accountId(), scope.identities())))
                || !selected.equals(registry.select(scope)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The preview scope changed; create a new preview");
    }

    private PersonScope withEvidenceJobs(PersonScope resolved) {
        var derivedJobs = evidenceStores.stream()
                .flatMap(store -> store.jobsContaining(resolved).stream())
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

    private @org.jspecify.annotations.Nullable PersonDataRequest lockAdmittedErasure(UUID id) {
        var actors = jdbc.query(
                "SELECT administrator_account_id,scope_json FROM person_data_request WHERE id=? AND state='ERASING'",
                (rs, row) -> new StepAdmission(rs.getObject(1, Long.class), rs.getString(2)),
                id);
        if (actors.isEmpty()) return null;
        var actor = actors.getFirst();
        var person = mapper.readValue(Objects.requireNonNull(actor.scopeJson()), PersonScope.class);
        requireActiveAdministratorAndLockAccount(
                Objects.requireNonNull(actor.administratorAccountId()), person.accountId());
        PersonDataRequest request = requests.lock(id).orElseThrow(() -> notFound());
        if (request.getState() != PersonDataRequest.State.ERASING) return null;
        if (!Objects.equals(request.getAdministratorAccountId(), actor.administratorAccountId())
                || !scope(request).equals(person)) throw new IllegalStateException("Erasure admission changed");
        return request;
    }

    private record StepAdmission(
            @org.jspecify.annotations.Nullable Long administratorAccountId,
            @org.jspecify.annotations.Nullable String scopeJson) {}

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
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This preview is no longer available");
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Person data request not found");
    }
}
