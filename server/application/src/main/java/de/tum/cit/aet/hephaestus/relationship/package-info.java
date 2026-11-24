/**
 * Relationship module: a person's relationship with Hephaestus, its settings, the change feed of the person's clients
 * and the settings of each workspace. It is the lowest of the three mentoring modules: {@code learning} and
 * {@code mentoring} may depend on it, and {@code agent} and {@code notification} sit above all three. No module below
 * it, such as {@code practices} or {@code conversation}, may depend on it.
 */
@ApplicationModule(
        displayName = "Relationship",
        allowedDependencies = {})
@NullMarked
package de.tum.cit.aet.hephaestus.relationship;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
