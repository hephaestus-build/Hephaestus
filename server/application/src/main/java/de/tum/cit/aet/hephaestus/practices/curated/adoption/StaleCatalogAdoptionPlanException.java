package de.tum.cit.aet.hephaestus.practices.curated.adoption;

import java.io.Serial;

public class StaleCatalogAdoptionPlanException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public StaleCatalogAdoptionPlanException() {
        super("The practice or its workspace adoption outcome changed. Review the current definition before adopting.");
    }

    public StaleCatalogAdoptionPlanException(Throwable cause) {
        super(
                "The practice or its workspace adoption outcome changed. Review the current definition before adopting.",
                cause);
    }
}
