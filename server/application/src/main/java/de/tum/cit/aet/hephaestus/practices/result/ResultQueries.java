package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.spi.ResultSort;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ResultQueries {
    Page<Result> list(
            long workspaceId, ResultQueryFilter filter, ResultSort sort, boolean displayableOnly, Pageable page);

    Page<Problem> problems(long workspaceId, ResultQueryFilter filter, ProblemQueryFilter child, Pageable page);

    List<Result> forDevelopersSince(long workspaceId, Collection<Long> developers, Instant since, boolean currentOnly);

    /** The developers with a result recorded from {@code since}, whatever its outcome or currentness. */
    List<Long> developersSince(long workspaceId, Instant since);

    List<Result> ordered(long workspaceId, List<UUID> ids);

    /**
     * Each of {@code reviewIds}' place among the developer's reviews of its work: the reviews of the
     * same work that recorded a result about the developer, oldest first, whatever a list's filters
     * keep. By review id.
     */
    Map<UUID, PlaceOnWork> placesOnWork(long workspaceId, long developerId, Collection<UUID> reviewIds);

    /** A review's place among its work's reviews, from 1, and how many there are. */
    record PlaceOnWork(int position, int total) {}
}
