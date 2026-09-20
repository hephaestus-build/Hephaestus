import { isRecord } from "@/lib/is-record";

/**
 * The operation a generated query key came from, or `undefined` for a key this client did not build:
 * a cache predicate is handed hand-rolled keys too, so the generator's `_id` tag has to be checked
 * rather than assumed.
 */
export function queryOperationId(queryKey: readonly unknown[]): string | undefined {
	const [head] = queryKey;
	if (!isRecord(head)) {
		return undefined;
	}
	return typeof head._id === "string" ? head._id : undefined;
}
