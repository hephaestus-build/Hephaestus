/**
 * Native app sessions: the PKCE-bound sign-in handoff from the browser federation to the installed app,
 * and the rotating refresh secret that keeps an app signed in until the absolute session deadline.
 *
 * <p>{@code issued_jwt} stays the revocation authority. A native session is live only while the
 * {@code issued_jwt} row of its current access token is unrevoked, so logout, the session list,
 * sign-out-everywhere, suspension and deletion end it through the paths they already take. See ADR 0045.
 */
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.core.auth.nativesession;
