import { describe, expect, it } from "vitest";

import { sessionLabel } from "./session-label";

describe("sessionLabel", () => {
	it("names this app by platform", () => {
		expect(sessionLabel("Hephaestus/0.1.0 (iOS 27.0)", true)).toBe(
			"Hephaestus app on iPhone or iPad",
		);
		expect(sessionLabel("Hephaestus/0.1.0 (Android 36)", true)).toBe("Hephaestus app on Android");
	});

	it("names a browser without claiming more than the agent says", () => {
		expect(
			sessionLabel(
				"Mozilla/5.0 (Macintosh) AppleWebKit/605.1.15 Version/27.0 Safari/605.1.15",
				false,
			),
		).toBe("Safari in a browser");
		expect(sessionLabel("Mozilla/5.0 (X11) Gecko/20100101 Firefox/140.0", false)).toBe(
			"Firefox in a browser",
		);
		expect(
			sessionLabel("Mozilla/5.0 AppleWebKit/537.36 Chrome/140.0 Safari/537.36 Edg/140.0", false),
		).toBe("Edge in a browser");
		expect(sessionLabel(undefined, false)).toBe("Unknown device");
	});
});
