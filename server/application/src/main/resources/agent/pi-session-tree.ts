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

/**
 * The entry to branch from, as the file on disk has it. The SDK holds a session's entries back until
 * its first assistant message and appends each one as it arrives after that, so a caller's checkpoint
 * is missing from the file only when the write behind it never landed. Branching on that id fails the
 * whole group, where the file's own leaf still carries everything written before it.
 */
function persistedCheckpoint(seed: SessionManager, checkpointEntryId: string): string {
	if (seed.getEntry(checkpointEntryId)) return checkpointEntryId;
	const persistedLeaf = seed.getLeafId();
	if (!persistedLeaf) {
		throw new Error(`Seed session holds neither ${checkpointEntryId} nor any persisted entry`);
	}
	console.error(
		`[pi-session-tree] checkpoint ${checkpointEntryId} never reached the seed session; branching every fork from its last written entry ${persistedLeaf}`,
	);
	return persistedLeaf;
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

	const branchFromId = persistedCheckpoint(
		SessionManager.open(seedSessionFile, sessionDir),
		checkpointEntryId,
	);

	const forks: SessionFork[] = [];
	for (const key of keys) {
		// createBranchedSession replaces the manager's active file, so every fork must
		// start from a newly opened view of the immutable seed.
		const seed = SessionManager.open(seedSessionFile, sessionDir);
		const sessionFile = seed.createBranchedSession(branchFromId);
		if (!sessionFile) {
			throw new Error("Persistent Pi session fork did not produce a session file");
		}
		forks.push({ key, sessionFile });
	}

	return forks;
}
