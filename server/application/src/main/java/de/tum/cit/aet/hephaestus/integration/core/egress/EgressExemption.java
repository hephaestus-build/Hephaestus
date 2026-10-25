package de.tum.cit.aet.hephaestus.integration.core.egress;

public enum EgressExemption {
    OAUTH_CONTROL_PLANE,
    WEBHOOK_REGISTRATION,
    WEBHOOK_REDELIVERY,
    OPERATOR_ALERT,
}
