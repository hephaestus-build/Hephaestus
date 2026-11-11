import { describe, expect, it } from "vitest";

import { groupReadSources } from "@/components/admin/practice-editor/reads-presentation";

import { mockDefinitionOptions, mockDocumentItemType, mockPullRequestItemType } from "./fixtures";

describe("groupReadSources", () => {
	it("files every source the options offer under a heading that names it", () => {
		const orphans = mockDefinitionOptions.itemTypes.flatMap((itemType) =>
			groupReadSources(itemType.readSources)
				.filter((group) => group.family === "other")
				.flatMap((group) => group.sources.map((option) => option.source)),
		);

		expect(orphans).toStrictEqual([]);
	});

	// Both ways grouping can stop being a partition are silent: a source filed under no family
	// disappears from the screen, and families emitted in source order bury the work itself.
	it("partitions a pull request's sources, the work itself first", () => {
		const groups = groupReadSources(mockPullRequestItemType.readSources);
		const filed = groups.flatMap((group) => group.sources.map((option) => option.source));

		expect([...filed].sort()).toStrictEqual(
			mockPullRequestItemType.readSources.map((option) => option.source).sort(),
		);
		expect(groups.map((group) => group.family)).toStrictEqual(["item", "around"]);
	});

	it("drops a family the item type has no source in", () => {
		expect(
			groupReadSources(mockDocumentItemType.readSources).map((group) => group.family),
		).toStrictEqual(["item"]);
	});
	it("keeps a source this build has not been taught, under its own heading", () => {
		const groups = groupReadSources([
			{ source: "wiki.page.body", label: "Wiki page", needs: ["REQUIRED"], citation: {} },
		]);

		expect(groups.map((group) => group.family)).toStrictEqual(["other"]);
	});
});
