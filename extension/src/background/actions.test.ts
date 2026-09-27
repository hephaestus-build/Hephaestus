import { describe, expect, it, vi } from "vitest";

import type { ReviewContext as ReviewContextDTO } from "~/api/types.gen";
import {
	type ActionApi,
	type ActionDependencies,
	bindConfirmation,
	confirmAction,
	confirmationClosed,
	discardAction,
	INTENT_LIFETIME_MS,
	openAction,
	previewAction,
} from "~/background/actions";
import { type ContextEnvironment, WorkspaceDirectory } from "~/background/context";
import { WorkerError } from "~/background/errors";

const PAGE = "https://github.com/acme/project/pull/1";
const SOURCE_TAB = 10;
const WINDOW_TAB = 20;
const JOB = "0b7c1a52-0d3e-4c55-8a0b-1d2e3f4a5b6c";
const WORK = { id: "16", kind: "scm.pull_request", label: "#1", provider: "GITHUB" as const };

/** A worker's view of one GitHub pull request, with a store that can hold a read mid-way. */
function harness() {
	let url = PAGE;
	let stored: unknown;
	let clock = 1_000_000;
	let gate: (() => Promise<void>) | undefined;
	let answer: ReviewContextDTO = {
		work: WORK,
		canRequestReview: true,
		canInspectReviewDetails: false,
	};
	const api = {
		generation: 7,
		assertCurrent: async () => undefined,
		workspaces: async () => [],
		workspace: async () => undefined,
		resolve: vi.fn<ActionApi["resolve"]>(async () => answer),
		trace: vi.fn<ActionApi["trace"]>(async () => ({
			artifactId: 16,
			artifactKind: "scm.pull_request",
			title: "Fixture",
			practices: [],
			signals: [
				{
					id: "s1",
					discoveredVia: "MANUAL",
					displayName: "Review requested",
					occurredAt: "2026-09-26T11:00:00Z",
					revision: "abc",
					signal: "manual",
					state: "TRIGGERED",
					reviewId: JOB,
				},
			],
		})),
		requestReview: vi.fn<ActionApi["requestReview"]>(async () => ({ status: "SUBMITTED" })),
	} satisfies ActionApi;
	const directory = new WorkspaceDirectory();
	vi.spyOn(directory, "sites").mockResolvedValue([
		{ slug: "team", displayName: "Team", providerType: "GITHUB", siteOrigin: "https://github.com" },
	]);
	const env: ContextEnvironment<ActionApi> = {
		instance: {
			origin: "https://heph.test",
			apiBase: "https://heph.test/api",
			webAppOrigin: "https://heph.test",
		},
		api,
		tabUrl: async () => url,
		directory,
		now: () => new Date(clock),
	};
	let ids = 0;
	const deps: ActionDependencies = {
		now: () => clock,
		newId: () => `00000000-0000-4000-8000-${String((ids += 1)).padStart(12, "0")}`,
		store: {
			read: async () => {
				const snapshot = structuredClone(stored);
				await gate?.();
				return snapshot;
			},
			write: async (value) => {
				stored = structuredClone(value);
			},
		},
	};
	/** Opens an intent from the source tab and binds it to the window tab, as the worker does. */
	const launch = async (action?: Parameters<typeof openAction>[4]) => {
		const id = await openAction(
			env,
			deps,
			SOURCE_TAB,
			"team",
			action ?? { kind: "request-review" },
		);
		await expect(bindConfirmation(deps, id, WINDOW_TAB)).resolves.toBe(true);
		return id;
	};
	return {
		env,
		api,
		deps,
		launch,
		get stored(): unknown {
			return stored;
		},
		get gate() {
			return gate;
		},
		set gate(next: (() => Promise<void>) | undefined) {
			gate = next;
		},
		navigate: (next: string) => {
			url = next;
		},
		advance: (ms: number) => {
			clock += ms;
		},
		answer: (next: Partial<ReviewContextDTO>) => {
			answer = { ...answer, ...next };
		},
	};
}

type Harness = ReturnType<typeof harness>;

/** A read the test holds half-way, and the release that lets it finish. */
function held() {
	return {
		reading: Promise.withResolvers<undefined>(),
		release: Promise.withResolvers<undefined>(),
	};
}

/** The old intent's preview, held mid-read while a newer intent replaces it, then let go. */
async function replacedWhileHeld(
	h: Harness,
	old: string,
	{ reading, release }: ReturnType<typeof held>,
): Promise<{ preview: Promise<unknown>; newer: string }> {
	const preview = previewAction(h.env, h.deps, old, WINDOW_TAB);
	// The test observes the rejection; marking it handled keeps it from surfacing as unhandled first.
	void Promise.allSettled([preview]);
	await reading.promise;
	const newer = await h.launch();
	release.resolve(undefined);
	return { preview, newer };
}

/** A store write that, when it removes the intent, also moves the source tab to other work. */
function navigatingOnRemoval(h: Harness, original: Harness["deps"]["store"]["write"]) {
	return async (value: Parameters<typeof original>[0]) => {
		await original(value);
		if (value === undefined) {
			h.navigate("https://github.com/acme/project/pull/2");
		}
	};
}

describe("one pending action, confirmed once", () => {
	it("sends exactly one request when the window confirms twice at the same moment", async () => {
		const h = harness();
		const id = await h.launch();
		const results = await Promise.allSettled([
			confirmAction(h.env, h.deps, id, WINDOW_TAB),
			confirmAction(h.env, h.deps, id, WINDOW_TAB),
		]);
		expect(h.api.requestReview).toHaveBeenCalledOnce();
		expect(results.map((result) => result.status).toSorted()).toStrictEqual([
			"fulfilled",
			"rejected",
		]);
		expect(h.stored).toBeUndefined();
	});

	it("never sends again after an answer was lost, a reload, or a restarted worker", async () => {
		const h = harness();
		const id = await h.launch();
		h.api.requestReview.mockRejectedValueOnce(new WorkerError("network", "offline"));
		await expect(confirmAction(h.env, h.deps, id, WINDOW_TAB)).rejects.toMatchObject({
			code: "network",
		});
		// The same store under fresh dependencies stands in for a restarted worker.
		const restarted = { ...h.deps, newId: () => "unused" };
		await expect(confirmAction(h.env, restarted, id, WINDOW_TAB)).rejects.toMatchObject({
			code: "expired",
		});
		await expect(previewAction(h.env, restarted, id, WINDOW_TAB)).rejects.toMatchObject({
			code: "expired",
		});
		expect(h.api.requestReview).toHaveBeenCalledOnce();
	});

	it("reports a refused request as the server's answer, not as a failure to retry", async () => {
		const h = harness();
		const id = await h.launch();
		h.api.requestReview.mockResolvedValueOnce({
			status: "REFUSED",
			reason: "COOLDOWN_ACTIVE",
			reasonDescription: "This work was reviewed a moment ago.",
		});
		await expect(confirmAction(h.env, h.deps, id, WINDOW_TAB)).resolves.toStrictEqual({
			kind: "request-review",
			status: "REFUSED",
			reasonDescription: "This work was reviewed a moment ago.",
		});
	});

	it("says the outcome is unknown when the session changed while the request was out", async () => {
		const h = harness();
		const id = await h.launch();
		h.api.requestReview.mockRejectedValueOnce(new WorkerError("stale", "changed"));
		await expect(confirmAction(h.env, h.deps, id, WINDOW_TAB)).rejects.toMatchObject({
			code: "network",
		});
	});
});

describe("a replaced intent", () => {
	it("preserves a replacement after old confirmation checks finish", async () => {
		const h = harness();
		const old = await h.launch();
		const reading = Promise.withResolvers<undefined>();
		const release = Promise.withResolvers<ReviewContextDTO>();
		h.api.resolve.mockImplementationOnce(async () => {
			reading.resolve(undefined);
			return release.promise;
		});
		const confirming = confirmAction(h.env, h.deps, old, WINDOW_TAB);
		await reading.promise;
		const newer = await h.launch();
		release.resolve({ work: WORK, canRequestReview: true, canInspectReviewDetails: false });
		await expect(confirming).rejects.toMatchObject({ code: "expired" });
		await discardAction(h.deps, old, WINDOW_TAB);
		expect(h.stored).toMatchObject({ id: newer });
		expect(h.api.requestReview).not.toHaveBeenCalled();
	});

	it("preserves a replacement when old expiry cleanup resumes", async () => {
		const h = harness();
		const old = await h.launch();
		const { reading, release } = held();
		h.advance(INTENT_LIFETIME_MS + 1);
		// pending reads outside the critical section; its cleanup must compare again inside it.
		h.gate = async () => {
			h.gate = undefined;
			reading.resolve(undefined);
			await release.promise;
		};
		const { preview, newer } = await replacedWhileHeld(h, old, { reading, release });
		await expect(preview).rejects.toMatchObject({ code: "expired" });
		expect(h.stored).toMatchObject({ id: newer });
	});

	it("preserves a replacement when old forbidden cleanup resumes", async () => {
		const h = harness();
		const old = await h.launch();
		const { reading, release } = held();
		h.api.resolve.mockImplementationOnce(async () => {
			reading.resolve(undefined);
			await release.promise;
			throw new WorkerError("forbidden", "Access removed");
		});
		const { preview, newer } = await replacedWhileHeld(h, old, { reading, release });
		await expect(preview).rejects.toMatchObject({ code: "forbidden" });
		expect(h.stored).toMatchObject({ id: newer });
	});

	it("never sends the replaced one", async () => {
		const h = harness();
		const old = await h.launch();
		await h.launch();
		await expect(confirmAction(h.env, h.deps, old, WINDOW_TAB)).rejects.toMatchObject({
			code: "expired",
		});
		expect(h.api.requestReview).not.toHaveBeenCalled();
		expect(h.stored).toBeDefined();
	});

	it("removes an expired intent only while it is still the stored one", async () => {
		const h = harness();
		const id = await h.launch();
		h.advance(INTENT_LIFETIME_MS + 1);
		await expect(previewAction(h.env, h.deps, id, WINDOW_TAB)).rejects.toMatchObject({
			code: "expired",
		});
		expect(h.stored).toBeUndefined();
	});
});

describe("the confirmation tab binding", () => {
	it("serves and confirms only the tab the worker created", async () => {
		const h = harness();
		const id = await h.launch();
		await expect(previewAction(h.env, h.deps, id, WINDOW_TAB)).resolves.toMatchObject({
			action: { kind: "request-review" },
			workspace: { slug: "team" },
			work: { id: "16" },
		});
		for (const other of [SOURCE_TAB, 21]) {
			await expect(previewAction(h.env, h.deps, id, other)).rejects.toMatchObject({
				code: "expired",
			});
			await expect(confirmAction(h.env, h.deps, id, other)).rejects.toMatchObject({
				code: "expired",
			});
			await discardAction(h.deps, id, other);
		}
		expect(h.api.requestReview).not.toHaveBeenCalled();
		expect(h.stored).toMatchObject({ id, confirmationTabId: WINDOW_TAB });
	});

	it("is unusable before it is bound, and cannot be bound twice", async () => {
		const h = harness();
		const id = await openAction(h.env, h.deps, SOURCE_TAB, "team", { kind: "request-review" });
		await expect(previewAction(h.env, h.deps, id, WINDOW_TAB)).rejects.toMatchObject({
			code: "expired",
		});
		await expect(bindConfirmation(h.deps, id, WINDOW_TAB)).resolves.toBe(true);
		await expect(bindConfirmation(h.deps, id, 21)).resolves.toBe(false);
		expect(h.stored).toMatchObject({ confirmationTabId: WINDOW_TAB });
	});

	it("goes with its window, and an old window's closing leaves a newer intent", async () => {
		const h = harness();
		await h.launch();
		const newer = await openAction(h.env, h.deps, SOURCE_TAB, "team", { kind: "request-review" });
		await expect(bindConfirmation(h.deps, newer, 30)).resolves.toBe(true);
		await confirmationClosed(h.deps, WINDOW_TAB);
		expect(h.stored).toMatchObject({ id: newer });
		await confirmationClosed(h.deps, 30);
		expect(h.stored).toBeUndefined();
	});
});

describe("what an intent is bound to", () => {
	it("consumes without sending if the source navigates during the storage write", async () => {
		const h = harness();
		const id = await h.launch();
		const original = h.deps.store.write;
		vi.spyOn(h.deps.store, "write").mockImplementation(navigatingOnRemoval(h, original));
		await expect(confirmAction(h.env, h.deps, id, WINDOW_TAB)).rejects.toMatchObject({
			code: "expired",
		});
		expect(h.api.requestReview).not.toHaveBeenCalled();
		expect(h.stored).toBeUndefined();
		await expect(confirmAction(h.env, h.deps, id, WINDOW_TAB)).rejects.toMatchObject({
			code: "expired",
		});
		expect(h.api.requestReview).not.toHaveBeenCalled();
	});

	it("discards it when the source tab left the work, and sends nothing", async () => {
		const h = harness();
		const id = await h.launch();
		h.navigate("https://github.com/acme/project/pull/2");
		await expect(confirmAction(h.env, h.deps, id, WINDOW_TAB)).rejects.toBeInstanceOf(WorkerError);
		expect(h.api.requestReview).not.toHaveBeenCalled();
		expect(h.stored).toBeUndefined();
	});

	it("discards it after the session generation moved", async () => {
		const h = harness();
		const id = await h.launch();
		const next = { ...h.env, api: { ...h.env.api, generation: 8 } };
		await expect(confirmAction(next, h.deps, id, WINDOW_TAB)).rejects.toMatchObject({
			code: "expired",
		});
		expect(h.api.requestReview).not.toHaveBeenCalled();
	});

	it("rechecks the reader's standing before sending", async () => {
		const h = harness();
		const id = await h.launch();
		h.answer({ canRequestReview: false });
		await expect(confirmAction(h.env, h.deps, id, WINDOW_TAB)).rejects.toMatchObject({
			code: "forbidden",
		});
		expect(h.api.requestReview).not.toHaveBeenCalled();
	});

	it("refuses a request from a reader who may not ask, before any window opens", async () => {
		const h = harness();
		h.answer({ canRequestReview: false });
		await expect(
			openAction(h.env, h.deps, SOURCE_TAB, "team", { kind: "request-review" }),
		).rejects.toMatchObject({ code: "forbidden" });
		expect(h.stored).toBeUndefined();
	});
});
