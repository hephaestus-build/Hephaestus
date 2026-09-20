import { describe, expect, it } from "vitest";

import { getProviderLabel } from "@/lib/provider/provider-labels";

describe("getProviderLabel", () => {
	it("maps every provider type the server can emit to human copy", () => {
		expect(getProviderLabel("GITHUB")).toBe("GitHub");
		expect(getProviderLabel("GITLAB")).toBe("GitLab");
		expect(getProviderLabel("SLACK")).toBe("Slack");
		expect(getProviderLabel("OUTLINE")).toBe("Outline");
	});

	it("is case-insensitive — discovery emits uppercase, some callers lowercase", () => {
		expect(getProviderLabel("outline")).toBe("Outline");
	});

	it("falls back to the raw type for an unknown provider rather than dropping it", () => {
		expect(getProviderLabel("BITBUCKET")).toBe("BITBUCKET");
	});

	it("uses the prose fallback when the type is missing", () => {
		expect(getProviderLabel(undefined)).toBe("that provider");
		expect(getProviderLabel(null, "your provider")).toBe("your provider");
	});
});
