import { z } from "zod";

import { EVENT_TYPE_LABELS } from "@/components/admin/audit/audit-format";
import { ACTION_LABELS, ENTITY_TYPE_LABELS } from "@/components/admin/audit/config-audit-format";
import { dayAfterInstant, dayStartInstant, toDateRange } from "@/lib/date-range-search";
import { multiValue, narrowToEnum } from "@/lib/search-params";

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

/** The values a label table names, so that a URL can hold no value the server would refuse. */
function valuesOf<T extends string>(labels: Record<T, string>): T[] {
	const values: T[] = [];
	for (const value in labels) {
		if (Object.hasOwn(labels, value)) {
			values.push(value);
		}
	}
	return values;
}

const EVENT_TYPES = valuesOf(EVENT_TYPE_LABELS);
const OUTCOMES = ["SUCCESS", "FAILURE"] as const;
const ENTITY_TYPES = valuesOf(ENTITY_TYPE_LABELS);
const ACTIONS = valuesOf(ACTION_LABELS);

/** The server's filters for the auth events that `search` asks for. */
export function authAuditFilters(search: AuditSearch) {
	const dateRange = toDateRange(search);
	return {
		eventType: narrowToEnum(search.eventType, EVENT_TYPES),
		result: narrowToEnum(search.outcome, OUTCOMES),
		accountId: search.accountId,
		actingAccountId: search.actorId,
		from: dateRange?.from ? dayStartInstant(dateRange.from) : undefined,
		to: dateRange?.to ? dayAfterInstant(dateRange.to) : undefined,
	} as const;
}

/** The server's filters for the settings changes that `search` asks for. */
export function configAuditFilters(search: ConfigAuditSearch) {
	const dateRange = toDateRange(search);
	return {
		entityType: narrowToEnum(search.entityType, ENTITY_TYPES),
		action: narrowToEnum(search.action, ACTIONS),
		actorId: search.actorId,
		from: dateRange?.from ? dayStartInstant(dateRange.from) : undefined,
		to: dateRange?.to ? dayAfterInstant(dateRange.to) : undefined,
	};
}

/** One page of the settings changes that `search` asks for. */
export function configAuditQuery(search: ConfigAuditSearch, size: number) {
	return { size, ...configAuditFilters(search) };
}
