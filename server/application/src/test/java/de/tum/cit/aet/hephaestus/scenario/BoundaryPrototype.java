package de.tum.cit.aet.hephaestus.scenario;

import static de.tum.cit.aet.hephaestus.scenario.Fixture.ALEX;
import static de.tum.cit.aet.hephaestus.scenario.Fixture.ALEX_SPACE;
import static de.tum.cit.aet.hephaestus.scenario.Fixture.JORDAN;
import static de.tum.cit.aet.hephaestus.scenario.Fixture.SLACK_A;
import static de.tum.cit.aet.hephaestus.scenario.Fixture.TEAM_A;
import static de.tum.cit.aet.hephaestus.scenario.Fixture.TEAM_B;

import java.time.Duration;

/**
 * The boundary prototype: two workspaces, two people, a personal space, a provider change, Slack routing ambiguity, a
 * restore and a regrant, in one timeline. A summary derived from a record that is later erased gives the basis chain.
 */
final class BoundaryPrototype {

    static final ScenarioRecord TEAM_A_NOTE =
            ScenarioRecord.team("team-a-note", JORDAN, TEAM_A, "Releases follow the checklist in RELEASE.md.");
    static final ScenarioRecord TEAM_B_NOTE =
            ScenarioRecord.team("team-b-note", ALEX, TEAM_B, "Team B deploys on Thursdays.");
    static final ScenarioRecord JORDAN_PRIVATE =
            ScenarioRecord.ownerOnly("jordan-private", JORDAN, TEAM_A, "Jordan wants to split large pull requests.");
    static final ScenarioRecord ALEX_TRAVELLING = ScenarioRecord.ownerOnly(
                    "alex-travelling", ALEX, ALEX_SPACE, "Alex writes the test before the fix.")
            .travellingTo(TEAM_A);
    static final ScenarioRecord ALEX_ERASED =
            ScenarioRecord.ownerOnly("alex-erased", ALEX, ALEX_SPACE, "Alex struggles with code review comments.");
    static final ScenarioRecord ALEX_SUMMARY = ScenarioRecord.ownerOnly(
                    "alex-summary", ALEX, ALEX_SPACE, "Alex asked twice how to answer review comments.")
            .derivedFrom(ALEX_ERASED.id());

    /** Alex wrote last in team B, so a Slack message in team A's Slack team is ambiguous to a careless router. */
    static final Duration ALEX_ASKS_IN_TEAM_B = minutes(1);

    static final Duration ALEX_ASKS_IN_SLACK_A = minutes(5);
    static final Duration ALEX_ASKS_IN_PERSONAL_SPACE = minutes(10);
    static final Duration ALEX_ASKS_AFTER_RESTORE = minutes(25);
    static final Duration REVIEW_OF_JORDAN = minutes(30);
    static final Duration JORDAN_ASKS_AFTER_LEAVING = minutes(50);
    static final Duration ALEX_ASKS_AFTER_PROVIDER_CHANGE = minutes(105);

    private BoundaryPrototype() {}

    static Scenario scenario() {
        return Scenario.named("boundary prototype")
                .at(
                        Duration.ZERO,
                        new Step.Plant(TEAM_A_NOTE),
                        new Step.Plant(TEAM_B_NOTE),
                        new Step.Plant(JORDAN_PRIVATE),
                        new Step.Plant(ALEX_TRAVELLING),
                        new Step.Plant(ALEX_ERASED),
                        new Step.Plant(ALEX_SUMMARY),
                        new Step.Backup())
                .at(ALEX_ASKS_IN_TEAM_B, new Step.Message(ALEX, TEAM_B, "When do we deploy?"))
                .at(ALEX_ASKS_IN_SLACK_A, new Step.SlackMessage(ALEX, SLACK_A, "How do we release?"))
                .at(ALEX_ASKS_IN_PERSONAL_SPACE, new Step.Message(ALEX, ALEX_SPACE, "What should I practise?"))
                .at(minutes(15), new Step.Erase(ALEX_ERASED.id()))
                .at(minutes(20), new Step.Restore())
                .at(ALEX_ASKS_AFTER_RESTORE, new Step.Message(ALEX, ALEX_SPACE, "What should I practise now?"))
                .at(REVIEW_OF_JORDAN, new Step.ReviewFinished(JORDAN, TEAM_A, "pull request 12"))
                // The reminder of that review is due 60 minutes after it was queued: after the regrant.
                .at(minutes(45), new Step.Leave(JORDAN, TEAM_A))
                .at(JORDAN_ASKS_AFTER_LEAVING, new Step.Message(JORDAN, TEAM_A, "Did my review finish?"))
                .at(minutes(60), new Step.Join(JORDAN, TEAM_A))
                .at(minutes(100), new Step.ChangeProvider(TEAM_A, "model-b"))
                .at(ALEX_ASKS_AFTER_PROVIDER_CHANGE, new Step.Message(ALEX, TEAM_A, "How do we release now?"))
                .build();
    }

    private static Duration minutes(long minutes) {
        return Duration.ofMinutes(minutes);
    }
}
