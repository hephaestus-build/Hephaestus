package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/** Confirms the journal facts of a synchronous handler before MVC writes its reply. */
public interface PrivacyJournalHttpReply {
    @Nullable
    Object invoke(Callable<@Nullable Object> handler) throws Exception;
}
