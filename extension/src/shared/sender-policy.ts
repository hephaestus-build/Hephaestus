import { MENTOR_PAGE, panelTabOf } from "~/shared/mentor";
import { COMMAND_SURFACES, type RpcRequest, type Surface } from "~/shared/rpc";

/** The fields of `chrome.runtime.MessageSender` the policy reads. */
export interface SenderFacts {
	id?: string;
	url?: string;
	frameId?: number;
	/** The sender's tab; its address is what Chrome reports, never what a message says. */
	tab?: { id?: number; url?: string };
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
 * - The Heph panel is not web-accessible either, and counts only outside any tab, at exactly the
 *   address the worker gives one tab's side panel.
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
	if (
		url.pathname === MENTOR_PAGE &&
		url.hash === "" &&
		url.host === extensionId &&
		sender.tab === undefined &&
		panelTabOf(url.search) !== undefined
	) {
		return "mentor";
	}
	return undefined;
}

/**
 * The tab a Heph panel belongs to, from the address Chrome reports for it. A side panel is in no tab,
 * so the page cannot be the panel when Chrome names one: the same page opened as a tab is refused. The
 * worker still checks that it set this address as that tab's panel (`attestPanel`).
 */
function panelTab(sender: SenderFacts): number | undefined {
	return sender.url === undefined ? undefined : panelTabOf(new URL(sender.url).search);
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
	let tabId: number | undefined;
	if (surface === "mentor") {
		tabId = panelTab(sender);
	} else if (surface !== "options") {
		tabId = sender.tab?.id;
	}
	return { allowed: true, surface, tabId };
}
