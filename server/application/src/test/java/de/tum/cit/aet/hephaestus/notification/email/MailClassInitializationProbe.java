package de.tum.cit.aet.hephaestus.notification.email;

import de.tum.cit.aet.hephaestus.testconfig.PinnedParks;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import jdk.jfr.consumer.RecordingStream;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Runs in a JVM of its own, where no class of Jakarta Mail is initialized yet. Sixteen virtual threads start the
 * delivery path together, and the probe prints each park that pinned one of them. With {@code initialized}, the main
 * thread takes that path first, as the adapter's constructor does.
 */
final class MailClassInitializationProbe {

    private MailClassInitializationProbe() {}

    @FunctionalInterface
    private interface Work {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        // The adapter's class and the harness are initialized before the recording, in both runs: their first use on
        // racing virtual threads would pin too. Only the mail classes differ between the runs.
        Class.forName(EmailAdapter.class.getName());
        together(() -> {});
        if (args.length > 0 && args[0].equals("initialized")) deliver();
        List<String> pinned = new CopyOnWriteArrayList<>();
        try (var events = new RecordingStream()) {
            events.enable("jdk.VirtualThreadPinned")
                    .withThreshold(Duration.ZERO)
                    .withStackTrace();
            events.onEvent("jdk.VirtualThreadPinned", event -> {
                if (!PinnedParks.ownedByJdk(event.getStackTrace())) pinned.add(String.valueOf(event.getStackTrace()));
            });
            events.startAsync();
            together(MailClassInitializationProbe::deliver);
            events.stop();
        }
        System.out.println("pinned parks: " + pinned.size());
        pinned.forEach(System.out::println);
    }

    /** One sender, as the server has, whose session it creates on first use. */
    private static final JavaMailSender SENDER = new JavaMailSenderImpl();

    /** The path of a delivery to the network: the adapter's own composition, a write and the transport. */
    private static void deliver() {
        EmailAdapter.initializeMailClasses(SENDER, "hephaestus@example.invalid", "Hephaestus", null);
    }

    /** Starts {@code work} on sixteen virtual threads at once and waits for all of them. */
    private static void together(Work work) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> runs = new ArrayList<>();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 16; i++) {
                runs.add(threads.submit(() -> {
                    start.await();
                    work.run();
                    return null;
                }));
            }
            start.countDown();
        }
        for (Future<?> run : runs) run.get();
    }
}
