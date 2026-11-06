package de.tum.cit.aet.hephaestus.core.privacy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** @param recoveryManifest Read-only mounted manifest required by the erasure-recovery startup profile. */
@ConfigurationProperties("hephaestus.privacy")
public record PersonDataRecoveryProperties(@DefaultValue("") String recoveryManifest) {}
