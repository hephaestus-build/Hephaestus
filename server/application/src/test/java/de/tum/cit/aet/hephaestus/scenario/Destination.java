package de.tum.cit.aet.hephaestus.scenario;

/** Where a sent effect lands. The judge derives the readers from the destination, never from the subject. */
public sealed interface Destination {

    /** The reviewed work in a workspace: every member reads it. */
    record Work(Place place) implements Destination {}

    /** A notification to one person in a place. */
    record Direct(Person person, Place place) implements Destination {}

    /** A Slack direct message in a Slack team. The judge reads it in the workspace that the team is bound to. */
    record SlackDm(Person person, String slackTeam) implements Destination {}
}
