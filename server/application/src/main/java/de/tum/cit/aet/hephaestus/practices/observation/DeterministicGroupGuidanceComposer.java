package de.tum.cit.aet.hephaestus.practices.observation;

import de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupStandingDTO;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

final class DeterministicGroupGuidanceComposer {

    /** Keeps catalog prose from turning the compact group card into a full practice description. */
    private static final int MAX_REMINDER_LENGTH = 180;

    private DeterministicGroupGuidanceComposer() {}

    /**
     * Produces a short standing sentence, a concrete focus, and at most one catalog-authored reminder.
     * Review criteria never enter this method: {@code whatGoodLooksLike}/{@code whyItMatters} are the
     * catalog's dedicated developer-facing layer.
     */
    static @Nullable String compose(PracticeGroupStandingDTO.Standing status, List<PracticeStandingDTO> cards) {
        // Without a verdict there is nothing to summarise, and inventing encouragement here would state
        // more than the evidence supports. The surface renders its own reason-specific empty copy.
        if (!PracticeGroupStandingDTO.isVerdict(status)) {
            return null;
        }

        // Partitioned by standing, exactly as the group status is: a practice whose recent evidence already
        // earned it a STRENGTH standing must not be named as the group's next focus merely because older items
        // are still on its card, and the sentence would otherwise contradict the status it explains. A MIXED
        // practice belongs on both sides — that is what makes it mixed.
        //
        // Non-verdict practices are dropped FIRST. Their standing is neither DEVELOPING nor STRENGTH, so a
        // negated filter would put a practice with nothing to say on both sides at once and name it as a
        // strength and a gap in the same sentence.
        List<PracticeStandingDTO> verdicts = cards.stream()
                .filter(card -> PracticeStandingDTO.isVerdict(card.standing()))
                .toList();
        List<PracticeStandingDTO> strengths = verdicts.stream()
                .filter(card -> card.standing() != PracticeStandingDTO.Standing.DEVELOPING)
                .toList();
        List<PracticeStandingDTO> gaps = verdicts.stream()
                .filter(card -> card.standing() != PracticeStandingDTO.Standing.STRENGTH)
                .toList();

        StringBuilder summary = new StringBuilder();
        switch (status) {
            case STRENGTH ->
                summary.append(
                                strengths.size() == 1
                                        ? "Your recent feedback shows a strength in "
                                        : "Your recent feedback shows strengths in ")
                        .append(nameList(strengths))
                        .append(strengths.size() == 1 ? ". Keep building on it." : ". Keep building on them.");
            case DEVELOPING ->
                summary.append("Your recent feedback suggests that ")
                        .append(nameList(gaps))
                        .append(
                                gaps.size() == 1
                                        ? " is the next practice to work on."
                                        : " are the next practices to work on.");
            case MIXED -> {
                if (samePractices(strengths, gaps)) {
                    summary.append("Your recent feedback is mixed in ")
                            .append(nameList(gaps))
                            .append(", with both strengths and room to grow.");
                } else {
                    summary.append("Your recent feedback shows a strength in ")
                            .append(nameList(strengths))
                            .append(". Next, work on ")
                            .append(nameList(gaps))
                            .append(".");
                }
            }
            case NOT_OBSERVED, NO_OPPORTUNITY ->
                throw new IllegalStateException("Non-verdict statuses are handled before composing guidance");
        }

        if (!gaps.isEmpty()) {
            appendCatalogReminder(summary, gaps.get(0));
        } else if (!strengths.isEmpty()) {
            appendCatalogReminder(summary, strengths.get(0));
        }
        return summary.toString();
    }

    private static void appendCatalogReminder(StringBuilder summary, PracticeStandingDTO practice) {
        String exemplar = nonBlank(practice.whatGoodLooksLike());
        if (exemplar != null) {
            summary.append(" What good looks like: ").append(conciseReminder(exemplar));
            return;
        }
        String rationale = nonBlank(practice.whyItMatters());
        if (rationale != null) {
            summary.append(" Why it matters: ").append(conciseReminder(rationale));
        }
    }

    private static boolean samePractices(List<PracticeStandingDTO> strengths, List<PracticeStandingDTO> gaps) {
        return (!strengths.isEmpty()
                && strengths.stream()
                        .map(PracticeStandingDTO::slug)
                        .toList()
                        .equals(gaps.stream().map(PracticeStandingDTO::slug).toList()));
    }

    private static String nameList(List<PracticeStandingDTO> cards) {
        List<String> names = cards.stream()
                .map(PracticeStandingDTO::name)
                .filter(Objects::nonNull)
                .map(name -> "“" + name + "”")
                .distinct()
                .toList();
        return switch (names.size()) {
            case 0 -> "this group";
            case 1 -> names.get(0);
            case 2 -> names.get(0) + " and " + names.get(1);
            default -> names.get(0) + ", " + names.get(1) + ", and other practices";
        };
    }

    private static @Nullable String nonBlank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String conciseReminder(String value) {
        String singleLine = value.replaceAll("\\s+", " ").trim();
        if (singleLine.length() > MAX_REMINDER_LENGTH) {
            int wordBoundary = singleLine.lastIndexOf(' ', MAX_REMINDER_LENGTH - 1);
            int end = wordBoundary >= MAX_REMINDER_LENGTH / 2 ? wordBoundary : MAX_REMINDER_LENGTH - 1;
            return singleLine.substring(0, end).stripTrailing() + "…";
        }
        char last = singleLine.charAt(singleLine.length() - 1);
        return last == '.' || last == '!' || last == '?' ? singleLine : singleLine + ".";
    }
}
