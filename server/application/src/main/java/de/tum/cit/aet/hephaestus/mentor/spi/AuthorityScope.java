package de.tum.cit.aet.hephaestus.mentor.spi;

/** What an admission is authorized under, with the generation each authority had at capture. */
public sealed interface AuthorityScope {
    /** The person's own space, with no workspace. */
    record Personal() implements AuthorityScope {}

    /** A workspace the person belongs to. */
    record Workspace(long workspaceId, long workspaceGeneration) implements AuthorityScope {}

    /** A workspace's Slack binding and the verified native actor on it. */
    record Slack(
            long workspaceId, long workspaceGeneration, long bindingId, long bindingGeneration, String nativeActorId)
            implements AuthorityScope {}
}
