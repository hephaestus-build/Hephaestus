import { z } from "zod";
import { decimalIdSchema } from "../../lib/core/contract.ts";

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
