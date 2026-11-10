package de.tum.cit.aet.hephaestus.evidence.capture;

import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What any server needs to build a captured checkout again: the repository, the commit and the tree it checked out.
 * The commit and the tree are the checkout's identity; the checkout digest that the capture records is the second
 * check. A rebuild holds the commit's history only: the branches and tags that the capture saw stay a recorded file.
 *
 * @param review the review whose provider ref reaches a commit that no branch holds, such as a fork's head
 * @param paths the files of a scan's partial checkout, which has no {@code .git}; null for the whole tree
 */
public record CheckoutKey(
        long repositoryId,
        String commit,
        String tree,
        @Nullable Integer review,
        @Nullable List<String> paths) {
    private static final Pattern OBJECT_ID = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    public CheckoutKey {
        if (repositoryId <= 0
                || !OBJECT_ID.matcher(commit).matches()
                || !OBJECT_ID.matcher(tree).matches()) throw new IllegalArgumentException("Invalid checkout key");
        paths = paths == null ? null : paths.stream().sorted().distinct().toList();
    }
}
