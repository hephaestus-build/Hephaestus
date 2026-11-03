package de.tum.cit.aet.hephaestus.integration.core.webhook;

/** Unchecked for the same reason as {@link WebhookReceiverBusyException}. */
class WebhookBodyTooSlowException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    WebhookBodyTooSlowException() {
        super("the body did not arrive within its deadline");
    }
}
