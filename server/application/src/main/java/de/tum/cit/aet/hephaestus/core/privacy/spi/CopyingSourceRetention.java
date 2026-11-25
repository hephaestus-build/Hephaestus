package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.time.Duration;
import java.util.Map;

/**
 * A source whose words a conversation tool result can copy, such as the channel messages of a monitored Slack
 * workspace. No record says which source a tool result copied, so the conversation retention erases each such result
 * with the shortest retention of a source in its workspace.
 */
public interface CopyingSourceRetention {
    /** For each workspace that has this source, how long the source keeps its words. */
    Map<Long, Duration> retentionByWorkspace();
}
