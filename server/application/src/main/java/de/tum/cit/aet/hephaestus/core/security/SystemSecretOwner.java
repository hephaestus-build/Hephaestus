package de.tum.cit.aet.hephaestus.core.security;

/** A module that stores values sealed by {@link SystemSecrets}. {@link SystemKeyRotation} calls each one. */
public interface SystemSecretOwner {

    /**
     * Locks every row that holds a system secret and seals each value again under the active key of {@code secrets}, in
     * the caller's transaction. A value that already uses that key stays unchanged, so a second call changes nothing.
     *
     * @return the number of values sealed again
     */
    int resealWithActiveKey(SystemSecrets secrets);
}
