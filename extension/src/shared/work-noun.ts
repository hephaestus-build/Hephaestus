import type { ReviewedWorkRef } from "~/api/types.gen";

/** How the work names itself on its provider: a GitLab `scm.pull_request` is a merge request. */
export function workNoun(work: Pick<ReviewedWorkRef, "kind" | "provider">): string {
	if (work.kind === "scm.pull_request") {
		return work.provider === "GITLAB" ? "merge request" : "pull request";
	}
	return work.kind === "scm.issue" ? "issue" : "work";
}
