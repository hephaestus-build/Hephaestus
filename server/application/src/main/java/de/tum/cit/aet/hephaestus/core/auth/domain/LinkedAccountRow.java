package de.tum.cit.aet.hephaestus.core.auth.domain;

public record LinkedAccountRow(Long externalActorId, Long accountId, Account.Status status) {}
