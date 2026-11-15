import java.nio.file.Path;

class GitCloneUrlExamples {

    String handBuilt(String serverUrl, String nameWithOwner) {
        // ruleid: java-hand-built-git-clone-url
        String gitlab = serverUrl + "/" + nameWithOwner + ".git";
        // ruleid: java-hand-built-git-clone-url
        String github = "https://github.com/" + nameWithOwner + ".git";
        return gitlab + github;
    }

    String[] segmented(String nameWithOwner) {
        String[] path = nameWithOwner.split("/", -1);
        // ruleid: java-hand-built-git-clone-url
        path[path.length - 1] += ".git";
        return path;
    }

    Path mirror(Path root, long repositoryId) {
        // ok: java-hand-built-git-clone-url
        return root.resolve(repositoryId + ".git");
    }
}
