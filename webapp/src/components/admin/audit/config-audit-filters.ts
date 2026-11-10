import type { ConfigAuditSearch } from "@/components/admin/audit/audit-search";
import { ACTION_LABELS, ENTITY_TYPE_LABELS } from "@/components/admin/audit/config-audit-format";
import { valuesOf } from "@/components/admin/audit/label-values";
import { dayAfterInstant, dayStartInstant, toDateRange } from "@/lib/date-range-search";
import { narrowToEnum } from "@/lib/search-params";

const ENTITY_TYPES = valuesOf(ENTITY_TYPE_LABELS);
const ACTIONS = valuesOf(ACTION_LABELS);

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
