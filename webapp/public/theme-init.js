// Runs before the first paint, so the page never flashes the other theme. It stays a classic script
// in `public/`: the CSP allows `script-src 'self'` and no inline code. It reads the cookie exactly as
// `readThemeCookie` in `src/runtime/theme/theme-cookie.ts` does (a parity test holds them together),
// then the per-origin choice from before the cookie, then the `system` default.
{
	const themes = new Set(["dark", "light", "system"]);
	const cookie = document.cookie
		.split(";")
		.map((entry) => entry.trim().split("="))
		.find(([name, value]) => name === "hephaestus-theme" && themes.has(value))?.[1];
	let legacy = null;
	try {
		legacy = window.localStorage.getItem("theme");
	} catch {
		// Storage can be blocked; the cookie or the default still decides.
	}
	const choice = cookie ?? (themes.has(legacy) ? legacy : "system");
	let applied = choice;
	if (choice === "system") {
		applied = window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
	}
	document.documentElement.classList.remove("light", "dark");
	document.documentElement.classList.add(applied);
	document.documentElement.dataset.colorMode = applied;
}
