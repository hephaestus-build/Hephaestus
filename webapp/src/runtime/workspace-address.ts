import type { AnyRouter, LocationRewrite } from "@tanstack/react-router";

import environment from "@/environment";
import { safeReturnTo } from "@/runtime/auth/return-to";

export interface WorkspaceAddressConfig {
	enabled: boolean;
	baseDomain: string;
	apexOrigin: string;
}

// /admin is shared: tenant input means workspace admin; internal /admin output means instance admin.
const APEX_PATHS = [
	"/login",
	"/consent",
	"/auth",
	"/imprint",
	"/privacy",
	"/terms",
	"/unsubscribe",
	"/settings",
	// The passkey challenge: one origin for every WebAuthn ceremony.
	"/verify-passkey",
	"/integrations",
	"/about",
	"/workspaces",
	// Do not prefix explicit workspace paths; output rewrites handle their target workspace.
	"/w",
];

function isApexPath(path: string): boolean {
	return APEX_PATHS.some((prefix) => path === prefix || path.startsWith(`${prefix}/`));
}

export function tenantSlug(url: URL, config: WorkspaceAddressConfig): string | undefined {
	if (!config.enabled || !url.hostname.endsWith(`.${config.baseDomain}`)) {
		return undefined;
	}
	const slug = url.hostname.slice(0, -(config.baseDomain.length + 1));
	return /^[a-z0-9][a-z0-9-]{1,49}[a-z0-9]$/u.test(slug) && !slug.includes("--") ? slug : undefined;
}

export function workspaceRewrite(config: WorkspaceAddressConfig, origin: string): LocationRewrite {
	const slug = tenantSlug(new URL(origin), config);
	return {
		input: ({ url }) => {
			if (slug !== undefined && url.origin === origin && !isApexPath(url.pathname)) {
				url.pathname = `/w/${slug}${url.pathname === "/" ? "" : url.pathname}`;
			}
			return url;
		},
		output: ({ url }) => {
			if (!config.enabled || url.origin !== origin) {
				return url;
			}
			const workspace = /^\/w\/(?<slug>[^/]+)(?<path>\/.*)?$/u.exec(url.pathname)?.groups;
			if (workspace !== undefined && workspace.slug === slug && slug !== undefined) {
				url.pathname = workspace.path ?? "/";
				return url;
			}
			if (slug !== undefined) {
				return new URL(url.pathname + url.search + url.hash, config.apexOrigin);
			}
			// A document request reaches the edge's canonical /w redirect, including after sign-in.
			return url;
		},
	};
}

/** TanStack router-core 1.171 navigate({ to }) ignores location.external, so commitLocation must force document navigation. */
export function installWorkspaceNavigation(
	router: AnyRouter,
	config: WorkspaceAddressConfig,
): void {
	if (!config.enabled) {
		return;
	}
	const { commitLocation } = router;
	router.commitLocation = async (location) => {
		const target = new URL(location.publicHref, router.origin);
		const apexWorkspace = router.origin === config.apexOrigin && target.pathname.startsWith("/w/");
		if (target.origin !== router.origin || apexWorkspace) {
			return router.navigate({
				href: target.href,
				reloadDocument: true,
				replace: location.replace,
				ignoreBlocker: location.ignoreBlocker,
			});
		}
		return commitLocation(location);
	};
}

export const workspaceAddressConfig: WorkspaceAddressConfig = environment.workspaceSubdomains;

export function currentTenantSlug(): string | undefined {
	return tenantSlug(new URL(window.location.origin), workspaceAddressConfig);
}

export function publicHref(path: string): string {
	if (!workspaceAddressConfig.enabled) {
		return path;
	}
	const url = new URL(path, window.location.origin);
	const rewritten = workspaceRewrite(workspaceAddressConfig, window.location.origin).output?.({
		url,
	});
	return new URL(rewritten ?? url).href;
}

export function workspaceReturnTo(path: string): string {
	const safePath = safeReturnTo(path);
	if (!workspaceAddressConfig.enabled) {
		return safePath;
	}
	const url = new URL(safePath, window.location.origin);
	const rewritten = workspaceRewrite(workspaceAddressConfig, window.location.origin).input?.({
		url,
	});
	const target = new URL(rewritten ?? url);
	return safeReturnTo(target.pathname + target.search + target.hash);
}
