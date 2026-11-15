package de.tum.cit.aet.hephaestus.integration.slack.messaging;

/** The monotonic time that a throttle budget counts, and the wait it takes. A test passes its own virtual time. */
interface SlackTime {

    SlackTime SYSTEM = new SlackTime() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public void sleep(long millis) throws InterruptedException {
            Thread.sleep(millis);
        }
    };

    long nanoTime();

    void sleep(long millis) throws InterruptedException;
}
