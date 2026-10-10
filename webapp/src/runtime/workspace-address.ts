import type { AnyRouter, LocationRewrite } from "@tanstack/react-router";

import environment from "@/environment";

export interface WorkspaceAddressConfig {
	enabled: boolean;
	baseDomain: string;
	apexOrigin: string;
}

const APEX_PATHS = [
	"/login",
	"/consent",
	"/auth",
	"/imprint",
	"/privacy",
	"/terms",
	"/unsubscribe",
	"/settings",
	"/integrations",
	"/about",
	"/landing",
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

/** Links already honor rewritten origins; imperative navigation and route redirects need the same boundary. */
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

export const workspaceAddressConfig: WorkspaceAddressConfig = {
	enabled: environment.workspaceSubdomains.enabled,
	baseDomain: environment.workspaceSubdomains.baseDomain,
	apexOrigin: new URL(environment.clientUrl).origin,
};

export function workspaceReturnTo(path: string): string {
	if (!workspaceAddressConfig.enabled) {
		return path;
	}
	const url = new URL(path, window.location.origin);
	const rewritten = workspaceRewrite(workspaceAddressConfig, window.location.origin).input?.({
		url,
	});
	const target = rewritten === undefined ? url : new URL(rewritten);
	return target.pathname + target.search + target.hash;
}
