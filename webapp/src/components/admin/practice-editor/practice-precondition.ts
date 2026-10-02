import { z } from "zod";

import type { PracticePrecondition, PracticeWorkTypeDefinitionOptions } from "@/api/types.gen";

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
	skipReason: z.string().trim().min(1).max(300),
	anyOf: z.array(clause).min(1).max(10),
});

export function parseGate(
	text: string,
	options?: PracticeWorkTypeDefinitionOptions,
): { value?: PracticePrecondition; error?: string } {
	if (text.trim() === "") {
		return {};
	}
	try {
		const result = gate.safeParse(JSON.parse(text));
		if (result.success && options) {
			const unsupported = result.data.anyOf.some(
				(item) =>
					(item.changedPathMatches !== undefined &&
						!options.preconditionSupportedAspects.includes("CHANGED_PATH")) ||
					(item.diffContains !== undefined &&
						!options.preconditionSupportedAspects.includes("DIFF_TEXT")) ||
					(item.evidenceHasItems !== undefined &&
						(!options.preconditionSupportedAspects.includes("EVIDENCE_ITEMS") ||
							!options.preconditionEvidenceCollections.includes(item.evidenceHasItems))),
			);
			if (unsupported) {
				return { error: "This gate is not supported for this kind of work." };
			}
		}
		return result.success
			? { value: result.data }
			: { error: "Use a sentence and at least one valid gate clause." };
	} catch {
		return { error: "Enter valid JSON for the gate." };
	}
}

export function gatePresentation(options?: PracticeWorkTypeDefinitionOptions) {
	const aspects = options?.preconditionSupportedAspects ?? [];
	return {
		description:
			aspects.length === 0
				? "This kind of work supports no evidence gate. Clear an existing gate to continue."
				: "Leave empty to review all work. To change or clear a gate, edit this JSON field.",
		example: aspects.includes("CHANGED_PATH")
			? JSON.stringify({
					skipReason: "the change adds no Swift code",
					anyOf: [{ changedPathMatches: ["**/*.swift"] }],
				})
			: undefined,
	};
}
