import { describe, expect, it } from "vitest";

import { reconcileRegistration } from "./reconcile";

describe("reconcileRegistration", () => {
	it("unregisters a device whose notifications were turned off in the system settings", () => {
		expect(reconcileRegistration({ registered: true, permitted: false, tokenChanged: false })).toBe(
			"unregister",
		);
	});

	it("registers the new token of a device the person turned on", () => {
		expect(reconcileRegistration({ registered: true, permitted: true, tokenChanged: true })).toBe(
			"refresh",
		);
	});

	it("never registers a device the person did not turn on, even with permission or a new token", () => {
		expect(reconcileRegistration({ registered: false, permitted: true, tokenChanged: true })).toBe(
			"none",
		);
	});

	it("leaves a registered, permitted device with its token alone", () => {
		expect(reconcileRegistration({ registered: true, permitted: true, tokenChanged: false })).toBe(
			"none",
		);
	});
});
