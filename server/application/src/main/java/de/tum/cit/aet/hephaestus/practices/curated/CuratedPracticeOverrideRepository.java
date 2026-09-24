package de.tum.cit.aet.hephaestus.practices.curated;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

@org.springframework.stereotype.Repository
@WorkspaceAgnostic("The instance catalog is global")
public interface CuratedPracticeOverrideRepository extends Repository<CuratedPracticeOverride, String> {
    CuratedPracticeOverride save(CuratedPracticeOverride override);

    void delete(CuratedPracticeOverride override);

    List<CuratedPracticeOverride> findAll();

    Optional<CuratedPracticeOverride> findBySlug(String slug);

    @Query("SELECT c.slug FROM CuratedPracticeOverride c WHERE c.name IS NOT NULL "
            + "AND c.acceptedBundledDigest IS NOT NULL AND c.adoptedBase IS NULL")
    List<String> findSlugsMissingAdoptedBase();
}
