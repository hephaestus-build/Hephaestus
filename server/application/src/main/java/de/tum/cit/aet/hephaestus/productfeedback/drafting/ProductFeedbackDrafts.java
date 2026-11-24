package de.tum.cit.aet.hephaestus.productfeedback.drafting;

import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The report state that a draft run drives. The filing owns the prompt, answer and trust decisions. */
public interface ProductFeedbackDrafts {

    /** A generation of a report has its own subject and sandbox credential. */
    record Subject(UUID feedbackId, int generation) {
        public String key() {
            return "draft:" + feedbackId + ":" + generation;
        }

        public static Subject fromKey(String key) {
            String[] fields = key.split(":", -1);
            if (fields.length != 3 || !fields[0].equals("draft"))
                throw new IllegalArgumentException("Invalid report draft subject");
            return new Subject(UUID.fromString(fields[1]), Integer.parseInt(fields[2]));
        }
    }

    interface Queue {
        void enqueue(Subject subject, Duration deadline);
    }

    /** Takes the filing lock before a run lock. */
    void lock(Subject subject);

    /** Marks the report as drafting if it still waits for this generation. */
    boolean claimed(Subject subject);

    /** Marks a held report generation as waiting for model capacity. */
    void deferred(Subject subject);

    /** Records the result when this is still the generation that waits. */
    void ended(Subject subject, @Nullable String answer, String reason);
}
