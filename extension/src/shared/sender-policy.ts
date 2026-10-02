import { COMMAND_SURFACES, type RpcRequest, type Surface } from "~/shared/rpc";

/** The fields of `chrome.runtime.MessageSender` the policy reads. */
export interface SenderFacts {
	id?: string;
	url?: string;
	frameId?: number;
	tab?: { id?: number };
}

const PAGE_SURFACES: Record<string, Surface> = {
	"/options.html": "options",
	"/action.html": "action",
};

/**
 * Which extension surface sent a message, from what Chrome attests and nothing the message says.
 *
 * - Another extension or a web page never matches: `sender.id` must be ours and the document must
 *   be one of our `chrome-extension:` pages. A content script's `sender.url` is the host page, so a
 *   content script is never a surface either — it has nothing to ask.
 * - Options and the action confirmation are not web-accessible, so no web page can frame them;
 *   they are served from the static id. The confirmation also counts only as a tab's top frame.
 * - The inline page is web-accessible with `use_dynamic_url`, so its host is a per-session GUID; it
 *   counts only when it is a sub-frame of a tab, which is where the content script puts it.
 */
export function classifySender(sender: SenderFacts, extensionId: string): Surface | undefined {
	if (sender.id !== extensionId || sender.url === undefined) {
		return undefined;
	}
	let url: URL;
	try {
		url = new URL(sender.url);
	} catch {
		return undefined;
	}
	if (url.protocol !== "chrome-extension:") {
		return undefined;
	}
	const page = Object.hasOwn(PAGE_SURFACES, url.pathname) ? PAGE_SURFACES[url.pathname] : undefined;
	if (page !== undefined) {
		if (url.host !== extensionId) {
			return undefined;
		}
		if (page === "action" && (sender.frameId !== 0 || sender.tab?.id === undefined)) {
			return undefined;
		}
		return page;
	}
	if (
		url.pathname === "/inline.html" &&
		sender.tab?.id !== undefined &&
		sender.frameId !== undefined &&
		sender.frameId > 0
	) {
		return "inline";
	}
	return undefined;
}

export type Authorization =
	| { allowed: true; surface: Surface; tabId: number | undefined }
	| { allowed: false };

/**
 * Whether this surface may send this command, and which tab it is about. The inline frame is always
 * about the tab that holds it, and the confirmation window is always the tab it runs in, which its
 * intent must be bound to; neither may name another one.
 */
export function authorize(
	request: RpcRequest,
	sender: SenderFacts,
	extensionId: string,
): Authorization {
	const surface = classifySender(sender, extensionId);
	if (surface === undefined || !COMMAND_SURFACES[request.type].includes(surface)) {
		return { allowed: false };
	}
	return {
		allowed: true,
		surface,
		tabId: surface === "options" ? undefined : sender.tab?.id,
	};
}
