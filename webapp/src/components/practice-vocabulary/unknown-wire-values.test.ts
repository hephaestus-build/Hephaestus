import { describe, expect, it } from "vitest";

import {
	WITHDRAWN_EVENT_LABEL,
	withdrawnEvents,
} from "@/components/admin/practice-editor/review-events";
import { facetPills } from "@/components/admin/practice-reviews/AppliedFacetPills";
import { itemKindLabel, itemKindNoun, itemName } from "@/lib/item-kinds";
import { mockDocumentItemType } from "@/mocks/fixtures/practice";

import { isKnownValue, UNKNOWN_STATUS_DEF } from "@/components/common/status-def";

import { claimCurrentnessNote, currentnessCopy, UNKNOWN_CURRENTNESS } from "./claim-currentness";
import { evidenceSourceDef } from "./evidence-source-defs";
import { copyKindsPhrase } from "./feedback-copy-defs";
import { adminWithheldText } from "./feedback-destination-defs";
import { itemEventReasonSentence } from "./item-event-defs";
import {
	partialReviewScope,
	REVIEW_STATUS_DEFS,
	reviewStopReasonSentence,
	shownReviewStatusDef,
} from "./review-status-defs";

// Provider vocabularies can reach the client before labels do; unknown values must not become misleading copy.
describe("a value this build has no words for", () => {
	const unknownKind = "tracker.ticket";

	it("names a kind of work generically, in every form", () => {
		expect(itemKindLabel(unknownKind)).toBe("Other work");
		expect(itemKindLabel(unknownKind, 2)).toBe("Other work");
		expect(itemKindNoun(unknownKind, 1)).toBe("piece of work");
		expect(itemKindNoun(unknownKind, 3)).toBe("pieces of work");
		expect(itemName({ kind: unknownKind, label: "T-88" })).toBe("Other work");
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

// The API types these enums as open strings: a newer server can send a value that this build has no
// words for, and each surface says so instead of showing nothing or crashing.
describe("an open enum value that this build does not know", () => {
	const value = "SOMETHING_NEW";

	it("is not mistaken for a known one", () => {
		expect(isKnownValue(REVIEW_STATUS_DEFS, value)).toBe(false);
		expect(isKnownValue(REVIEW_STATUS_DEFS, "QUEUED")).toBe(true);
	});

	it("shows a review status as Unknown", () => {
		expect(shownReviewStatusDef(REVIEW_STATUS_DEFS, value, undefined)).toBe(UNKNOWN_STATUS_DEF);
		expect(UNKNOWN_STATUS_DEF.label).toBe("Unknown");
	});

	it("says that a stop reason, or the reason a review did not start, is not recognized", () => {
		expect(reviewStopReasonSentence(value)).toBe(
			"It stopped for a reason that this version of Hephaestus does not recognize.",
		);
		expect(itemEventReasonSentence(value)).toBe(
			"It did not start, for a reason that this version of Hephaestus does not recognize.",
		);
	});

	it("names no scope for a review kind", () => {
		expect(partialReviewScope(value)).toBeUndefined();
		expect(partialReviewScope("SCAN")).toBe("Its observations cover only the work it reached.");
	});

	it("reads an observation's currentness as unknown, never as current", () => {
		expect(currentnessCopy({ currentness: value })).toBe(UNKNOWN_CURRENTNESS);
		expect(UNKNOWN_CURRENTNESS.badge).toBe("Status unknown");
		expect(claimCurrentnessNote({ currentness: value })).toBe(
			"This version of Hephaestus cannot tell whether this observation is current.",
		);
	});

	it("keeps the text of feedback for an unknown destination withheld from admins", () => {
		expect(adminWithheldText(value)).toStrictEqual({
			title: "Text is withheld",
			detail:
				"This version of Hephaestus does not recognize where this feedback goes, so it does not show the text.",
		});
		expect(adminWithheldText("ON_WORK")).toBeUndefined();
		expect(adminWithheldText("PRACTICE_PAGE")?.title).toBe("Text is private to the developer");
	});

	it("counts a copy of an unknown kind as a copy of another kind", () => {
		expect(copyKindsPhrase([{ kind: value }, { kind: "SUMMARY" }])).toBe(
			"a copy of another kind and a summary",
		);
	});
});
