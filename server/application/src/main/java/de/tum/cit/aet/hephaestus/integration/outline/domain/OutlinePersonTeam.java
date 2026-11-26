package de.tum.cit.aet.hephaestus.integration.outline.domain;

/** Match an empty or exact person team key after binding the Outline provider instance. */
public final class OutlinePersonTeam {

    private static final String MATCH = " IN ('', ";
    public static final String SQL_MATCH = MATCH + "c.instance_key)";
    public static final String JPQL_MATCH = MATCH + "c.instanceKey)";

    private OutlinePersonTeam() {}
}
