/**
 * GitHub lifecycle entry points: {@code GitHubWorkspaceProvisioningAdapter}, {@code GitHubInstallationReconciler} and
 * {@code GitHubInstallationAdoptionService} call {@code GitHubInstallationProvisioning} to create or adopt an
 * installation's workspace, and {@code GitHubInstallationLifecycle} for later installation and repository-selection
 * changes, because the workspace owns the Workspace aggregate while GitHub owns the installation state and its inbox
 * consumer.
 * The rest of the closed {@code integration.scm.github} module stays unreachable from outside.
 */
@org.springframework.modulith.NamedInterface("lifecycle")
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.integration.scm.github.lifecycle;
