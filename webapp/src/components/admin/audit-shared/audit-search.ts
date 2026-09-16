import { z } from "zod";

import { multiValue } from "@/lib/search-params";

/**
 * Every field is `.catch()`-ed rather than merely optional: a stale link from an old ticket must still
 * open the log, less narrowly filtered, instead of failing `validateSearch` into an error page.
 */
export const auditSearchSchema = z.object({
	tab: z.enum(["signins", "settings"]).catch("signins"),
	eventType: multiValue,
	outcome: multiValue,
	accountId: z.number().optional().catch(undefined),
	entityType: multiValue,
	action: multiValue,
	actorId: z.number().optional().catch(undefined),
	/** Inclusive local calendar days. */
	from: z.string().optional().catch(undefined),
	to: z.string().optional().catch(undefined),
});

export type AuditSearch = z.infer<typeof auditSearchSchema>;

export const workspaceAuditSearchSchema = auditSearchSchema.omit({
	tab: true,
	eventType: true,
	outcome: true,
	accountId: true,
});

export type ConfigAuditSearch = z.infer<typeof workspaceAuditSearchSchema>;

export {
	dayAfterInstant,
	dayStartInstant,
	fromDateRange,
	fromDayParam,
	toDateRange,
	toDayParam,
} from "@/lib/date-range-search";
