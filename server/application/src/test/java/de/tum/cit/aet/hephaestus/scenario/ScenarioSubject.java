package de.tum.cit.aet.hephaestus.scenario;

/**
 * The system that a scenario drives. It learns the world only from steps, keeps its own state, and acts only through
 * the harness: it opens runs, reports reads, calls the model, writes entries and queues and sends effects.
 */
@FunctionalInterface
public interface ScenarioSubject {

    void accept(Step step, Harness harness);
}
