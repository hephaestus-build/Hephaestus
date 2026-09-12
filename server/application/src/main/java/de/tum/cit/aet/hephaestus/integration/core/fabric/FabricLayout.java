package de.tum.cit.aet.hephaestus.integration.core.fabric;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Worker-local Git operation spool and active attempt folders; neither is durable application state. */
@Component
public class FabricLayout {

    private static final String JOBS = "jobs";

    private final Path root;

    public FabricLayout(@Value("${hephaestus.fabric.root:/data/git-repos}") String root) {
        this.root = Path.of(root);
    }

    public Path root() {
        return root;
    }

    public Path jobsRoot() {
        return root.resolve(JOBS);
    }
}
