package de.tum.cit.aet.hephaestus.scenario;

import java.util.List;

/**
 * The people and places that every scenario starts with: Alex and Jordan are members of two team workspaces, and Alex
 * has a personal space. Each workspace has its own Slack team.
 */
public final class Fixture {

    public static final Person ALEX = new Person("Alex");
    public static final Person JORDAN = new Person("Jordan");
    public static final Place TEAM_A = Place.team("team-a");
    public static final Place TEAM_B = Place.team("team-b");
    public static final Place ALEX_SPACE = Place.personalSpace(ALEX);
    public static final String SLACK_A = "slack-a";
    public static final String SLACK_B = "slack-b";

    private Fixture() {}

    static List<Step> steps() {
        return List.of(
                new Step.Join(ALEX, TEAM_A),
                new Step.Join(JORDAN, TEAM_A),
                new Step.Join(ALEX, TEAM_B),
                new Step.Join(JORDAN, TEAM_B),
                new Step.BindSlackTeam(SLACK_A, TEAM_A),
                new Step.BindSlackTeam(SLACK_B, TEAM_B));
    }
}
