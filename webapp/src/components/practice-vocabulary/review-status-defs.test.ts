import { describe, expect, it } from "vitest";

import {
	knownReasonSentence,
	withheldSentence,
} from "@/components/admin/practice-scans/scan-display";

import { reviewReasonSentence } from "./review-status-defs";

describe("legacy withholding reasons", () => {
	it("describes retained ambiguity without selecting a result", () => {
		expect(reviewReasonSentence("LEGACY_AMBIGUOUS_RESULTS")).toBe(
			"This review keeps several historical observations for the same practice and work without choosing one as its answer.",
		);
		expect(withheldSentence("LEGACY_AMBIGUOUS_RESULTS")).toBe(
			reviewReasonSentence("LEGACY_AMBIGUOUS_RESULTS"),
		);
	});
	it("describes an unbound historical reuse through the same registry", () => {
		expect(withheldSentence("LEGACY_REUSE_UNBOUND")).toBe(
			"This review referred to an earlier observation that could not be identified exactly.",
		);
	});
	it("describes a practice whose evidence settings cannot be used for this work", () => {
		expect(withheldSentence("source_contract_unsupported")).toBe(
			"Hephaestus cannot use this practice’s evidence settings for this work. Ask a workspace admin to check them.",
		);
	});
	it("describes a practice whose Review on or About settings do not fit this kind of work", () => {
		expect(withheldSentence("item_capability_unsupported")).toBe(
			"Hephaestus cannot use this practice’s Review on or About settings for this kind of work. Ask a workspace admin to check them.",
		);
	});
	it.each([
		["readiness_refused", "Evidence that this practice requires could not be captured."],
		[
			"ROLE_UNAVAILABLE",
			"No eligible person holds the role that this practice is about on this work.",
		],
		[
			"ROLE_AMBIGUOUS",
			"More than one eligible person holds the role that this practice is about, and Hephaestus does not guess which one.",
		],
		["OUTSIDE_REVIEW_SCOPE", "This work or this person is outside the workspace’s review scope."],
		[
			"CHECKS_PENDING",
			"Not reviewed yet: this practice needs the checks of this commit, and they had not finished.",
		],
		[
			"NO_CHECKS_REPORTED",
			"Not reviewed: this practice needs the checks of this commit, and none were reported.",
		],
		["WORKSPACE_INACTIVE", "The workspace was not active when the candidate observation arrived."],
		[
			"COPY_AUTHORITY_UNAVAILABLE",
			"Hephaestus could not confirm the record of whose data this review copied, so the candidate observation was not recorded.",
		],
		[
			"quote_unverified",
			"The candidate observation failed one of Hephaestus’s checks, so it was not recorded.",
		],
		[
			"duplicate_problem_key",
			"The candidate observation failed one of Hephaestus’s checks, so it was not recorded.",
		],
	])("describes the withholding reason %s", (code, sentence) => {
		expect(reviewReasonSentence(code)).toBe(sentence);
	});

	it("keeps the generic fallback for an unknown reason", () => {
		expect(knownReasonSentence("UNKNOWN_REASON")).toBeUndefined();
		expect(withheldSentence("UNKNOWN_REASON")).toBe(
			"Withheld for a recorded reason that this page cannot describe yet.",
		);
	});
});
