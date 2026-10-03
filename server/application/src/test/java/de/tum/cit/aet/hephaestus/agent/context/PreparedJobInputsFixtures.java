package de.tum.cit.aet.hephaestus.agent.context;

import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.jspecify.annotations.Nullable;
import org.mockito.Mockito;

/** Disk fixtures for unit callers; production only freezes folders through JobEvidenceFiles. */
public final class PreparedJobInputsFixtures {
    private PreparedJobInputsFixtures() {}

    public static PreparedJobInputs filesOnly(Map<String, byte[]> files) {
        return inputs(new PreparedEvidence(files, null), null);
    }

    public static PreparedJobInputs inputs(PreparedEvidence evidence, @Nullable AutomatedReviewReadinessReport report) {
        try {
            var root = Files.createTempDirectory("job-folder-fixture-");
            var paths = new LinkedHashMap<>(evidence.filesOnDisk());
            for (var entry : evidence.files().entrySet()) {
                var target = root.resolve(Integer.toString(paths.size()));
                Files.createDirectories(target.getParent());
                Files.write(target, entry.getValue());
                paths.put(entry.getKey(), target);
            }
            var cleanups = new ArrayList<>(evidence.cleanups());
            cleanups.add(() -> FileUtils.deleteDirectory(root.toFile()));
            return new PreparedJobInputs(paths, evidence.directories(), cleanups, evidence.manifest(), report);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public static Map<String, byte[]> files(PreparedJobInputs inputs) {
        var files = new LinkedHashMap<String, byte[]>();
        try {
            for (var entry : inputs.filesOnDisk().entrySet())
                files.put(entry.getKey(), Files.readAllBytes(entry.getValue()));
            return files;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public static PreparedJobInputs prepare(JobEvidenceFiles files, AgentJob job, PreparedJobInputs inputs) {
        return files.prepare(
                job,
                new PreparedEvidence(
                        Map.of(), inputs.filesOnDisk(), inputs.cleanups(), inputs.folderIndex(), inputs.directories()),
                inputs.automatedReviewReadinessReport());
    }

    public static JobEvidenceFiles freezer() {
        return mock(JobEvidenceFiles.class, call -> {
            if (call.getMethod().getName().equals("prepare")) return inputs(call.getArgument(1), call.getArgument(2));
            return Mockito.RETURNS_DEFAULTS.answer(call);
        });
    }
}
