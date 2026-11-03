package de.tum.cit.aet.hephaestus.contributors;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Hephaestus project's contributors, as the packaging build recorded them from GitHub. The list ships in
 * the JAR, so the About page reads no provider at run time. A build that recorded none, such as a local
 * build, lists nobody.
 */
@Service
@WorkspaceAgnostic("Hephaestus project contributors - meta info, not tenant data")
public class ContributorService {

    static final String RESOURCE = "contributors.json";

    private final List<ContributorDTO> contributors;

    @Autowired
    public ContributorService(JsonMapper mapper) {
        this(mapper, new ClassPathResource(RESOURCE));
    }

    ContributorService(JsonMapper mapper, Resource resource) {
        this.contributors = resource.exists() ? List.of(read(mapper, resource)) : List.of();
    }

    /** By contribution count, highest first, as the packaging build sorted them. */
    public List<ContributorDTO> getGlobalContributors() {
        return contributors;
    }

    private static ContributorDTO[] read(JsonMapper mapper, Resource resource) {
        try (InputStream input = resource.getInputStream()) {
            return mapper.readValue(input, ContributorDTO[].class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + RESOURCE, e);
        }
    }
}
