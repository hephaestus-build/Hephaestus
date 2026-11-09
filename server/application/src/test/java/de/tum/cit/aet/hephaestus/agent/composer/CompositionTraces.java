package de.tum.cit.aet.hephaestus.agent.composer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.CompositionToolTrace;
import java.util.ArrayList;
import java.util.List;

/** A composition tool trace that keeps its decisions in memory, as one attempt's trace holds them. */
final class CompositionTraces {
    private CompositionTraces() {}

    static CompositionToolTrace inMemory() {
        var trace = mock(CompositionToolTrace.class);
        var decisions = new ArrayList<CompositionToolTrace.Decision>();
        doAnswer(call -> decisions.add(call.getArgument(5)))
                .when(trace)
                .record(any(), any(), any(), any(), any(), any());
        when(trace.decisions(any())).thenAnswer(call -> List.copyOf(decisions));
        return trace;
    }
}
