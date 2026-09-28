package de.tum.cit.aet.hephaestus.agent.job;

public enum DeliveryStatus {
    PENDING,
    DELIVERED,
    FAILED;

    static final String DESCRIPTION = "Result-processing status: null = not applicable, PENDING = awaiting processing,"
            + " DELIVERED = processing finished, FAILED = processing error. Processing may include delivery; this"
            + " status alone does not establish feedback publication.";
}
