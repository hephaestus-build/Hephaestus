import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";
import { z } from "zod";

import { mockPracticeDefinitionOptions } from "./practice";

const catalog = z
	.object({
		sources: z.array(
			z.object({
				kind: z.string(),
				displayName: z.string(),
				description: z.string(),
				selectionScope: z.string(),
				privacyClass: z.string(),
				requiredQuality: z.string(),
				completeness: z.object({ supportsComplete: z.boolean() }),
			}),
		),
	})
	.parse(
		JSON.parse(
			readFileSync(
				`../server/application/src/main/resources/contracts/artifact-source/${mockPracticeDefinitionOptions.sourceContractVersion}/catalog.json`,
				"utf8",
			),
		),
	);

describe("practice definition options fixture", () => {
	it("matches its declared source contract for every offered evidence source", () => {
		for (const workType of mockPracticeDefinitionOptions.workTypes) {
			for (const option of workType.allowedSources) {
				const source = catalog.sources.find((candidate) => candidate.kind === option.sourceKind);
				expect(source, option.sourceKind).toBeDefined();
				expect(option, option.sourceKind).toStrictEqual({
					sourceKind: source?.kind,
					displayName: source?.displayName,
					description: source?.description,
					selectionScope: source?.selectionScope,
					privacyClass: source?.privacyClass,
					requiredQuality: source?.requiredQuality,
					supportsExhaustiveEvidence: source?.completeness.supportsComplete,
				});
			}
		}
	});
});
