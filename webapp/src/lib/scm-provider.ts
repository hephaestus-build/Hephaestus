// Kept apart from the hook: the Source control route's `head` reads it, and route options load with
// the app entry, so this module must import no component.
type ScmKind = "GITHUB" | "GITLAB";

/** Which source-control provider a workspace kind names, and its display name once that is known. */
export function scmProviderOf(workspaceKind: string | undefined): {
	kind: ScmKind | undefined;
	label: string;
} {
	if (workspaceKind === undefined) {
		return { kind: undefined, label: "Source control" };
	}
	if (workspaceKind === "GITLAB") {
		return { kind: "GITLAB", label: "GitLab" };
	}
	return { kind: "GITHUB", label: "GitHub" };
}
