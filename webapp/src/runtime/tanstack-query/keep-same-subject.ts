import { isRecord } from "@/lib/is-record";

/** The part of a generated query key that says whose data, and of which kinds, a read is. */
interface SubjectKey {
	queryKey: readonly [{ path?: { workspaceSlug?: string; userId?: number }; query?: object }];
}

interface Subject {
	workspaceSlug: string;
	userId?: number;
	login?: string;
	teamId?: number;
	team?: string;
	repo?: readonly string[];
	kinds?: readonly string[];
}

/**
 * While another range loads, what is on screen stays — marked stale — rather than blanking the
 * page. Only the same subject's figures may stand in: another workspace's, team's, person's,
 * repositories' or category's never do, and the region shows its skeleton instead.
 */
export function keepSameSubject({
	workspaceSlug,
	userId,
	login,
	teamId,
	team,
	repo = [],
	kinds = [],
}: Subject) {
	return <TData>(previous: TData | undefined, previousQuery: SubjectKey | undefined) => {
		const key = previousQuery?.queryKey[0];
		const query = isRecord(key?.query) ? key.query : {};
		const same =
			key?.path?.workspaceSlug === workspaceSlug &&
			key.path.userId === userId &&
			query.login === login &&
			query.teamId === teamId &&
			query.team === team &&
			listOf(query.repo) === repo.join(",") &&
			listOf(query.kinds) === kinds.join(",");
		return same ? previous : undefined;
	};
}

function listOf(value: unknown): string {
	return Array.isArray(value) ? value.join(",") : "";
}
