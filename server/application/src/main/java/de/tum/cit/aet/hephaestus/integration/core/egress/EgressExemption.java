package de.tum.cit.aet.hephaestus.integration.core.egress;

public enum EgressExemption {
    OAUTH_CONTROL_PLANE,
    WEBHOOK_REGISTRATION,
    WEBHOOK_REDELIVERY,
    OPERATOR_ALERT,
    /**
     * The instance's own GitHub App filing product feedback to the operator's repositories. It still consults Silent
     * Mode before each write; Pause is its own switch.
     */
    PRODUCT_FEEDBACK,
}
