/**
 * The theme preference is one cookie for the whole base domain, so it follows a person between
 * workspace addresses and the docs site (ADR 0053, amendment of 2026-10-26). `public/theme-init.js`
 * and the docs site read the same name and values before paint.
 */
export const THEME_COOKIE = "hephaestus-theme";

export const THEMES = ["dark", "light", "system"] as const;

export type Theme = (typeof THEMES)[number];

const ONE_YEAR_SECONDS = 365 * 24 * 60 * 60;

/** Any host under the base domain can set this cookie, so only a known value is a theme. */
export function isTheme(value: string | null | undefined): value is Theme {
	return THEMES.some((theme) => theme === value);
}

export function readThemeCookie(cookies: string): Theme | undefined {
	for (const cookie of cookies.split(";")) {
		const [name, value] = cookie.trim().split("=");
		if (name === THEME_COOKIE && isTheme(value)) {
			return value;
		}
	}
	return undefined;
}

interface CookieScope {
	hostname: string;
	secure: boolean;
	workspaceSubdomains: { enabled: boolean; baseDomain: string };
}

/**
 * The base domain when workspace addresses are on and this host is under it; otherwise the cookie
 * stays host-only. It carries the theme alone: the auth cookies stay host-only.
 */
export function themeCookieDomain({
	hostname,
	workspaceSubdomains,
}: CookieScope): string | undefined {
	const { enabled, baseDomain } = workspaceSubdomains;
	if (!enabled || baseDomain === "") {
		return undefined;
	}
	return hostname === baseDomain || hostname.endsWith(`.${baseDomain}`) ? baseDomain : undefined;
}

/**
 * The cookies to write for a choice. A host-only twin from before workspace addresses were on would
 * be listed first and win every read, so writing the shared cookie expires it.
 */
export function themeCookies(theme: Theme, scope: CookieScope): string[] {
	const domain = themeCookieDomain(scope);
	const shared = [
		`${THEME_COOKIE}=${theme}`,
		"Path=/",
		`Max-Age=${ONE_YEAR_SECONDS}`,
		"SameSite=Lax",
		...(domain === undefined ? [] : [`Domain=${domain}`]),
		...(scope.secure ? ["Secure"] : []),
	].join("; ");
	return domain === undefined ? [shared] : [`${THEME_COOKIE}=; Path=/; Max-Age=0`, shared];
}
