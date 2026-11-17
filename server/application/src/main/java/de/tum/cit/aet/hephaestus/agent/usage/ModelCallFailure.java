package de.tum.cit.aet.hephaestus.agent.usage;

import org.jspecify.annotations.Nullable;

/**
 * A model call that failed, as the LLM proxy saw it, for every kind of work that calls a model. It holds no text of
 * the provider, the call or an exception.
 *
 * @param kind a closed kind of the proxy, such as {@code upstream_stalled} or {@code upstream_status}, the stream's
 *     outcome, or the exception's simple class name. That last form is an open set, so the kind stays a string.
 * @param status the HTTP status the provider answered; {@code null} when it answered none
 */
public record ModelCallFailure(String kind, @Nullable Integer status) {

    /** The kind, and the status when the provider answered one, for a log line. */
    public String describe() {
        return status == null ? kind : kind + " " + status;
    }
}
