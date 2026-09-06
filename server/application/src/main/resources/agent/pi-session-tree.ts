import { SessionManager } from "@earendil-works/pi-coding-agent";

export interface SessionFork {
	key: string;
	sessionFile: string;
}

export interface ForkSessionsOptions {
	seedSessionFile: string;
	checkpointEntryId: string;
	keys: readonly string[];
	sessionDir?: string;
}

export interface ReconnaissanceSeed {
	seedSessionFile: string;
	checkpointEntryId: string;
}

/**
 * What the shared reconnaissance leaves for the groups to branch from.
 *
 * <p>A disposed session still answers getLeafId(), and the SDK holds a session's entries back until
 * its first assistant message, so a budget that ran out names an entry the file never received. It
 * has to be told from an answer here, or it reads as a session-storage error against that entry
 * further down.
 */
export function reconnaissanceSeed(
	sessionManager: SessionManager,
	deadline: { expired: boolean },
	budgetMs: number,
): ReconnaissanceSeed {
	if (deadline.expired) {
		throw new Error(
			`Shared reconnaissance did not answer within ${Math.round(budgetMs / 1000)}s, so each group reads for itself`,
		);
	}
	const checkpointEntryId = sessionManager.getLeafId();
	const seedSessionFile = sessionManager.getSessionFile();
	if (!checkpointEntryId || !seedSessionFile) {
		throw new Error("Shared reconnaissance produced no persistent checkpoint");
	}
	return { seedSessionFile, checkpointEntryId };
}

export function forkSessions({
	seedSessionFile,
	checkpointEntryId,
	keys,
	sessionDir,
}: ForkSessionsOptions): SessionFork[] {
	const uniqueKeys = new Set<string>();
	for (const key of keys) {
		if (!key || uniqueKeys.has(key)) {
			throw new Error(`Keys must be non-empty and unique: ${key}`);
		}
		uniqueKeys.add(key);
	}

	const forks: SessionFork[] = [];
	for (const key of keys) {
		// createBranchedSession replaces the manager's active file, so every fork must
		// start from a newly opened view of the immutable seed.
		const seed = SessionManager.open(seedSessionFile, sessionDir);
		const sessionFile = seed.createBranchedSession(checkpointEntryId);
		if (!sessionFile) {
			throw new Error("Persistent Pi session fork did not produce a session file");
		}
		forks.push({ key, sessionFile });
	}

	return forks;
}
