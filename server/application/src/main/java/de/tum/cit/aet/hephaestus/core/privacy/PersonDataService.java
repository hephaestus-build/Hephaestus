package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
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
    private final PersonDataRequestRepository requests;
    private final PersonSuppressionService suppression;
    private final PlatformTransactionManager transactions;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    public record Snapshot(
            PersonDataRequest request, List<PersonDataContributor.ExternalDelivery> externalDeliveries) {}

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Snapshot preview(
            Long administratorId, @org.jspecify.annotations.Nullable Long accountId, List<PersonIdentity> identities) {
        PersonScope scope = resolver.resolve(accountId, identities);
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
        bundle.set("identities", mapper.valueToTree(scope(r).identities()));
        var stores = bundle.putObject("stores");
        for (var store : registry.stores())
            stores.set(
                    store.store(),
                    mapper.valueToTree(store.export(Objects.requireNonNull(selected.get(store.store())))));
        return bundle;
    }

    @Transactional
    public void requestErasure(UUID id, long administratorId, boolean externalCopiesRemoved) {
        PersonDataRequest r = requests.lock(id).orElseThrow(() -> notFound());
        if (r.getState() == PersonDataRequest.State.COMPLETE || r.getState() == PersonDataRequest.State.ERASING) return;
        if (r.getState() == PersonDataRequest.State.FAILED) {
            r.setState(PersonDataRequest.State.ERASING);
            r.setFailureCode(null);
            return;
        }
        requirePreview(r);
        var selected = selections(r);
        requireUnchanged(r, selected);
        PersonScope person = scope(r);
        if (person.accountId() != null && person.accountId().equals(r.getAdministratorAccountId()))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Another administrator must create a new preview for this erasure");
        if (!externalCopiesRemoved && !deliveries(selected).isEmpty())
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Remove provider feedback using the un-deliver runbook and confirm removal first");
        if (person.accountId() != null) {
            jdbc.queryForList("SELECT id FROM account WHERE id=? FOR UPDATE", person.accountId());
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
            if (person.accountId().equals(administratorId))
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Another administrator must perform this erasure");
            jdbc.update(
                    "UPDATE account SET status='DELETING',deleted_at=CURRENT_TIMESTAMP WHERE id=? AND status<>'DELETED'",
                    person.accountId());
        }
        r.setAdministratorAccountId(administratorId);
        r.setState(PersonDataRequest.State.ERASING);
        r.setFailureCode(null);
    }

    public void run(UUID id) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        try {
            Map<String, PersonDataSelection> frozen = tx.execute(status -> {
                PersonDataRequest r = requests.lock(id).orElseThrow(() -> notFound());
                return r.getState() == PersonDataRequest.State.ERASING
                        ? selections(r)
                        : Map.<String, PersonDataSelection>of();
            });
            if (frozen == null || frozen.isEmpty()) return;
            for (var store : registry.stores()) store.prepareErasure(Objects.requireNonNull(frozen.get(store.store())));
            for (var store : registry.stores()) {
                Boolean proceed = tx.execute(status -> {
                    PersonDataRequest r = requests.lock(id).orElseThrow(() -> notFound());
                    if (r.getState() != PersonDataRequest.State.ERASING) return false;
                    var completed = completed(r);
                    if (!completed.keySet().stream()
                            .allMatch(key -> registry.stores().stream()
                                    .anyMatch(s -> s.store().equals(key))))
                        throw new IllegalStateException("Erasure contributor inventory changed");
                    if (!completed.containsKey(store.store())) {
                        long count =
                                store.erase(Objects.requireNonNull(selections(r).get(store.store())));
                        completed.put(store.store(), count);
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

    private void requireUnchanged(PersonDataRequest r, Map<String, PersonDataSelection> selected) {
        PersonScope scope = scope(r);
        if (!scope.equals(resolver.resolve(scope.accountId(), scope.identities()))
                || !selected.equals(registry.select(scope)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The preview scope changed; create a new preview");
    }

    private PersonScope scope(PersonDataRequest r) {
        return mapper.readValue(Objects.requireNonNull(r.getScopeJson()), PersonScope.class);
    }

    private Map<String, PersonDataSelection> selections(PersonDataRequest r) {
        return mapper.readValue(
                Objects.requireNonNull(r.getSelectionsJson()),
                new TypeReference<Map<String, PersonDataSelection>>() {});
    }

    private Map<String, Long> completed(PersonDataRequest r) {
        return mapper.readValue(r.getCompletedJson(), new TypeReference<Map<String, Long>>() {});
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
