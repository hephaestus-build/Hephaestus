package de.tum.cit.aet.hephaestus.scenario;

/** One event of a scenario. The harness gives each step to the judge first, then to the subject. */
public sealed interface Step {

    /** A person gains membership of a team workspace. A second join after a leave is a regrant. */
    record Join(Person person, Place place) implements Step {}

    /** A person loses membership of a team workspace. This narrows their authority there. */
    record Leave(Person person, Place place) implements Step {}

    /** A Slack team is bound to one workspace. */
    record BindSlackTeam(String slackTeam, Place place) implements Step {}

    /** A workspace changes its AI provider. What travels into it from another place pauses. */
    record ChangeProvider(Place place, String model) implements Step {}

    /** A record appears. */
    record Plant(ScenarioRecord record) implements Step {}

    /** A record is erased. Nothing may show it or what rests on it again, not even after a restore. */
    record Erase(String recordId) implements Step {}

    /** The subject's database is backed up. */
    record Backup() implements Step {}

    /** The subject's database is restored from the last backup. */
    record Restore() implements Step {}

    /** A person writes a message in a place, in the app. */
    record Message(Person person, Place place, String text) implements Step {}

    /** A person writes a Slack direct message in a Slack team. */
    record SlackMessage(Person person, String slackTeam, String text) implements Step {}

    /** A review of a person's work finished in a workspace. */
    record ReviewFinished(Person author, Place place, String work) implements Step {}
}
