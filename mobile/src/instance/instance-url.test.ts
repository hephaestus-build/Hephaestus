import { describe, expect, it } from "vitest";

import { instanceUrlCandidates, isOlderThan, webAddress } from "./instance-url";

describe("instanceUrlCandidates", () => {
	it("tries the standard /api mount before the root for a bare host", () => {
		expect(instanceUrlCandidates("hephaestus.example.org", false)).toStrictEqual({
			ok: true,
			label: "hephaestus.example.org",
			candidates: ["https://hephaestus.example.org/api", "https://hephaestus.example.org"],
		});
	});

	it("uses an address that already names the API as it is", () => {
		expect(instanceUrlCandidates("https://team.example.org/api/", false)).toMatchObject({
			ok: true,
			candidates: ["https://team.example.org/api"],
		});
	});

	it("drops the query and fragment a pasted browser address carries", () => {
		expect(instanceUrlCandidates("https://team.example.org/w/team?x=1#top", false)).toMatchObject({
			ok: true,
			candidates: ["https://team.example.org/w/team/api", "https://team.example.org/w/team"],
		});
	});

	it("refuses plain HTTP unless the build allows local development", () => {
		expect(instanceUrlCandidates("http://localhost:18380", false)).toStrictEqual({
			ok: false,
			reason: "insecure",
		});
		expect(instanceUrlCandidates("http://localhost:18380", true)).toMatchObject({
			ok: true,
			candidates: ["http://localhost:18380/api", "http://localhost:18380"],
		});
	});

	it("refuses what is not an address a person could mean", () => {
		expect(instanceUrlCandidates("   ", false)).toStrictEqual({ ok: false, reason: "empty" });
		expect(instanceUrlCandidates("ftp://files.example.org", false)).toStrictEqual({
			ok: false,
			reason: "invalid",
		});
		expect(instanceUrlCandidates("https://user:secret@example.org", false)).toStrictEqual({
			ok: false,
			reason: "invalid",
		});
	});
});

describe("isOlderThan", () => {
	it("compares numerically, part by part", () => {
		expect(isOlderThan("0.9.0", "0.10.0")).toBe(true);
		expect(isOlderThan("1.2.3", "1.2.3")).toBe(false);
		expect(isOlderThan("2.0.0", "1.9.9")).toBe(false);
	});

	it("treats a blank or unreadable minimum as none", () => {
		expect(isOlderThan("0.1.0", "")).toBe(false);
		expect(isOlderThan("0.1.0", "latest")).toBe(false);
	});
});

describe("webAddress", () => {
	it("keeps a store instance's HTTPS web address", () => {
		expect(webAddress("https://hephaestus.build/", "https://hephaestus.build/api", false)).toBe(
			"https://hephaestus.build",
		);
	});

	it("refuses plain HTTP outside a development build, credentials, queries and non-web schemes", () => {
		expect(
			webAddress("http://team.example.org", "https://team.example.org/api", false),
		).toBeUndefined();
		expect(
			webAddress("https://user:pw@team.example.org", "https://team.example.org/api", false),
		).toBeUndefined();
		expect(
			webAddress("https://team.example.org/?next=x", "https://team.example.org/api", false),
		).toBeUndefined();
		expect(webAddress("data:text/html,hi", "https://team.example.org/api", true)).toBeUndefined();
		expect(webAddress("<html>", "https://team.example.org/api", true)).toBeUndefined();
	});

	it("points a development server's localhost at the host the device reached the API on, keeping its port", () => {
		expect(webAddress("http://localhost:4200", "http://10.0.2.2:18380", true)).toBe(
			"http://10.0.2.2:4200",
		);
		expect(webAddress("http://127.0.0.1:4200/", "http://192.168.1.20:18380", true)).toBe(
			"http://192.168.1.20:4200",
		);
	});
});
