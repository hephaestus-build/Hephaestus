import assert from "node:assert/strict";
import { test } from "node:test";

import {
	FREEZE_NOT_LIFTED,
	hold,
	type HoldDeps,
	type HoldSignal,
	INTERRUPTED,
	STAGING_MOVED,
} from "./lib/qual-hold.ts";

const pinned = "a".repeat(40);
const other = "b".repeat(40);

interface Fake {
	deps: HoldDeps;
	promotions: { commit: string; freeze: boolean }[];
	started: string[];
	killed: HoldSignal[];
	signal: (signal: HoldSignal) => void;
}

/** A staging that reports `applied` in turn, a promoter that records each request, and a measurement that exits `code`. */
function fake(options: {
	applied?: string[];
	code?: number;
	liftFails?: boolean;
	onStart?: (fake: Fake) => void;
}): Fake {
	const applied = [...(options.applied ?? [pinned, pinned, pinned])];
	let handler: ((signal: HoldSignal) => void) | undefined;
	const result: Fake = {
		promotions: [],
		started: [],
		killed: [],
		signal: (signal) => {
			handler?.(signal);
		},
		deps: {
			applied: async () => applied.shift() ?? pinned,
			promote: async (commit, freeze) => {
				result.promotions.push({ commit, freeze });
				if (!freeze && options.liftFails === true) {
					throw new Error("the promotion run failed");
				}
				return String(result.promotions.length);
			},
			start: (frozen) => {
				result.started.push(frozen);
				options.onStart?.(result);
				return {
					exited: Promise.resolve(options.code ?? 0),
					kill: (signal) => {
						result.killed.push(signal);
					},
				};
			},
			onSignal: (next) => {
				handler = next;
			},
			log: () => undefined,
		},
	};
	return result;
}

await test("a measurement runs on the frozen commit, and the freeze is lifted afterwards", async () => {
	const staging = fake({});
	assert.equal(await hold(staging.deps), 0);
	assert.deepEqual(staging.started, [pinned]);
	assert.deepEqual(staging.promotions, [
		{ commit: pinned, freeze: true },
		{ commit: pinned, freeze: false },
	]);
});

await test("a failed measurement still lifts the freeze and keeps its own exit code", async () => {
	const staging = fake({ code: 1 });
	assert.equal(await hold(staging.deps), 1);
	assert.deepEqual(staging.promotions.at(-1), { commit: pinned, freeze: false });
});

await test("an interrupt reaches the measurement, and the freeze is lifted", async () => {
	// A signal arrives as an event, after the measurement started.
	const staging = fake({
		code: 130,
		onStart: (self) => {
			queueMicrotask(() => {
				self.signal("SIGTERM");
			});
		},
	});
	assert.equal(await hold(staging.deps), 130);
	assert.deepEqual(staging.killed, ["SIGTERM"]);
	assert.deepEqual(staging.promotions.at(-1), { commit: pinned, freeze: false });
});

await test("an interrupt while the freeze is set measures nothing and still lifts the freeze", async () => {
	const staging = fake({});
	const { promote } = staging.deps;
	staging.deps.promote = async (commit, freeze) => {
		const run = await promote(commit, freeze);
		if (freeze) {
			staging.signal("SIGINT");
		}
		return run;
	};
	assert.equal(await hold(staging.deps), INTERRUPTED);
	assert.deepEqual(staging.started, []);
	assert.deepEqual(staging.promotions.at(-1), { commit: pinned, freeze: false });
});

await test("staging that moves during the measurement makes the result invalid", async () => {
	const staging = fake({ applied: [pinned, pinned, other] });
	assert.equal(await hold(staging.deps), STAGING_MOVED);
	assert.deepEqual(staging.promotions.at(-1), { commit: pinned, freeze: false });
});

await test("staging that moves while the freeze is set measures nothing and lifts on the new commit", async () => {
	const staging = fake({ applied: [pinned, other] });
	assert.equal(await hold(staging.deps), STAGING_MOVED);
	assert.deepEqual(staging.started, []);
	assert.deepEqual(staging.promotions.at(-1), { commit: other, freeze: false });
});

await test("a lift that fails wins over every other exit code", async () => {
	const staging = fake({ applied: [pinned, pinned, other], code: 1, liftFails: true });
	assert.equal(await hold(staging.deps), FREEZE_NOT_LIFTED);
});
