import { z } from "zod";
import { decimalIdSchema } from "../../lib/core/contract.ts";

export const threadRecordSchema = z.object({
	channel: z.string(),
	threadTs: z.string(),
	messages: z.array(
		z.object({
			ts: z.string(),
			author: z.string().nullable(),
			accountId: decimalIdSchema.nullable(),
			authorLogin: z.string().optional(),
			authorName: z.string().optional(),
			text: z.string().nullable(),
			edited: z.literal(true).optional(),
		}),
	),
	messageCount: z.int(),
	truncated: z.boolean(),
});
