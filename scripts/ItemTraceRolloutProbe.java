import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import java.util.Map;
import org.springframework.data.projection.SpelAwareProxyProjectionFactory;

// Compile ReviewRequestReason from v1.0.0-rc.19 and put it and Spring Data Commons on the classpath.
// Run with COALESCED to check the previous writer, then DUPLICATE_VERSION to check the proposed writer.
class ItemTraceRolloutProbe {
    interface EventRow {
        ReviewRequestReason getReason();
    }

    public static void main(String[] args) {
        var factory = new SpelAwareProxyProjectionFactory();
        var row = factory.createProjection(EventRow.class, Map.of("reason", args[0]));
        System.out.println(row.getReason());
    }
}
