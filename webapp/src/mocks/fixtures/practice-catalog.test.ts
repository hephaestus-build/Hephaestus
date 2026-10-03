import { readFileSync } from "node:fs";

import { assert, describe, expect, it } from "vitest";
import { z } from "zod";

import { realPracticeDefinition } from "./practice-catalog";

const bundledCatalog = z
	.object({
		criteriaPreambles: z.record(z.string(), z.string()),
		groups: z.array(
			z.object({ practices: z.array(z.object({ slug: z.string(), criteria: z.string() })) }),
		),
	})
	.parse(
		JSON.parse(
			readFileSync(
				"../server/application/src/main/resources/practices/default-catalog.json",
				"utf8",
			),
		),
	);

describe("bundled practice preview fixture", () => {
	it("renders the actual document preamble and complete practice criteria", () => {
		const practice = bundledCatalog.groups
			.flatMap((group) => group.practices)
			.find((candidate) => candidate.slug === "published-decisions-name-the-alternatives");
		const preamble = bundledCatalog.criteriaPreambles["docs.document"];
		assert(practice, "The bundled catalog has no published-decisions practice");
		assert(preamble !== undefined, "The bundled catalog has no document preamble");
		expect(realPracticeDefinition.criteria).toBe(`${preamble}\n\n---\n\n${practice.criteria}`);
		expect(realPracticeDefinition.criteria).not.toContain("NO_REVIEW_OCCASION");
	});
});
