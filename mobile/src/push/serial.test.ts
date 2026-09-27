import { describe, expect, it } from "vitest";

import { reconcileRegistration } from "./reconcile";
import { createSerialQueue } from "./serial";

/** The server's view of this installation, and a refresh whose request the test releases. */
function server() {
	let registered = true;
	let releaseRefresh: (() => void) | undefined;
	return {
		get registered() {
			return registered;
		},
		refresh: async () => {
			// oxlint-disable-next-line promise/avoid-new -- the test holds the request open itself
			await new Promise<void>((resolve) => {
				releaseRefresh = resolve;
			});
			registered = true;
		},
		disable: async () => {
			registered = false;
		},
		release: () => releaseRefresh?.(),
	};
}

async function turns(count = 10) {
	for (let turn = 0; turn < count; turn += 1) {
		await Promise.resolve();
	}
}

describe("push registration queue", () => {
	it("lets an explicit off win over a token refresh already on its way", async () => {
		const queue = createSerialQueue();
		const device = server();

		const refresh = queue(device.refresh);
		await turns();
		const off = queue(device.disable);
		device.release();
		await Promise.all([refresh, off]);

		expect(device.registered).toBe(false);
	});

	it("does not bring a device back after it was turned off, on the next return to the foreground", async () => {
		const queue = createSerialQueue();
		const device = server();
		await queue(device.disable);

		const action = await queue(async () =>
			reconcileRegistration({ registered: device.registered, permitted: true, tokenChanged: true }),
		);

		expect(action).toBe("none");
		expect(device.registered).toBe(false);
	});

	it("keeps going after a task fails", async () => {
		const queue = createSerialQueue();
		const failed = queue(async () => {
			throw new Error("offline");
		});
		await expect(failed).rejects.toThrow("offline");
		await expect(queue(async () => "next")).resolves.toBe("next");
	});
});
