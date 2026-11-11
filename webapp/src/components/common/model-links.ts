import { createContext } from "react";

/**
 * The hosts that a link in model-written text may point to: the workspace's `linkHosts`, as the
 * server lists them (Hephaestus and the workspace's providers). A host that starts with a dot also
 * allows its subdomains. The page's own host is always allowed. The server applies the same rule
 * to what it sends to Slack (`ModelLinks`).
 */
export const ModelLinkHostsContext = createContext<readonly string[]>([]);

/** Whether `href` is a web address on an allowed host; anything else shows as text. */
export function linkIsAllowed(href: string, hosts: readonly string[]): boolean {
	let url: URL;
	try {
		url = new URL(href);
	} catch {
		return false;
	}
	if ((url.protocol !== "http:" && url.protocol !== "https:") || url.username || url.password) {
		return false;
	}
	const host = url.hostname.toLowerCase();
	if (host === globalThis.location.hostname.toLowerCase()) {
		return true;
	}
	return hosts.some((allowed) =>
		allowed.startsWith(".")
			? host === allowed.slice(1) || host.endsWith(allowed)
			: host === allowed,
	);
}
