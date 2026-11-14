import { createContext, use } from "react";

/**
 * The members whose role change or removal is in flight. Cells read it from context instead of
 * the columns capturing it: `FlexRender` renders a cell as `createElement(columnDef.cell)`, so new
 * columns remount every cell. A confirmation dialog would then return focus to a detached trigger.
 */
export const PendingMembers = createContext<{ role?: string; removing?: ReadonlySet<string> }>({});

/** A role change or a removal is in flight for this member. */
export function useMemberPending(accountId: string) {
	const { role, removing } = use(PendingMembers);
	return role === accountId || removing?.has(accountId) === true;
}
