import type { AccountRef, AdminAccountView, ProviderIdentityRef } from "@/api/types.gen";
import { firstNonBlank } from "@/lib/text";

export type AppRole = AdminAccountView["appRole"];

/**
 * An account's role and status in words, keyed on the wire's enums so a value the server adds fails
 * the build here rather than reaching an admin as a constant name. `APP_ADMIN` is the role the
 * product calls Instance admin everywhere, from the sidebar to the admin docs.
 */
export const APP_ROLE_LABELS: Record<AppRole, string> = {
	USER: "User",
	APP_ADMIN: "Instance admin",
};

export const ACCOUNT_STATUS_LABELS: Record<AccountRef["status"], string> = {
	UNCLAIMED: "Unclaimed",
	ACTIVE: "Active",
	BLOCKED: "Blocked",
	DELETING: "Being deleted",
	DELETED: "Deleted",
};

/**
 * The identity a workspace knows an account by: the one from the workspace's provider, else the
 * first with a login. The server sends only identities from providers this workspace connects.
 */
export function workspaceIdentity(
	account: AccountRef,
	provider?: ProviderIdentityRef["provider"],
): ProviderIdentityRef | undefined {
	const named = account.identities.filter(
		(identity) => firstNonBlank(identity.login) !== undefined,
	);
	return named.find((identity) => identity.provider === provider) ?? named[0];
}

export function accountName(
	account: AccountRef,
	provider?: ProviderIdentityRef["provider"],
): string {
	const identity = workspaceIdentity(account, provider);
	return (
		firstNonBlank(account.displayName, identity?.displayName, identity?.login) ?? "Unnamed account"
	);
}
