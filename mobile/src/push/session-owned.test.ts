import { describe, expect, it, vi } from "vitest";

import { openForSession, registerForSession, type RegistrationSteps } from "./session-owned";

/** A session that can be replaced while a step is waiting, as signing out and in again does. */
function sessions() {
	let current = "A";
	return {
		startedBy: (owner: string) => () => current === owner,
		switchTo: (next: string) => {
			current = next;
		},
	};
}

function registration(overrides: Partial<RegistrationSteps> = {}): RegistrationSteps {
	return {
		stillCurrent: () => true,
		projectId: "project",
		permitted: async () => true,
		pushToken: async () => "ExponentPushToken[x]",
		send: vi.fn<(pushToken: string) => Promise<void>>(async () => undefined),
		...overrides,
	};
}

describe("registerForSession", () => {
	it("registers the token for the session that asked", async () => {
		const steps = registration();

		await expect(registerForSession(steps)).resolves.toBe("done");
		expect(steps.send).toHaveBeenCalledWith("ExponentPushToken[x]");
	});

	it("does not register a token that arrives after the person switched account", async () => {
		const world = sessions();
		const steps = registration({
			stillCurrent: world.startedBy("A"),
			pushToken: async () => {
				world.switchTo("B");
				return "ExponentPushToken[late]";
			},
		});

		await expect(registerForSession(steps)).resolves.toBe("superseded");
		expect(steps.send).not.toHaveBeenCalled();
	});

	it("does not ask for a token once the session ended while the permission was pending", async () => {
		const world = sessions();
		const pushToken = vi.fn<(projectId: string) => Promise<string>>(async () => "token");
		const steps = registration({
			stillCurrent: world.startedBy("A"),
			permitted: async () => {
				world.switchTo("signed-out");
				return true;
			},
			pushToken,
		});

		await expect(registerForSession(steps)).resolves.toBe("superseded");
		expect(pushToken).not.toHaveBeenCalled();
	});

	it("does nothing without permission or on a build that cannot receive push", async () => {
		const denied = registration({ permitted: async () => false });
		const noProject = registration({ projectId: undefined });

		await expect(registerForSession(denied)).resolves.toBe("skipped");
		await expect(registerForSession(noProject)).resolves.toBe("skipped");
		expect(denied.send).not.toHaveBeenCalled();
		expect(noProject.send).not.toHaveBeenCalled();
	});
});

describe("openForSession", () => {
	it("switches to the notification's workspace, then shows practice feedback", async () => {
		const order: string[] = [];

		await openForSession({
			addressedTo: "sign-in-a",
			currentSignIn: () => "sign-in-a",
			stillCurrent: () => true,
			selectWorkspace: async () => {
				order.push("workspace");
			},
			showFeedback: () => {
				order.push("feedback");
			},
		});

		expect(order).toStrictEqual(["workspace", "feedback"]);
	});

	it("does not navigate a session that took over while the workspace was being switched", async () => {
		const world = sessions();
		const showFeedback = vi.fn<() => void>();

		const outcome = await openForSession({
			addressedTo: "sign-in-a",
			currentSignIn: () => "sign-in-a",
			stillCurrent: world.startedBy("A"),
			selectWorkspace: async () => world.switchTo("B"),
			showFeedback,
		});

		expect(outcome).toBe("superseded");
		expect(showFeedback).not.toHaveBeenCalled();
	});

	it("ignores a notification sent to an earlier sign-in, whatever its workspace", async () => {
		const selectWorkspace = vi.fn<() => Promise<void>>(async () => undefined);
		const showFeedback = vi.fn<() => void>();

		const outcome = await openForSession({
			addressedTo: "sign-in-before-switching-account",
			currentSignIn: () => "sign-in-now",
			stillCurrent: () => true,
			selectWorkspace,
			showFeedback,
		});

		expect(outcome).toBe("not-ours");
		expect(selectWorkspace).not.toHaveBeenCalled();
		expect(showFeedback).not.toHaveBeenCalled();
	});

	it("ignores a notification once signed out", async () => {
		const showFeedback = vi.fn<() => void>();

		const outcome = await openForSession({
			addressedTo: "sign-in-a",
			currentSignIn: () => undefined,
			stillCurrent: () => true,
			selectWorkspace: async () => undefined,
			showFeedback,
		});

		expect(outcome).toBe("not-ours");
		expect(showFeedback).not.toHaveBeenCalled();
	});
});
