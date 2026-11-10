import { EVENT_TYPE_LABELS } from "@/components/admin/audit/audit-format";
import type { AuditSearch } from "@/components/admin/audit/audit-search";
import { valuesOf } from "@/components/admin/audit/label-values";
import { dayAfterInstant, dayStartInstant, toDateRange } from "@/lib/date-range-search";
import { narrowToEnum } from "@/lib/search-params";

const EVENT_TYPES = valuesOf(EVENT_TYPE_LABELS);
const OUTCOMES = ["SUCCESS", "FAILURE"] as const;

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
