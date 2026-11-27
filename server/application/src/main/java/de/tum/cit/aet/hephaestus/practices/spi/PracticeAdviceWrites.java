package de.tum.cit.aet.hephaestus.practices.spi;

/**
 * The mentor's review handler writes lasting advice through this port (ADR 0160). Each write touches only advice rows;
 * observations, problems and evidence stay as they are.
 */
public interface PracticeAdviceWrites {
    AdviceWrite revise(ReviseAdvice command);

    AdviceWrite retire(RetireAdvice command);
}
