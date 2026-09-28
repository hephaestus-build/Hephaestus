import { isRecord } from "@/lib/is-record";

/** The part of a generated query key that says whose data, and of which kinds, a read is. */
interface SubjectKey {
	queryKey: readonly [{ path?: { workspaceSlug?: string }; query?: object }];
}

interface Subject {
	workspaceSlug: string;
	login?: string;
	teamId?: number;
	kinds?: readonly string[];
}

/**
 * While another range loads, what is on screen stays — marked stale — rather than blanking the
 * page. Only the same subject's figures may stand in: another workspace's, team's, member's or
 * category's never do, and the region shows its skeleton instead.
 */
export function keepSameSubject({ workspaceSlug, login, teamId, kinds = [] }: Subject) {
	return <TData>(previous: TData | undefined, previousQuery: SubjectKey | undefined) => {
		const key = previousQuery?.queryKey[0];
		const query = isRecord(key?.query) ? key.query : {};
		const same =
			key?.path?.workspaceSlug === workspaceSlug &&
			query.login === login &&
			query.teamId === teamId &&
			(Array.isArray(query.kinds) ? query.kinds : []).join(",") === kinds.join(",");
		return same ? previous : undefined;
	};
}
