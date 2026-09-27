import { describe, expect, it } from "vitest";

import { defaultInstance, serverPort, updatesConfig } from "../app.config";

describe("serverPort", () => {
	it("reads the worktree server's port from its environment file", () => {
		expect(serverPort("COMPOSE_PROJECT_NAME=x\nSERVER_PORT=18380\nPOSTGRES_PORT=55462\n")).toBe(
			18_380,
		);
	});

	it("falls back to the server's own default without a file or setting", () => {
		expect(serverPort(undefined)).toBe(8080);
		expect(serverPort("#SERVER_PORT=9999\n")).toBe(8080);
	});
});

describe("defaultInstance", () => {
	it("offers hephaestus.build in the store build", () => {
		expect(defaultInstance("production", {}, undefined)).toStrictEqual({
			defaultInstance: "hephaestus.build",
		});
	});

	it("offers the worktree server in the development build", () => {
		expect(defaultInstance("development", {}, "SERVER_PORT=18380\n")).toStrictEqual({
			devServerPort: 18_380,
		});
	});

	it("requires a preview build to name its staging server", () => {
		expect(() => defaultInstance("preview", {}, undefined)).toThrow("PREVIEW_INSTANCE");
		expect(
			defaultInstance("preview", { PREVIEW_INSTANCE: "staging.example.org" }, undefined),
		).toStrictEqual({
			defaultInstance: "staging.example.org",
		});
	});
});

describe("updatesConfig", () => {
	it("keeps updates off without an EAS project", () => {
		expect(updatesConfig("production", undefined, undefined)).toStrictEqual({ enabled: false });
	});

	it("refuses a store or preview build that would accept unsigned updates", () => {
		expect(() => updatesConfig("production", "project", undefined)).toThrow("unsigned");
		expect(() => updatesConfig("preview", "project", undefined)).toThrow("unsigned");
	});

	it("signs a store build's updates with the certificate", () => {
		expect(updatesConfig("production", "project", "certs/certificate.pem")).toMatchObject({
			enabled: true,
			codeSigningCertificate: "certs/certificate.pem",
		});
	});

	it("lets a development build take updates from its own channel unsigned", () => {
		expect(updatesConfig("development", "project", undefined)).toMatchObject({ enabled: true });
	});
});
