package de.tum.cit.aet.hephaestus.practices.weeklydigest;

import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileChangeDTO;
import org.jspecify.annotations.Nullable;

/** Which change a weekly digest listed: its type and its subject, a practice or a group. Never its words. */
public record WeeklyDigestChangeKey(
        ProfileChangeDTO.Type type,
        @Nullable String practiceSlug,
        @Nullable String groupSlug) {

    static WeeklyDigestChangeKey of(ProfileChangeDTO change) {
        return new WeeklyDigestChangeKey(change.type(), change.practiceSlug(), change.groupSlug());
    }
}
