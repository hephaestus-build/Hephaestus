import { describe, expect, it } from "vitest";

import { readThemeCookie, themeCookieDomain, themeCookies } from "./theme-cookie";

const subdomains = { enabled: true, baseDomain: "hephaestus.build" };

describe("readThemeCookie", () => {
	it("reads the theme among other cookies", () => {
		expect(readThemeCookie("a=1; hephaestus-theme=light; b=2")).toBe("light");
	});

	// A sibling host under the base domain can write this cookie, so it may change only the theme.
	it("ignores a value that is not a theme", () => {
		expect(readThemeCookie("hephaestus-theme=solarized")).toBeUndefined();
		expect(readThemeCookie("hephaestus-theme=dark%3B%20x")).toBeUndefined();
		expect(readThemeCookie("")).toBeUndefined();
	});
});

describe("themeCookieDomain", () => {
	it("shares the cookie across the base domain when workspace addresses are on", () => {
		const scope = { secure: true, workspaceSubdomains: subdomains };
		expect(themeCookieDomain({ ...scope, hostname: "artemis.hephaestus.build" })).toBe(
			"hephaestus.build",
		);
		expect(themeCookieDomain({ ...scope, hostname: "hephaestus.build" })).toBe("hephaestus.build");
	});

	it("keeps the cookie on its host otherwise", () => {
		expect(
			themeCookieDomain({
				hostname: "hephaestus.build",
				secure: true,
				workspaceSubdomains: { ...subdomains, enabled: false },
			}),
		).toBeUndefined();
		expect(
			themeCookieDomain({ hostname: "localhost", secure: false, workspaceSubdomains: subdomains }),
		).toBeUndefined();
		expect(
			themeCookieDomain({
				hostname: "evilhephaestus.build",
				secure: true,
				workspaceSubdomains: subdomains,
			}),
		).toBeUndefined();
	});
});

describe("themeCookies", () => {
	it("expires a host-only twin, then writes a readable, year-long, lax cookie on the shared domain", () => {
		expect(
			themeCookies("dark", {
				hostname: "artemis.hephaestus.build",
				secure: true,
				workspaceSubdomains: subdomains,
			}),
		).toStrictEqual([
			"hephaestus-theme=; Path=/; Max-Age=0",
			"hephaestus-theme=dark; Path=/; Max-Age=31536000; SameSite=Lax; Domain=hephaestus.build; Secure",
		]);
	});

	it("writes one host-only cookie without Secure on a plain-HTTP local host", () => {
		expect(
			themeCookies("light", {
				hostname: "localhost",
				secure: false,
				workspaceSubdomains: subdomains,
			}),
		).toStrictEqual(["hephaestus-theme=light; Path=/; Max-Age=31536000; SameSite=Lax"]);
	});
});
