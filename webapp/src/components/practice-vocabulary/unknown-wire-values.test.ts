import { describe, expect, it } from "vitest";

import { evidenceSourceLabel } from "@/components/admin/practice-editor/evidence-presentation";
import {
	WITHDRAWN_MOMENT_LABEL,
	withdrawnMoments,
} from "@/components/admin/practice-editor/occasion-moments";
import { facetPills } from "@/components/admin/practice-reviews/AppliedFacetPills";
import { holdReasonCopy } from "@/components/admin/practice-reviews/job-utils";
import { artifactKindLabel, artifactKindNoun, reviewedWorkName } from "@/lib/artifact-kinds";
import { mockDocumentWorkType } from "@/mocks/fixtures/practice";

import { evidenceSourceDef } from "./evidence-source-defs";

/**
 * The server's vocabularies are open: a kind of work, a moment, an evidence source or a hold reason
 * can reach this build before it has words for them. Each helper below must still answer in words,
 * and none of them may hand the reader the id it was given.
 */
describe("a value this build has no words for", () => {
	const unknownKind = "tracker.ticket";

	it("names a kind of work generically, in every form", () => {
		expect(artifactKindLabel(unknownKind)).toBe("Other work");
		expect(artifactKindLabel(unknownKind, 2)).toBe("Other work");
		expect(artifactKindNoun(unknownKind, 1)).toBe("piece of work");
		expect(artifactKindNoun(unknownKind, 3)).toBe("pieces of work");
		expect(reviewedWorkName({ kind: unknownKind, label: "T-88" })).toBe("Other work");
	});

	it("names a moment generically", () => {
		const [withdrawn] = withdrawnMoments(mockDocumentWorkType, ["docs.document.forked"]);
		expect(withdrawn?.displayName).toBe(WITHDRAWN_MOMENT_LABEL);
	});

	it("names an evidence source generically, the same way in the editor and on the evidence", () => {
		expect(evidenceSourceDef("scm.future-source").label).toBe("Another source");
		expect(evidenceSourceLabel("scm.future-source", [])).toBe("Another source");
	});

	it("names a hold generically", () => {
		expect(holdReasonCopy("PROVIDER_OUTAGE").label).toBe("On hold");
	});

	it("keeps a filter pill whose value the options cannot name, without printing the value", () => {
		const [pill] = facetPills("Group", [], ["code-quality"], () => undefined);
		expect(pill?.label).toBe("Selected");
	});
});

describe("the words for a kind of work", () => {
	it("says pull or merge request until the provider decides", () => {
		expect(artifactKindNoun("scm.pull_request", 1)).toBe("pull or merge request");
		expect(artifactKindNoun("scm.pull_request", 2, "GITHUB")).toBe("pull requests");
		expect(artifactKindNoun("scm.pull_request", 1, "GITLAB")).toBe("merge request");
		expect(artifactKindLabel("scm.pull_request", 1, "GITLAB")).toBe("Merge request");
		expect(artifactKindLabel("scm.pull_request", 2)).toBe("Pull or merge requests");
	});

	it("names numbered work the way its provider writes it, and other work by its kind", () => {
		expect(reviewedWorkName({ kind: "scm.pull_request", provider: "GITHUB", label: "#1423" })).toBe(
			"Pull request #1423",
		);
		expect(reviewedWorkName({ kind: "scm.pull_request", provider: "GITLAB", label: "!1423" })).toBe(
			"Merge request !1423",
		);
		expect(reviewedWorkName({ kind: "scm.issue", provider: "GITLAB", label: "#7" })).toBe(
			"Issue #7",
		);
		expect(reviewedWorkName({ kind: "docs.document", label: "Onboarding" })).toBe("Document");
	});

	it("names work whose kind is missing as reviewed work", () => {
		expect(artifactKindLabel(undefined)).toBe("Reviewed work");
		expect(artifactKindNoun(undefined, 2)).toBe("pieces of reviewed work");
	});
});
