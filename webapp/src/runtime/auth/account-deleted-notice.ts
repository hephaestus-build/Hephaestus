import { z } from "zod";

/**
 * Tells the sign-in page that this tab just deleted its own account.
 *
 * The self-deletion flow signs the user out and does a full reload to the login page, so a toast
 * fired in the mutation's onSuccess would be destroyed by the reload. The Danger Zone records the
 * deletion once the server confirms it, and the login route reads it on its first render and then
 * clears it. Session storage follows the tab, not the person, so the record names the deleted
 * account and expires: another identity in this tab, or a later visit, must not see the notice.
 */
const ACCOUNT_DELETED_NOTICE_KEY = "hephaestus-account-deleted-notice";

/** Long enough to reach the sign-in page, short enough that a later reader of the tab is not told. */
export const ACCOUNT_DELETED_NOTICE_LIFETIME_MS = 10 * 60_000;

const noticeSchema = z.object({
	accountId: z.string().regex(/^[1-9][0-9]*$/u),
	deletedAt: z.number().nonnegative(),
});

function storedNotice() {
	let stored: unknown = null;
	try {
		stored = JSON.parse(sessionStorage.getItem(ACCOUNT_DELETED_NOTICE_KEY) ?? "null");
	} catch {
		// Storage can be unavailable, and a stored value need not be JSON; then there is no notice.
	}
	return noticeSchema.safeParse(stored).data;
}

export function recordAccountDeleted(accountId: string): void {
	try {
		sessionStorage.setItem(
			ACCOUNT_DELETED_NOTICE_KEY,
			// oxlint-disable-next-line no-restricted-properties -- The notice expires in wall-clock time, across reloads.
			JSON.stringify(noticeSchema.parse({ accountId, deletedAt: Date.now() })),
		);
	} catch {
		// Storage can be unavailable; the deletion still goes to the sign-in page.
	}
}

/** Whether this tab deleted its account in the last few minutes. */
export function accountDeletedRecently(): boolean {
	const notice = storedNotice();
	if (notice === undefined) {
		return false;
	}
	// oxlint-disable-next-line no-restricted-properties -- The notice expires in wall-clock time, across reloads.
	const elapsed = Date.now() - notice.deletedAt;
	// A record from the future, after the clock was set back, is not recent.
	return elapsed >= 0 && elapsed < ACCOUNT_DELETED_NOTICE_LIFETIME_MS;
}

export function clearAccountDeletedNotice(): void {
	try {
		sessionStorage.removeItem(ACCOUNT_DELETED_NOTICE_KEY);
	} catch {
		// Storage can be unavailable; then nothing was recorded.
	}
}

/** The server confirmed this identity, so a notice about any other account is not for this reader. */
export function clearAccountDeletedNoticeUnlessFor(accountId: string): void {
	const notice = storedNotice();
	if (notice !== undefined && notice.accountId !== accountId) {
		clearAccountDeletedNotice();
	}
}
