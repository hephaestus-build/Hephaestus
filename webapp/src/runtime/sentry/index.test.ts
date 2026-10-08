import { afterEach, describe, expect, it, vi } from "vitest";

import { DATA_COLLECTION, stripRequestUserAndBreadcrumbs } from "./index";

function leaves(value: unknown): unknown[] {
	return typeof value === "object" && value !== null && !Array.isArray(value)
		? Object.values(value).flatMap(leaves)
		: [value];
}

describe("the data an error report may carry", () => {
	it("turns no category on", () => {
		expect(leaves(DATA_COLLECTION).filter((leaf) => leaf === true)).toStrictEqual([]);
		expect(DATA_COLLECTION.httpBodies).toStrictEqual([]);
	});
});

it("strips request, user, and breadcrumb fields", () => {
	const event = stripRequestUserAndBreadcrumbs({
		type: undefined,
		request: { url: "https://example.test/private?token=secret", headers: { cookie: "secret" } },
		user: { id: "person" },
		breadcrumbs: [{ category: "navigation", data: { from: "/private" } }],
	});

	expect(event?.request).toBeUndefined();
	expect(event?.user).toBeUndefined();
	expect(event?.breadcrumbs).toBeUndefined();
});

describe("unsubscribe privacy", () => {
	afterEach(() => window.history.replaceState(null, "", "/"));

	it.each(["/unsubscribe", "/unsubscribe/"])(
		"drops the entire report on bearer-link page %s",
		(path) => {
			window.history.replaceState(null, "", `${path}?token=private-capability`);
			expect(
				stripRequestUserAndBreadcrumbs({ type: undefined, message: "A request failed" }),
			).toBeNull();
		},
	);
});

describe("the environment an error report names", () => {
	afterEach(() => {
		delete window.__ENV__;
	});

	it("is the deployment's, and local when the deployment leaves it unset", async () => {
		vi.resetModules();
		// The entrypoint writes an unset variable as "".
		window.__ENV__ = { SENTRY_ENVIRONMENT: "" };
		const { sentryEnvironment } = await import("./config");

		expect(sentryEnvironment).toBe("local");
	});
});
