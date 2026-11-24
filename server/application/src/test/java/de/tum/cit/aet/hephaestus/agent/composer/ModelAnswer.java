package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.agent.proxy.ProxyRefusal;
import org.jspecify.annotations.Nullable;

/** One model call's answer as a test serves it to the composer: the provider's answer, or the proxy rule that refused it. */
@SuppressWarnings("ArrayRecordComponent") // the provider's response bytes, written once to the composer
record ModelAnswer(int status, byte[] body, @Nullable ProxyRefusal refusal) {}
