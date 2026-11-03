import { z } from "zod";
import { decimalIdSchema, PRACTICE_SLUG } from "./contract.ts";

export const runtimeIdSchema = z.uuid();
export const practiceIndexSchema = z.array(
	z.strictObject({
		slug: z.string().regex(PRACTICE_SLUG),
		name: z.string().min(1),
		readsSources: z.array(z.string().min(1)),
		exhaustiveSources: z.array(z.string().min(1)),
		knownLimitations: z.array(z.string().min(1)).default([]),
		whyItMatters: z.string().min(1).optional(),
		versionId: decimalIdSchema.optional(),
		contextPaths: z.array(z.string().min(1)).optional(),
	}),
);
export type PracticeIndexEntry = z.infer<typeof practiceIndexSchema>[number];
