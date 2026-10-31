import { toast } from "@/lib/toast";

/**
 * Says that a link named a workspace the reader cannot open, after the workspace gate opened another
 * workspace (`opened`) or none. An unknown workspace and one the reader has no access to read the
 * same, so the notice tells nobody which workspaces exist (ADR 0052). It stays until the reader
 * closes it, and the toaster's polite live region announces it.
 */
export function noticeCannotOpenWorkspace(requested: string, opened: string | undefined): void {
	toast.warning(`You cannot open the workspace “${requested}”.`, {
		// One notice, however often the gate runs for the same arrival.
		id: "cannot-open-workspace",
		...(opened === undefined ? {} : { description: `This is ${opened}.` }),
	});
}
