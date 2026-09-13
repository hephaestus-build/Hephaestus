package de.tum.cit.aet.hephaestus.core.auth.spi;

/** Account-owned subscription choices only; bearer unsubscribe tokens are never exported. */
public interface NotificationPreferencesExportQuery {
    Preferences preferences(long accountId);

    record Preferences(
            boolean productFeedback,
            boolean productSurveys,
            boolean researchSurveys,
            String productFeedbackFrequency,
            boolean workspaceAlerts,
            boolean surveySummaries) {}
}
