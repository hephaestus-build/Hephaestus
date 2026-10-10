package de.tum.cit.aet.hephaestus.core.auth.spi;

public interface AccountPublicActivity {
    boolean visible(long accountId);

    boolean setVisible(long accountId, boolean visible);
}
