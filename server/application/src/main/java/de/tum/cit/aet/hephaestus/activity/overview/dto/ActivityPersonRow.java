package de.tum.cit.aet.hephaestus.activity.overview.dto;

/** Identity keys for an activity row that reports a person's counts. */
public interface ActivityPersonRow {
    String activityName();

    String activityLogin();

    default String activityIdentity() {
        return activityLogin();
    }
}
