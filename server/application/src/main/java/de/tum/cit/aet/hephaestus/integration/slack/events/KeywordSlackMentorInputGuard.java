package de.tum.cit.aet.hephaestus.integration.slack.events;

import java.util.List;
import java.util.Locale;

/** Narrow keyword guard for mentor DMs. It is not a general moderation or crisis-detection system. */
public class KeywordSlackMentorInputGuard implements SlackMentorInputGuard {

    private static final List<String> SELF_HARM_CUES = List.of(
            "kill myself",
            "killing myself",
            "suicide",
            "suicidal",
            "end my life",
            "want to die",
            "kill me",
            "hurt myself",
            "harming myself",
            "self harm",
            "self-harm",
            "no reason to live");

    private static final List<String> HARASSMENT_CUES = List.of(
            "kill you", "kys", "i hate you", "you're worthless", "you are worthless", "shut up you", "stupid bot");

    static final String SELF_HARM_RESPONSE =
            "It sounds like you may be going through something very hard. I am not the right kind of help for "
                    + "this. I am only a coding-practice mentor. Reach out to someone who can support you now. In "
                    + "many countries, you can call or text a crisis line, for example 988 in the US and Canada. "
                    + "You can also contact your local emergency services. "
                    + "You deserve real support, and it is okay to ask for it.";

    static final String THREADING_RESPONSE =
            "I reply in this DM thread. Hephaestus mentors you in DMs. It uses channel messages only as context that is allowed.";

    @Override
    public Verdict decide(String text) {
        if (text == null || text.isBlank()) {
            return new Verdict(Action.ALLOW, null);
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, SELF_HARM_CUES)) {
            return new Verdict(Action.REPLY, SELF_HARM_RESPONSE);
        }
        if (containsAny(normalized, HARASSMENT_CUES)) {
            return new Verdict(Action.IGNORE, null);
        }
        if (isSlackThreadingQuestion(normalized)) {
            return new Verdict(Action.REPLY, THREADING_RESPONSE);
        }
        return new Verdict(Action.ALLOW, null);
    }

    private static boolean isSlackThreadingQuestion(String normalized) {
        return (normalized.contains("chat thread")
                || normalized.contains("dm thread")
                || normalized.contains("main chat")
                || normalized.contains("channel repl")
                || normalized.contains("reply in thread")
                || normalized.contains("reply in a thread"));
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
