/** Redirect aliases before authentication so cookies, CORS and OAuth stay on the canonical origin. */
export function workspaceAccessAlias(hostname: string, clientUrl: string): string | undefined {
	const canonical = new URL(clientUrl);
	if (canonical.protocol !== "https:" && canonical.protocol !== "http:") return undefined;
	const suffix = `.${canonical.hostname}`;
	if (!hostname.endsWith(suffix)) return undefined;
	const slug = hostname.slice(0, -suffix.length);
	if (!/^[a-z0-9][a-z0-9-]{2,50}$/.test(slug)) return undefined;
	canonical.pathname = `/w/${slug}/request-access`;
	canonical.search = "";
	canonical.hash = "";
	return canonical.href;
}
