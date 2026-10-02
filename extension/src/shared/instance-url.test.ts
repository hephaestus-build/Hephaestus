import { describe, expect, it } from "vitest";

import { originPattern, parseInstanceOrigin } from "~/shared/instance-url";

describe("parseInstanceOrigin", () => {
	it("keeps only the origin of an HTTPS address", () => {
		expect(
			parseInstanceOrigin("https://Hephaestus.Example.com/w/team?x=1", {
				allowLoopbackHttp: false,
			}),
		).toStrictEqual({ ok: true, origin: "https://hephaestus.example.com" });
	});

	it("assumes HTTPS when no scheme is typed", () => {
		expect(parseInstanceOrigin("staging.example.com", { allowLoopbackHttp: false })).toStrictEqual({
			ok: true,
			origin: "https://staging.example.com",
		});
	});

	it("refuses plain HTTP, even on loopback, in a production build", () => {
		expect(
			parseInstanceOrigin("http://localhost:8080", { allowLoopbackHttp: false }),
		).toStrictEqual({ ok: false, reason: "insecure" });
	});

	it("accepts loopback HTTP in a development build and nothing else", () => {
		expect(
			parseInstanceOrigin("http://127.0.0.1:18480", { allowLoopbackHttp: true }),
		).toStrictEqual({
			ok: true,
			origin: "http://127.0.0.1:18480",
		});
		expect(parseInstanceOrigin("http://example.com", { allowLoopbackHttp: true })).toStrictEqual({
			ok: false,
			reason: "insecure",
		});
	});

	it("refuses credentials and other schemes", () => {
		expect(
			parseInstanceOrigin("https://a:b@example.com", { allowLoopbackHttp: false }),
		).toStrictEqual({ ok: false, reason: "credentials" });
		expect(parseInstanceOrigin("ftp://example.com", { allowLoopbackHttp: true })).toStrictEqual({
			ok: false,
			reason: "invalid",
		});
		expect(parseInstanceOrigin("   ", { allowLoopbackHttp: true })).toStrictEqual({
			ok: false,
			reason: "invalid",
		});
	});
});

describe("originPattern", () => {
	it("names every port of the host, which is how Chrome match patterns work", () => {
		expect(originPattern("http://localhost:18480")).toBe("http://localhost/*");
		expect(originPattern("https://gitlab.example.test")).toBe("https://gitlab.example.test/*");
	});
});
