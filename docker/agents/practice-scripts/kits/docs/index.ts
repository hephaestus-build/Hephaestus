import { z } from "zod";
import { decimalIdSchema } from "../../lib/core/contract.ts";
import type { ScriptContext } from "../../lib/core/practice-script.ts";

export const documentRecordSchema = z.object({
	id: decimalIdSchema,
	sourceId: decimalIdSchema,
	title: z.string().nullable(),
	collection: z.string().nullable(),
	collectionSlug: z.string().nullable(),
	slug: z.string().nullable(),
	createdBy: z.string().nullable(),
	createdByAccountId: decimalIdSchema.nullable(),
	updatedBy: z.string().nullable(),
	updatedByAccountId: decimalIdSchema.nullable(),
	archived: z.boolean(),
});

export function docs<C extends Pick<ScriptContext, "kind" | "item" | "evidence">>(ctx: C) {
	if (ctx.kind !== "docs.document") {
		throw new Error("the script uses the wrong domain kit");
	}
	return {
		...ctx,
		kind: "docs.document" as const,
		item: documentRecordSchema.parse(ctx.item),
		body: async () => ctx.evidence.record("document.md"),
		cite: async (startLine: number, endLine?: number) =>
			ctx.evidence.citeRecord("document.md", startLine, endLine),
	};
}
