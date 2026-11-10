package de.tum.cit.aet.hephaestus.practices.spi;

/** The ledger retains the model kind of past calls after that kind can no longer be configured. */
public enum ModelKind {
    CHAT,
    DECISION,
    EMBEDDING,
    RERANKING;
}
