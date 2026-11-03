import { afterEach, describe, expect, it, vi } from "vitest";

import { serverTooOld } from "~/background/server-fit";
import { isOlderThanSupported, serverTooOldText } from "~/shared/server-version";

const INSTANCE = {
	origin: "https://old.example.test",
	apiBase: "https://old.example.test/api",
	webAppOrigin: "https://old.example.test",
};

const discovery = (body: Response | (() => never)) => {
	const asked: string[] = [];
	vi.stubGlobal("fetch", async (input: RequestInfo | URL, init?: RequestInit) => {
		asked.push(new Request(input, init).url);
		return typeof body === "function" ? body() : body.clone();
	});
	return asked;
};

afterEach(() => {
	vi.unstubAllGlobals();
});

describe("isOlderThanSupported", () => {
	it.each(["0.95.1", "0.99.99"])("says %s is older", (version) => {
		expect(isOlderThanSupported(version)).toBe(true);
	});

	it.each(["1.0.0", "1.0.0-rc.2", "1.4.2", "2.0.0", "DEV", "", "1.0"])(
		"does not say %s is older",
		(version) => {
			expect(isOlderThanSupported(version)).toBe(false);
		},
	);
});

describe("serverTooOld", () => {
	it("reads the discovery file at the instance's origin", async () => {
		const asked = discovery(Response.json({ version: "1.0.0" }));
		await expect(serverTooOld(INSTANCE)).resolves.toBeUndefined();
		expect(asked).toStrictEqual([`${INSTANCE.origin}/.well-known/hephaestus.json`]);
	});

	it("names the version of an older server", async () => {
		discovery(Response.json({ version: "0.95.1" }));
		await expect(serverTooOld(INSTANCE)).resolves.toStrictEqual({
			serverVersion: "0.95.1",
			oldestServer: "1.0.0",
		});
	});

	it("treats a discovery file without a version as from before 1.0", async () => {
		discovery(Response.json({ apexOrigin: INSTANCE.origin }));
		await expect(serverTooOld(INSTANCE)).resolves.toStrictEqual({ oldestServer: "1.0.0" });
	});

	it.each([
		["a missing file", new Response(null, { status: 404 })],
		["a page instead of the file", new Response("<html></html>", { status: 200 })],
		["a version that is not a text", Response.json({ version: 1 })],
		["a version longer than any release", Response.json({ version: "1".repeat(65) })],
	])("claims nothing about age from %s", async (_label, response) => {
		discovery(response);
		await expect(serverTooOld(INSTANCE)).resolves.toBeUndefined();
	});

	it("claims nothing about age when the instance cannot be reached", async () => {
		discovery(() => {
			throw new TypeError("offline");
		});
		await expect(serverTooOld(INSTANCE)).resolves.toBeUndefined();
	});
});

describe("serverTooOld on a stalled host", () => {
	it("stops waiting and claims nothing about age", async () => {
		const never = Promise.withResolvers<Response>();
		vi.stubGlobal("fetch", async (input: Request) => {
			input.signal.addEventListener("abort", () => {
				never.reject(new DOMException("stalled", "AbortError"));
			});
			return never.promise;
		});
		await expect(serverTooOld(INSTANCE, 5)).resolves.toBeUndefined();
	});
});

describe("serverTooOldText", () => {
	it("does not guess a version that the server did not name", () => {
		expect(serverTooOldText({ instanceHost: "old.example.test", oldestServer: "1.0.0" })).toContain(
			"runs a version older than 1.0.0",
		);
	});
});
