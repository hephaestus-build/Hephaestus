package de.tum.cit.aet.hephaestus.core.authority;

/** A boundary refused work whose authority narrowed. The caller stops the work and records the refusal's reason. */
public class AuthorityRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient AuthorityRefusal refusal;

    public AuthorityRefusedException(AuthorityRefusal refusal) {
        super("Authority narrowed before " + refusal.boundary() + ": " + refusal.reason());
        this.refusal = refusal;
    }

    public AuthorityRefusal refusal() {
        return refusal;
    }
}
