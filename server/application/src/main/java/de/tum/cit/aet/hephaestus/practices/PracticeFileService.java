package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogPracticeDocument;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeFileCheckDTO;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Reads a workspace practice out as a folder of files, and checks a folder without saving it (ADR 0118). */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class PracticeFileService {
    private final PracticeService practices;
    private final PracticeFiles files;
    private final JsonMapper json;

    /** The files of the practice's current version, by path relative to its folder. */
    @Transactional(readOnly = true)
    public Map<String, String> export(WorkspaceContext ctx, String slug) {
        return folder(ctx, slug);
    }

    private Map<String, String> folder(WorkspaceContext ctx, String slug) {
        var practice = practices.getPractice(ctx, slug);
        var definition = json.convertValue(practice.getCurrentVersion().getDefinition(), PracticeDefinition.class);
        var group = practice.getGroup() == null ? null : practice.getGroup().getSlug();
        return files.write(new CatalogPracticeDocument(practice.getKind(), group, definition));
    }

    /** The practice's folder as a ZIP archive whose entries sit under {@code <slug>/}. */
    @Transactional(readOnly = true)
    public byte[] zip(WorkspaceContext ctx, String slug) {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var file : folder(ctx, slug).entrySet()) {
                zip.putNextEntry(new ZipEntry(slug + "/" + file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return bytes.toByteArray();
    }

    /** Parses and validates a folder as the practice {@code slug}. Saves nothing and calls no model. */
    public PracticeFileCheckDTO check(String slug, Map<String, String> folder) {
        var read = files.read(slug, folder);
        var document = read.document();
        return document == null
                ? new PracticeFileCheckDTO(false, null, null, null, read.problems())
                : new PracticeFileCheckDTO(
                        true,
                        document.definition().name(),
                        document.kind().value(),
                        document.groupSlug(),
                        read.problems());
    }
}
