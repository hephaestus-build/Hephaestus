import { describe, expect, it } from "vitest";

import {
	WITHDRAWN_EVENT_LABEL,
	withdrawnEvents,
} from "@/components/admin/practice-editor/review-events";
import { facetPills } from "@/components/admin/practice-reviews/AppliedFacetPills";
import { itemKindLabel, itemKindNoun, itemName } from "@/lib/item-kinds";
import { mockDocumentItemType } from "@/mocks/fixtures/practice";

import { evidenceSourceDef } from "./evidence-source-defs";

// Provider vocabularies can reach the client before labels do; unknown values must not become misleading copy.
describe("a value this build has no words for", () => {
	const unknownKind = "tracker.ticket";

	it("names a kind of work generically, in every form", () => {
		expect(itemKindLabel(unknownKind)).toBe("Other work");
		expect(itemKindLabel(unknownKind, 2)).toBe("Other work");
		expect(itemKindNoun(unknownKind, 1)).toBe("piece of work");
		expect(itemKindNoun(unknownKind, 3)).toBe("pieces of work");
		expect(itemName({ kind: unknownKind, label: "T-88", provider: null })).toBe("Other work");
	});

	it("names an event generically", () => {
		const [withdrawn] = withdrawnEvents(mockDocumentItemType, ["docs.document.forked"]);
		expect(withdrawn?.label).toBe(WITHDRAWN_EVENT_LABEL);
	});

	it("names a read source generically", () => {
		expect(evidenceSourceDef("scm.future-source").label).toBe("Another source");
	});

	it("keeps a filter pill whose value the options cannot name, without printing the value", () => {
		const [pill] = facetPills("Group", [], ["code-quality"], () => undefined);
		expect(pill?.label).toBe("Selected");
	});
});

describe("the words for a kind of work", () => {
	it("says pull or merge request until the provider decides", () => {
		expect(itemKindNoun("scm.pull_request", 1)).toBe("pull or merge request");
		expect(itemKindNoun("scm.pull_request", 2, "GITHUB")).toBe("pull requests");
		expect(itemKindNoun("scm.pull_request", 1, "GITLAB")).toBe("merge request");
		expect(itemKindLabel("scm.pull_request", 1, "GITLAB")).toBe("Merge request");
		expect(itemKindLabel("scm.pull_request", 2)).toBe("Pull or merge requests");
	});

	it("names numbered work the way its provider writes it, and other work by its kind", () => {
		expect(itemName({ kind: "scm.pull_request", provider: "GITHUB", label: "#1423" })).toBe(
			"Pull request #1423",
		);
		expect(itemName({ kind: "scm.pull_request", provider: "GITLAB", label: "!1423" })).toBe(
			"Merge request !1423",
		);
		expect(itemName({ kind: "scm.issue", provider: "GITLAB", label: "#7" })).toBe("Issue #7");
		expect(itemName({ kind: "docs.document", label: "Onboarding", provider: "OUTLINE" })).toBe(
			"Document",
		);
	});

	it("names work whose kind is missing as reviewed work", () => {
		expect(itemKindLabel(undefined)).toBe("Reviewed work");
		expect(itemKindNoun(undefined, 2)).toBe("pieces of reviewed work");
	});
});

describe("the words for the kinds a scan and a reviewer read", () => {
	it("names a pull request review by its provider's noun", () => {
		expect(itemKindLabel("scm.pull_request_review", 1, "GITHUB")).toBe("Pull request review");
		expect(itemKindLabel("scm.pull_request_review", 1, "GITLAB")).toBe("Merge request review");
	});

	it("names a repository and a file by their labels", () => {
		expect(
			itemName({ kind: "scm.repository", provider: "GITHUB", label: "ls1intum/Hephaestus" }),
		).toBe("Repository ls1intum/Hephaestus");
		expect(itemName({ kind: "scm.file", provider: "GITLAB", label: "src/app.ts" })).toBe(
			"File src/app.ts",
		);
	});
});
