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
	it("keeps the generic fallback for an unknown reason", () => {
		expect(knownReasonSentence("UNKNOWN_REASON")).toBeUndefined();
		expect(withheldSentence("UNKNOWN_REASON")).toBe(
			"Withheld for a recorded reason that this page cannot describe yet.",
		);
	});
});
