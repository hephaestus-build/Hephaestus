package de.tum.cit.aet.hephaestus.integration.scm.github.jackson;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHCheckRun;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHStatusContext;

/** Jackson mixin for the GitHub GraphQL StatusCheckRollupContext union: a check run or a commit status. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "__typename", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = GHCheckRun.class, name = "CheckRun"),
    @JsonSubTypes.Type(value = GHStatusContext.class, name = "StatusContext"),
})
public abstract class GitHubStatusCheckRollupContextMixin {}
