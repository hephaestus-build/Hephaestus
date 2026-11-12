package de.tum.cit.aet.hephaestus.testconfig;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.context.Lifecycle;

/** Exercises work after the same stop/start pair that a context pause and resume calls. */
public final class LifecycleTestSupport {

    private LifecycleTestSupport() {}

    /** The caller proves useful work still succeeds after the bean resumes. */
    public static void assertResumes(Lifecycle lifecycle, ThrowingCallable work) throws Throwable {
        lifecycle.stop();
        lifecycle.start();
        work.call();
    }
}
