/**
 * Cross-module port surface for the auth module. Holds both directions of the dependency:
 *
 * <ul>
 *   <li><b>Provided (inbound) ports</b> — contracts {@code core.auth} implements for other modules
 *       to consume: e.g. {@code AccountIdentityQuery}, {@code AccountPreferencesQuery}, {@code
 *       AccountMembershipQuery}.
 *   <li><b>Required (outbound) ports</b> — contracts {@code core.auth} <em>consumes</em> but
 *       expects another module (typically {@code integration}) to implement, keeping vendor
 *       knowledge out of auth: e.g. {@code GitProviderRegistry}, {@code IdentityProviderCatalog}.
 * </ul>
 *
 * <p>Modulith verification enforces that this is the only {@code core.auth} package other modules
 * may depend on.
 */
@org.springframework.modulith.NamedInterface("auth-spi")
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.core.auth.spi;
