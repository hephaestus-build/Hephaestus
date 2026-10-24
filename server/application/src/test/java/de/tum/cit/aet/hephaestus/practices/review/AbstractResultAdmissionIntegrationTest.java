package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustodyQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAttemptGuard;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBudget;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewModelBindings;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.RecordApplicationEvents;

@RecordApplicationEvents
public abstract class AbstractResultAdmissionIntegrationTest extends BaseIntegrationTest {
    @MockitoSpyBean
    protected ProductItemQuery productItems;

    @MockitoBean
    protected ReviewAttemptGuard ownership;

    @MockitoBean
    protected ReviewRuntimeQuery runtime;

    @MockitoBean
    protected ReviewModelBindings models;

    @MockitoBean
    protected ReviewBudget budget;

    @MockitoBean
    protected HistoryCustodyQuery custody;
}
