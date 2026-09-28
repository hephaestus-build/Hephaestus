package de.tum.cit.aet.hephaestus.integration.scm.github.jackson;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHBot;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHMannequin;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHTeam;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHUser;

/**
 * Jackson mixin for GitHub GraphQL RequestedReviewer interface.
 * <p>
 * Configures polymorphic deserialization for RequestedReviewer types.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "__typename",
        visible = true,
        defaultImpl = GHUser.class)
@JsonSubTypes({
    @JsonSubTypes.Type(value = GHUser.class, name = "User"),
    @JsonSubTypes.Type(value = GHTeam.class, name = "Team"),
    @JsonSubTypes.Type(value = GHBot.class, name = "Bot"),
    @JsonSubTypes.Type(value = GHMannequin.class, name = "Mannequin"),
})
public abstract class GitHubRequestedReviewerMixin {}
