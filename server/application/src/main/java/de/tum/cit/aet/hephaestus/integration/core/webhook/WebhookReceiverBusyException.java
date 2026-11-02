package de.tum.cit.aet.hephaestus.integration.core.webhook;

/** Unchecked, so that no reader of a body that handles a failed read can turn the refusal into a client error. */
class WebhookReceiverBusyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    WebhookReceiverBusyException() {
        super("the receiver holds as many delivery bytes as it can");
    }
}
