import type { AdminAccountView } from "@/api/types.gen";

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

export const ACCOUNT_STATUS_LABELS: Record<AdminAccountView["status"], string> = {
	ACTIVE: "Active",
	SUSPENDED: "Suspended",
	DELETING: "Being deleted",
	DELETED: "Deleted",
};
