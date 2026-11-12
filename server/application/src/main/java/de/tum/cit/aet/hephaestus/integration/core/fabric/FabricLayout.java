package de.tum.cit.aet.hephaestus.integration.core.fabric;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Server-local Git operation spool and active attempt folders; neither is durable application state. */
@Component
public class FabricLayout {

    private static final String JOBS = "jobs";

    private final Path root;
    private final EvidenceSpool spool;

    public FabricLayout(@Value("${hephaestus.fabric.root:/data/git-repos}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.spool = new EvidenceSpool(this.root);
    }

    public Path root() {
        return root;
    }

    public EvidenceSpool spool() {
        return spool;
    }

    public Path jobsRoot() {
        return root.resolve(JOBS);
    }
}
