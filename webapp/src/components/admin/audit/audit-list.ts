import type { PanelState } from "@/components/common/panel-state";
import type { MorePages } from "@/runtime/tanstack-query/infinite-list";

/** An audit trail as a table shows it: its rows and its paging. */
export type AuditListState<TRow> = PanelState<{ rows: TRow[] } & MorePages>;

/** Cursor pages as one list. */
export function auditRows<TRow>(pages: readonly { content: TRow[] }[]) {
	return { rows: pages.flatMap((page) => page.content) };
}
