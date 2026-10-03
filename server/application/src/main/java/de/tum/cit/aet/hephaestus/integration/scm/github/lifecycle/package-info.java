/**
 * GitHub lifecycle entry points for the workspace module: {@code WorkspaceProvisioningService} and
 * {@code WorkspaceProvisioningAdapter} call {@code GitHubLifecycleListener} for installation and
 * repository-selection changes, because the workspace owns the Workspace aggregate while GitHub owns the
 * installation state and its NATS consumer. The rest of the closed {@code integration.scm.github} module stays
 * unreachable from outside.
 */
@org.springframework.modulith.NamedInterface("lifecycle")
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.integration.scm.github.lifecycle;
