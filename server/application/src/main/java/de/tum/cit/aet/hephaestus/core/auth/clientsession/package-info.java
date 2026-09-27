/**
 * Installed-client sign-in: sessions for clients that cannot hold the browser's session cookie, such as
 * the Chrome extension.
 *
 * <p>An installed client starts a sign-in at {@code GET /auth/login?mode=client} with an exact,
 * registered {@code redirect_uri}, an S256 PKCE challenge and its own {@code state}. The federated
 * sign-in ends in a single-use, 60-second handoff code redirected back to the client; the client
 * redeems it with its verifier for an access token and a refresh secret, which travel only in response
 * bodies. Every access token is an ordinary {@code issued_jwt}-backed token carrying the session's
 * {@code sid}, so the decoder, the consent gate and every revocation path apply unchanged.
 *
 * <p>Rotation is strict: presenting a refresh secret the session already rotated away ends the session.
 * Every issuance takes the account row {@code FOR SHARE}, session operations then lock the
 * {@code client_session} row, and account-wide revocation takes the account row {@code FOR UPDATE}
 * first, so a revocation that commits is never outlived by a token a concurrent rotation minted.
 */
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.core.auth.clientsession;
