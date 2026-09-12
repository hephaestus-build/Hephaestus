package de.tum.cit.aet.hephaestus.notification.email;

import java.util.Locale;

/**
 * Every email Hephaestus can send. The template name selects {@code templates/email/html/<name>.html},
 * {@code templates/email/text/<name>.txt} and the {@code email.<name>.subject} message; the enum
 * name is the bounded {@code kind} tag on the delivery metric.
 */
public enum EmailKind {
    /** Sent by an instance admin from the settings page to prove the relay works end to end. */
    TEST_MESSAGE("test-message"),
    /** The GDPR Art. 17 cooldown started; tells the person when the purge runs. */
    ACCOUNT_DELETION_SCHEDULED("account-deletion-scheduled");

    private final String templateName;

    EmailKind(String templateName) {
        this.templateName = templateName;
    }

    public String templateName() {
        return templateName;
    }

    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
