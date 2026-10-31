import { toast } from "@/lib/toast";

/** A workspace slug, as the API defines it (`server/openapi.yaml`). */
const WORKSPACE_SLUG = /^(?=.{1,64}$)[a-z0-9]+(?:-[a-z0-9]+)*$/u;

/**
 * Says that a link named a workspace the reader cannot open, after the workspace gate opened another
 * workspace (`opened`) or none. An unknown workspace and one the reader has no access to read the
 * same, so the notice tells nobody which workspaces exist (ADR 0052). It stays until the reader
 * closes it, and the toaster's polite live region announces it.
 */
export function noticeCannotOpenWorkspace(requested: string, opened: string | undefined): void {
	// The slug comes from the link, so the notice names it only when it is one: other text in the
	// address would speak in the product's voice.
	const named = WORKSPACE_SLUG.test(requested) ? `the workspace “${requested}”` : "this workspace";
	toast.warning(`You cannot open ${named}.`, {
		// One notice, however often the gate runs for the same arrival.
		id: "cannot-open-workspace",
		...(opened === undefined ? {} : { description: `This is ${opened}.` }),
	});
}
