import { useTheme } from "fumadocs-ui/provider/base";
import { useEffect, useRef } from "react";

import { isTheme, THEME_COOKIE } from "@webapp/runtime/theme/theme-cookie.ts";
import { SITE_URL } from "@/lib/site";

const ONE_YEAR_MS = 365 * 24 * 60 * 60 * 1000;

/*
 * The theme is one cookie across the Hephaestus hosts (ADR 0053, amendment of 2026-10-26). Before paint, this script
 * copies the cookie into the `theme` key that `next-themes` reads. The first valid cookie wins, as in the web app's
 * `readThemeCookie`.
 */
export const themeCookieScript = `(function(){var c;document.cookie.split(";").some(function(e){var p=e.trim().split("=");if(p[0]===${JSON.stringify(THEME_COOKIE)}&&(p[1]==="light"||p[1]==="dark"||p[1]==="system")){c=p[1];return true}return false});try{if(c==="light"||c==="dark"){localStorage.setItem("theme",c)}else if(c==="system"){localStorage.removeItem("theme")}}catch(e){}})();`;

/*
 * Writes the reader's choice back, so a toggle here carries over to every Hephaestus host. The published site shares
 * the cookie with its parent domain; a preview or a local build keeps it to its own host. The write needs no
 * synchronous read, so it uses the Cookie Store; a browser without it keeps the choice on this site only.
 */
export function useThemeCookie(): void {
	const { theme } = useTheme();
	const first = useRef(true);
	useEffect(() => {
		if (theme === undefined) {
			return;
		}
		if (first.current) {
			first.current = false;
			return;
		}
		const value = isTheme(theme) ? theme : "system";
		const { hostname } = window.location;
		const domain =
			hostname === new URL(SITE_URL).hostname ? hostname.split(".").slice(1).join(".") : undefined;
		if (!("cookieStore" in window)) {
			return;
		}
		void window.cookieStore.set({
			name: THEME_COOKIE,
			value,
			path: "/",
			sameSite: "lax",
			expires: Date.now() + ONE_YEAR_MS,
			...(domain === undefined ? {} : { domain }),
		});
	}, [theme]);
}
