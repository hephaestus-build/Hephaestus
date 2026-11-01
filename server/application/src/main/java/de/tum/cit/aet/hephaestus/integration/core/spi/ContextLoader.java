package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Stages a heavy item into Heph's context folder when the model asks for it with {@code load_context}. Core routes a
 * path to the loader whose {@link #prefix} it starts with, and knows nothing else about the item. Each call acts for
 * one developer in one workspace, for conversational mentoring, and applies the same visibility rules as the
 * integration's context documents.
 */
public interface ContextLoader {

    /** The folder path this loader owns, ending in {@code /}, such as {@code repos/}. */
    String prefix();

    /**
     * This loader's section of the folder's {@code INDEX.md}: what the developer can load, as Markdown that starts
     * with a level-two heading. Empty when there is nothing to load.
     */
    String index(long workspaceId, long accountId);

    /** Canonical destinations that the worker may prepare for this account's tools Job. */
    List<String> destinations(long workspaceId, long accountId);

    /**
     * One compact line for the turn's situation, at most {@link #SITUATION_CHARS} long: the items the developer most
     * likely means, so that the model can load one in its first round. Empty when there is none.
     */
    default String situation(long workspaceId, long accountId) {
        return "";
    }

    int SITUATION_CHARS = 600;

    /**
     * Prepares the item at {@code path} for staging. The caller stages {@link Loaded#directory()} at {@link
     * Loaded#path()} and closes the result.
     *
     * @param path the path the model asked for, starting with {@link #prefix}, with an optional {@code @version}
     * @param abandoned once it holds, the loader starts no further work and throws {@code CancellationException}
     * @throws ContextLoadException when the item cannot be loaded
     */
    Loaded load(long workspaceId, long accountId, String path, BooleanSupplier abandoned);

    /** What a load resolved: the version asked for, its exact id, and how the model cites it. */
    record Version(String ref, String id, String cite) {}

    /** A prepared item; {@link #close()} deletes its files. */
    interface Loaded extends AutoCloseable {
        /** Where it goes in the folder, without a version: {@code repos/github.com/acme/api}. */
        String path();

        Path directory();

        Version version();

        /** What the model reads about the staged item. */
        String note();

        /**
         * A command, without a shell, that the folder runs as the sandbox user in the staged item's directory after
         * staging, under the turn's deadline. Its output follows {@link #note()}. Empty for none.
         */
        default List<String> summary() {
            return List.of();
        }

        @Override
        void close();
    }
}
