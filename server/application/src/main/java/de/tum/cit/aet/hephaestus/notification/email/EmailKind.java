package de.tum.cit.aet.hephaestus.notification.email;

import java.util.Locale;

/**
 * Every email Hephaestus can send. The template name selects {@code templates/email/html/<name>.html},
 * {@code templates/email/text/<name>.txt} and the {@code email.<name>.subject} message; the enum
 * name is the bounded {@code kind} tag on the delivery metric.
 */
public enum EmailKind {
    WORKSPACE_ALERT("workspace-alert", true),
    /** Sent by an instance admin from the settings page to prove the relay works end to end. */
    TEST_MESSAGE("test-message", false),
    /** The GDPR Art. 17 cooldown started; tells the person when the purge runs. */
    ACCOUNT_DELETION_SCHEDULED("account-deletion-scheduled", false),
    ACCOUNT_SECURITY_CHANGED("account-security-changed", false),
    SURVEY_ENDED_SUMMARY("survey-ended-summary", true),
    SURVEY_INVITATION("survey-invitation", true),
    PRODUCT_FEEDBACK_DIGEST("product-feedback-digest", true),
    PRODUCT_FEEDBACK("product-feedback", true);

    private final String templateName;
    private final boolean optional;

    EmailKind(String templateName, boolean optional) {
        this.templateName = templateName;
        this.optional = optional;
    }

    public boolean optional() {
        return optional;
    }

    public String templateName() {
        return templateName;
    }

    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
