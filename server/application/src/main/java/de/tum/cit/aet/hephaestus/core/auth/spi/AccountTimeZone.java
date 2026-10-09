package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The IANA time zone that an account chose for its own week, as stored on the account. */
public interface AccountTimeZone {
    /** The zone id the account chose; empty when it chose none. */
    Optional<String> timeZone(long accountId);

    /** Sets or clears ({@code null}) the account's time zone. */
    void setTimeZone(long accountId, @Nullable String timeZone);
}
