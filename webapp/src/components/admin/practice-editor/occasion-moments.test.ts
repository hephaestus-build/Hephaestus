import { assert, describe, expect, it } from "vitest";

import {
	momentBands,
	momentDef,
	withdrawnMoments,
} from "@/components/admin/practice-editor/occasion-moments";
import {
	mockConversationWorkType,
	mockDocumentWorkType,
	mockIssueWorkType,
	mockPracticeDefinitionOptions,
	mockPullRequestWorkType,
} from "@/mocks/fixtures/practice";

describe("momentDef", () => {
	it("gives every moment of a work type its own glyph, so the strip reads in greyscale", () => {
		for (const workType of mockPracticeDefinitionOptions.workTypes) {
			const icons = workType.signals.map((option) => momentDef(option.signal).icon);

			expect(new Set(icons).size, workType.artifactKind).toBe(icons.length);
		}
	});

	it("draws a moment this build has never met rather than dropping it", () => {
		const def = momentDef("wiki.page.forked");

		expect(def.phase).toBe("during");
		expect(def.repeats).toBe(false);
	});
});

describe("momentBands", () => {
	it("reads a pull request as start, churn, and two ways to end", () => {
		const bands = momentBands(mockPullRequestWorkType.signals);

		expect(
			bands.map((band) => [band.phase, band.moments.map((moment) => moment.signal)]),
		).toStrictEqual([
			["start", ["scm.pull_request.opened"]],
			[
				"during",
				["scm.pull_request.ready", "scm.pull_request.synchronized", "scm.pull_request.reviewed"],
			],
			["end", ["scm.pull_request.merged", "scm.pull_request.closed"]],
		]);
	});

	it("warns that an issue's middle moment repeats, because binding it is a decision about volume", () => {
		const bands = momentBands(mockIssueWorkType.signals);

		expect(
			bands.map((band) => [band.phase, band.moments.map((moment) => moment.signal)]),
		).toStrictEqual([
			["start", ["scm.issue.opened"]],
			["during", ["scm.issue.updated"]],
			["end", ["scm.issue.closed"]],
		]);
		expect(momentDef("scm.issue.updated").repeats).toBe(true);
	});

	it("gives a document its own three moments under the same three bands", () => {
		const bands = momentBands(mockDocumentWorkType.signals);

		expect(bands.map((band) => band.moments.map((moment) => moment.signal))).toStrictEqual([
			["docs.document.published"],
			["docs.document.updated"],
			["docs.document.archived"],
		]);
	});

	it("drops the bands a work type has nothing in, rather than rendering them empty", () => {
		const bands = momentBands(mockConversationWorkType.signals);

		expect(bands).toHaveLength(1);
		const [only] = bands;
		assert(only);
		expect(only.phase).toBe("end");
	});
});

describe("withdrawnMoments", () => {
	it("finds nothing while every chosen moment is one the work type offers", () => {
		expect(
			withdrawnMoments(mockPullRequestWorkType, [
				"scm.pull_request.opened",
				"scm.pull_request.merged",
			]),
		).toStrictEqual([]);
	});

	it("keeps a saved hand-asked review visible, under the name the wire gives it", () => {
		// A practice saved while asking by hand still counted as an occasion. It cannot be saved again
		// until it goes, so it has to be on screen to be unticked.
		expect(
			withdrawnMoments(mockPullRequestWorkType, [
				"scm.pull_request.opened",
				"scm.pull_request.manual_review",
			]),
		).toStrictEqual([
			{
				signal: "scm.pull_request.manual_review",
				displayName: "Review requested by hand",
				recommended: false,
			},
		]);
	});

	it("names a moment nothing on the wire explains by its id, which is at least searchable", () => {
		expect(withdrawnMoments(mockDocumentWorkType, ["docs.document.forked"])).toStrictEqual([
			{ signal: "docs.document.forked", displayName: "docs.document.forked", recommended: false },
		]);
	});
});
