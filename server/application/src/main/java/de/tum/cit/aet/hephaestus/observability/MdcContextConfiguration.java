package de.tum.cit.aet.hephaestus.observability;

import de.tum.cit.aet.hephaestus.core.runtime.ReceiverComponent;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

@Configuration
@ReceiverComponent
public class MdcContextConfiguration {

    @PostConstruct
    void register() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new Slf4jThreadLocalAccessor());
    }
}
