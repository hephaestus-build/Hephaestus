/**
 * Mentor module: the account-owned runtime of the personal mentor (ADR 0120): its typed mailbox, requests, attempts,
 * tasks, callbacks and effects. Transports and evidence adapters reach it only through {@code mentor.spi}.
 */
@ApplicationModule(
        displayName = "Mentor",
        allowedDependencies = {"core", "core::person-data", "core::runtime"})
@NullMarked
package de.tum.cit.aet.hephaestus.mentor;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
