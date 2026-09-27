/**
 * Whether the person agreed, for one conversation, that what they send Heph goes to the AI service
 * behind it. Asked before the first message or retry of each conversation in each account and
 * workspace, and never granted by opening a conversation or a draft. Kept in memory only: a new
 * session, account or workspace asks again, and nothing turns it on for every conversation at once.
 */
export interface SharingScope {
	sessionKey: string;
	workspaceSlug: string;
	threadId: string;
}

const granted = new Set<string>();

function key(scope: SharingScope): string {
	return JSON.stringify([scope.sessionKey, scope.workspaceSlug, scope.threadId]);
}

export function sharingGranted(scope: SharingScope): boolean {
	return granted.has(key(scope));
}

export function grantSharing(scope: SharingScope): void {
	granted.add(key(scope));
}

/** Forgets every answer: the session they were given in has ended or changed. */
export function clearSharing(): void {
	granted.clear();
}
