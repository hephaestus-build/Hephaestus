import { GitPullRequestIcon } from "@primer/octicons-react";
import { FileTextIcon } from "lucide-react";
import { describe, expect, it } from "vitest";

import { GitLabIcon } from "@/components/icons/brand";
import { reviewedWorkIcon } from "@/components/icons/reviewed-work-icon";
import { ARTIFACT_KIND } from "@/lib/artifact-kinds";

describe("reviewedWorkIcon", () => {
	it("draws work with its provider's mark rather than its kind's glyph", () => {
		expect(reviewedWorkIcon(ARTIFACT_KIND.pullRequest, "GITLAB")).toBe(GitLabIcon);
	});

	it("falls back to the kind's glyph when the provider is unknown", () => {
		expect(reviewedWorkIcon(ARTIFACT_KIND.pullRequest)).toBe(GitPullRequestIcon);
		expect(reviewedWorkIcon(undefined)).toBe(FileTextIcon);
	});
});
