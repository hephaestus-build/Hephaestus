package de.tum.cit.aet.hephaestus.practices.spi;

import org.jspecify.annotations.Nullable;

/**
 * What a practice start found about the sources that its capture read.
 *
 * @param unreadReason why the sources could not be compared, such as the capture code of a failed read; never null when
 *     {@code currentness} is {@link Currentness#UNVERIFIABLE}
 */
public record PracticeStartCheck(
        Currentness currentness, @Nullable String unreadReason) {
    public PracticeStartCheck {
        if (currentness == Currentness.UNVERIFIABLE && unreadReason == null)
            throw new IllegalArgumentException("An unverifiable start names why");
    }

    public static PracticeStartCheck current() {
        return new PracticeStartCheck(Currentness.CURRENT, null);
    }
}
