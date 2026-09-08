package de.tum.cit.aet.hephaestus.workspace.directory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Only completed captures are persisted. Absence alone is not a confirmed departure. */
public record DirectorySnapshot(
        Instant startedAt,
        Instant completedAt,
        long configurationVersion,
        Instant sourceVersion,
        Set<String> groupIds,
        Map<String, String> groupNames,
        Map<String, Set<String>> eligibleSubjects,
        Set<String> confirmedDepartures) {
    public static final Duration MAX_AGE = Duration.ofMinutes(15);

    public DirectorySnapshot {
        groupIds = Set.copyOf(groupIds);
        groupNames = Map.copyOf(groupNames);
        eligibleSubjects = eligibleSubjects.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        confirmedDepartures = Set.copyOf(confirmedDepartures);
    }

    public boolean freshAt(Instant now) {
        return !startedAt.isAfter(now) && now.isBefore(startedAt.plus(MAX_AGE));
    }
}
