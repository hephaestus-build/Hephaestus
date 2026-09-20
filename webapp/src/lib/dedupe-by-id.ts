/** Offset pages over an append-only log are not stable: a row written between two fetches shifts
 * everything down, so the next page repeats the previous one's tail. */
export function dedupeById<T extends { id?: string | number }>(rows: T[]): T[] {
	const seen = new Set<string | number>();
	return rows.filter((row) => {
		if (row.id == null) {
			return true;
		}
		if (seen.has(row.id)) {
			return false;
		}
		seen.add(row.id);
		return true;
	});
}
