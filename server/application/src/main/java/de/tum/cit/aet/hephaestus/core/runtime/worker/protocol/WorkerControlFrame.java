package de.tum.cit.aet.hephaestus.core.runtime.worker.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Sealed type of worker control-channel frames. Jackson keys on the {@code "type"} property. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = GitOperation.class, name = "GitOperation"),
    @JsonSubTypes.Type(value = GitOutput.class, name = "GitOutput"),
    @JsonSubTypes.Type(value = GitAck.class, name = "GitAck"),
    @JsonSubTypes.Type(value = GitCancel.class, name = "GitCancel"),
    @JsonSubTypes.Type(value = WorkerHello.class, name = "WorkerHello"),
    @JsonSubTypes.Type(value = WorkerWelcome.class, name = "WorkerWelcome"),
    @JsonSubTypes.Type(value = Heartbeat.class, name = "Heartbeat"),
    @JsonSubTypes.Type(value = CapacityReport.class, name = "CapacityReport"),
    @JsonSubTypes.Type(value = ForceReconnect.class, name = "ForceReconnect"),
    @JsonSubTypes.Type(value = CancelJob.class, name = "CancelJob"),
})
public sealed interface WorkerControlFrame
        permits GitOperation,
                GitOutput,
                GitAck,
                GitCancel,
                WorkerHello,
                WorkerWelcome,
                Heartbeat,
                CapacityReport,
                ForceReconnect,
                CancelJob {}
