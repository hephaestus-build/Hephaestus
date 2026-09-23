import { z } from "zod";

import type { PracticeSubject } from "@/api/types.gen";

const term = z
	.string()
	.max(200)
	.refine((value) => value.trim().length > 0);

const clause = z
	.strictObject({
		changedPathMatches: z.array(term).min(1).max(100).optional(),
		diffContains: z.array(term).min(1).max(100).optional(),
		evidenceHasItems: z
			.enum(["scm.review-threads", "scm.inline-review-comments", "scm.general-review-comments"])
			.optional(),
	})
	.refine(
		(value) =>
			[value.changedPathMatches, value.diffContains, value.evidenceHasItems].filter(
				(item) => item !== undefined,
			).length === 1,
	);

const gate = z.strictObject({
	absentSays: z.string().trim().min(1).max(300),
	anyOf: z.array(clause).min(1).max(10),
});

export function parseGate(text: string): { value?: PracticeSubject; error?: string } {
	if (text.trim() === "") {
		return {};
	}
	try {
		const result = gate.safeParse(JSON.parse(text));
		return result.success
			? { value: result.data }
			: { error: "Use a sentence and at least one valid gate clause." };
	} catch {
		return { error: "Enter valid JSON for the gate." };
	}
}
