package de.tum.cit.aet.hephaestus.core;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The trails an admin action can be recorded on — one constant per append-only table, each carrying
 * the vocabulary that table types its rows with.
 */
public enum AuditLedger {
    /** {@code config_audit_event} — admin configuration changes, per workspace or instance-curated. */
    CONFIG_AUDIT(ConfigAuditEntityType.values()),

    /** {@code auth_event} — authentication, private user access, and instance-level administration. */
    AUTH_EVENT(AuthEvent.EventType.values()),

    /** {@code connection_audit} — integration connection lifecycle. */
    CONNECTION_AUDIT;

    private final Set<String> vocabulary;

    AuditLedger(Enum<?>... rowTypes) {
        this.vocabulary = Arrays.stream(rowTypes).map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The constants this ledger types its rows with. Empty for {@link #CONNECTION_AUDIT}, whose
     * {@code event_type} is composed at write time — which is why {@link Audited#type()} is optional.
     */
    public Set<String> vocabulary() {
        return vocabulary;
    }
}
