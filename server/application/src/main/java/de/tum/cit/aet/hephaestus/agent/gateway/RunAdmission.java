package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import java.util.List;
import java.util.Set;

/** Whether a due run may get its sandbox now. One dispatch pass asks it once for each due run, in the claim's order. */
public interface RunAdmission {
    /** Admits every run. */
    RunAdmission ALL = kinds -> due -> true;

    /** The decisions of one dispatch pass over runs of these kinds; a pass may remember what it admitted so far. */
    Pass pass(Set<AgentRunKind> kinds);

    interface Pass {
        boolean admits(AgentRuns.DueRun due);

        /** The next page of due runs, before the pass asks about any of them, so that it can read them together. */
        default void read(List<AgentRuns.DueRun> page) {}

        /** The pass read every due run it was going to read. */
        default void ended() {}
    }
}
