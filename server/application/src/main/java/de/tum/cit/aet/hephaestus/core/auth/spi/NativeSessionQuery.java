package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.util.UUID;

/**
 * Whether a native app session still speaks for its account: unrevoked and within its deadline, the
 * account active, and the current transparency notice completed. Anything that reaches an installed app
 * on the account's behalf — a push notification — checks this at the moment it acts, not when it queued.
 */
public interface NativeSessionQuery {

    boolean isSignedIn(UUID nativeSessionId, long accountId);
}
