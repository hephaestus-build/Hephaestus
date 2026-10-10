package de.tum.cit.aet.hephaestus.activity.overview.dto;

/** How activity counts a contributor's account. */
public enum ActivityContributorKind {
    /** A person, counted among the people. */
    PERSON,
    /** A provider bot account, which is always automation. */
    BOT,
    /** An account that a workspace admin treats as automation. */
    AUTOMATION;

    public static ActivityContributorKind of(boolean bot, boolean classified) {
        if (bot) {
            return BOT;
        }
        return classified ? AUTOMATION : PERSON;
    }
}
