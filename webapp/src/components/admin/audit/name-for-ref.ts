import type { AuditRef } from "./ref-label";

export function nameForRef(
	rows: readonly { account?: AuditRef; actor?: AuditRef; actingActor?: AuditRef }[],
	id: number,
): string | undefined {
	for (const row of rows) {
		for (const ref of [row.account, row.actor, row.actingActor]) {
			if (ref?.id === id) {
				return ref.displayName ?? undefined;
			}
		}
	}
	return undefined;
}
