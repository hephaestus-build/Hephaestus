/**
 * SCM domain and persistence shared by the GitHub and GitLab adapters. Provider-neutral synchronization
 * lives in {@code scm.sync}; provider-specific behavior belongs in {@code scm.github} or {@code scm.gitlab}.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "SCM",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.integration.scm;
