import type { SessionView } from "@/api/types.gen";

import { daysAfter, daysBefore, hoursBefore } from "./story-clock";

/** The current device, two other browsers and a browser extension. */
export const storySessions: SessionView[] = [
	{
		jti: "sess-current-001",
		client: "WEB",
		current: true,
		userAgent:
			"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36",
		ip: "192.0.2.10",
		issuedAt: hoursBefore(3),
		expiresAt: daysAfter(30),
	},
	{
		jti: "sess-other-002",
		client: "WEB",
		current: false,
		userAgent: "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:126.0) Gecko/20100101 Firefox/126.0",
		ip: "198.51.100.23",
		issuedAt: daysBefore(4),
		expiresAt: daysAfter(26),
	},
	{
		jti: "sess-other-003",
		client: "WEB",
		current: false,
		userAgent:
			"Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1",
		ip: "203.0.113.77",
		issuedAt: daysBefore(8),
		expiresAt: daysAfter(22),
	},
	{
		jti: "sess-extension-004",
		client: "BROWSER_EXTENSION",
		current: false,
		userAgent:
			"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36",
		ip: "192.0.2.10",
		issuedAt: hoursBefore(2),
		expiresAt: daysAfter(7),
	},
];
