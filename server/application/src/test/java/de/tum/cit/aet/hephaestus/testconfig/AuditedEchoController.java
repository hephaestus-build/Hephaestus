package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Declares a config audit row and writes none, one, or an unchanged update: what {@link AuditedRequestCheck} judges. */
@RestController
@RequestMapping("/audited-request-check")
public class AuditedEchoController {

    /** The state a test row records. */
    public record EchoSnapshot(String name) implements ConfigAuditSnapshot {}

    private final ConfigAuditPort audit;
    private final TransactionTemplate transactions;

    public AuditedEchoController(ConfigAuditPort audit, TransactionTemplate transactions) {
        this.audit = audit;
        this.transactions = transactions;
    }

    @PostMapping("/{id}/silent")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CATALOG_GROUP")
    ResponseEntity<Void> silent(@PathVariable String id) {
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/recorded")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CATALOG_GROUP")
    ResponseEntity<Void> recorded(@PathVariable String id) {
        write(new ConfigAuditEntry(ConfigAuditEntityType.CATALOG_GROUP, id, null, null, new EchoSnapshot(id)));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/unchanged")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CATALOG_GROUP")
    ResponseEntity<Void> unchanged(@PathVariable String id) {
        EchoSnapshot same = new EchoSnapshot(id);
        write(new ConfigAuditEntry(ConfigAuditEntityType.CATALOG_GROUP, id, null, same, same));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/other-type")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CATALOG_GROUP")
    ResponseEntity<Void> otherType(@PathVariable String id) {
        write(new ConfigAuditEntry(ConfigAuditEntityType.CATALOG_PRACTICE, id, null, null, new EchoSnapshot(id)));
        return ResponseEntity.noContent().build();
    }

    private void write(ConfigAuditEntry entry) {
        transactions.executeWithoutResult(status -> audit.record(entry));
    }
}
