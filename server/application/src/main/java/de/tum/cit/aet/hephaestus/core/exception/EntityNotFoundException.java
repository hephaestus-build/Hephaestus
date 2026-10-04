package de.tum.cit.aet.hephaestus.core.exception;

import java.io.Serial;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The message reaches people as a toast, so it names the thing in product words and leaves out the
 * id. Call sites pass a type name; {@link #NOUNS} maps the ones that are not already product words.
 * The name and id stay on the exception so the handler can log them for operators.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class EntityNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final Map<String, String> NOUNS = Map.ofEntries(
            Map.entry("AgentJob", "review"),
            Map.entry("ChatMessage", "message"),
            Map.entry("ChatThread", "conversation"),
            Map.entry("LlmConnection", "AI provider"),
            Map.entry("LlmModel", "AI model"),
            Map.entry("Outline collection", "Outline collection"),
            Map.entry("Outline connection", "Outline connection"),
            Map.entry("PracticeGroup", "practice group"),
            Map.entry("Catalog group", "practice group"),
            Map.entry("Catalog practice", "practice"),
            Map.entry("Offered catalog group", "practice group"),
            Map.entry("Offered catalog practice", "practice"),
            Map.entry("ReviewBackfillRun", "review of past work"),
            Map.entry("ReviewRun", "review"),
            Map.entry("ReviewSweepSchedule", "recurring check"),
            Map.entry("Slack channel", "Slack channel"),
            Map.entry("Slack connection", "Slack connection"),
            Map.entry("SyncJob", "sync"),
            Map.entry("TeamRepositoryPermission", "team permission"),
            Map.entry("WorkspaceLlmConnection", "AI provider"),
            Map.entry("WorkspaceLlmModel", "AI model"),
            Map.entry("WorkspaceMembership", "member"));

    /** What was looked up, for the debug log. The text for people leaves the id out. */
    private final String lookup;

    public EntityNotFoundException(String entityName, Long entityId) {
        super(notFound(entityName));
        this.lookup = entityName + " " + entityId;
    }

    public EntityNotFoundException(String entityName, String entityIdentifier) {
        super(notFound(entityName));
        this.lookup = entityName + " " + entityIdentifier;
    }

    /** For a lookup whose input must not be echoed back, such as a caller-supplied address. */
    public EntityNotFoundException(String message) {
        super(message);
        this.lookup = "caller-supplied lookup";
    }

    String getLookup() {
        return lookup;
    }

    private static String notFound(String entityName) {
        String noun = NOUNS.getOrDefault(entityName, lowerFirst(entityName));
        return "We could not find that " + noun + ". It may have been deleted. Reload the page to see what is current.";
    }

    private static String lowerFirst(String name) {
        return name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1);
    }
}
