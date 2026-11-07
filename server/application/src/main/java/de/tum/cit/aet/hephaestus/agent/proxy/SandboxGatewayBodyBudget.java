package de.tum.cit.aet.hephaestus.agent.proxy;

import java.util.concurrent.Semaphore;

/**
 * The request bodies that the gateway holds at once. A model call keeps its body until its stream ends, and connections
 * alone do not bound that: each body may be as large as the gateway's request limit.
 */
final class SandboxGatewayBodyBudget {

    private static final long KIB = 1024;

    private final Semaphore kibibytes;

    SandboxGatewayBodyBudget(long bytes) {
        this.kibibytes = new Semaphore(Math.toIntExact(bytes / KIB));
    }

    /** A quarter of the heap: a streamed model call can hold its body and two serialized copies of it. */
    static SandboxGatewayBodyBudget ofHeap() {
        return new SandboxGatewayBodyBudget(Runtime.getRuntime().maxMemory() / 4);
    }

    boolean tryHold(long bytes) {
        return kibibytes.tryAcquire(permits(bytes));
    }

    void release(long bytes) {
        kibibytes.release(permits(bytes));
    }

    private static int permits(long bytes) {
        return Math.toIntExact((bytes + KIB - 1) / KIB);
    }
}
