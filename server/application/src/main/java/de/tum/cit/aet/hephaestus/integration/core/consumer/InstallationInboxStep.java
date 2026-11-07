package de.tum.cit.aet.hephaestus.integration.core.consumer;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Installation lifecycle events have no workspace until their handler creates one. */
@Entity
@Table(
        name = "installation_inbox_step",
        indexes = @Index(name = "idx_installation_inbox_step_processed_at", columnList = "processed_at"),
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_installation_inbox_step_delivery",
                        columnNames = {"consumer_name", "stream_name", "stream_sequence", "received_at", "step_name"}))
public class InstallationInboxStep extends InboxStep {}
