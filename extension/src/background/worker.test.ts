import { setImmediate } from "node:timers/promises";

import { fakeBrowser } from "@webext-core/fake-browser";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { Credentials } from "~/background/session";
import { readInstance, readReportView, writeInstance } from "~/background/storage";
import type { RpcRequest } from "~/shared/rpc";

const platform = vi.hoisted(() => ({
	identity: {
		getRedirectURL: vi.fn<(path?: string) => string>(),
		launchWebAuthFlow:
			vi.fn<(details: { url: string; interactive?: boolean }) => Promise<string>>(),
	},
	permissions: {
		getAll: vi.fn<() => Promise<{ origins: string[] }>>(),
		remove: vi.fn<() => Promise<boolean>>(),
	},
	scripting: {
		getRegisteredContentScripts: vi.fn<() => Promise<never[]>>(),
		registerContentScripts: vi.fn<(scripts: { matches: string[] }[]) => Promise<void>>(),
	},
	tabs: {
		get: vi.fn<(tabId: number) => Promise<{ id: number; url?: string }>>(),
		query: vi.fn<() => Promise<{ id: number }[]>>(),
		sendMessage: vi.fn<() => Promise<void>>(),
	},
	sessionGet: vi.fn<(key: string) => Promise<Record<string, unknown>>>(),
	sessionSet: vi.fn<(items: Record<string, unknown>) => Promise<void>>(),
	localGet: vi.fn<(key: string) => Promise<Record<string, unknown>>>(),
}));

vi.mock("@wxt-dev/browser", () => ({
	browser: {
		...fakeBrowser,
		identity: platform.identity,
		permissions: platform.permissions,
		scripting: platform.scripting,
		tabs: platform.tabs,
		storage: {
			...fakeBrowser.storage,
			session: {
				...fakeBrowser.storage.session,
				get: platform.sessionGet,
				set: platform.sessionSet,
			},
			local: { ...fakeBrowser.storage.local, get: platform.localGet },
		},
	},
}));

const ID = "ijkajblcbajjpjbknfgdiiiljipafiko";
const A = {
	origin: "https://a.test",
	apiBase: "https://a.test/api",
	webAppOrigin: "https://a.test",
};
const B = {
	origin: "https://b.test",
	apiBase: "https://b.test/api",
	webAppOrigin: "https://b.test",
};
const sender = { id: ID, url: `chrome-extension://${ID}/options.html`, frameId: 0 };

function tokens(label: string) {
	return {
		accessToken: `access-${label}`,
		refreshToken: `refresh-${label}`,
		accessTokenExpiresAt: "2099-01-01T00:00:00Z",
		sessionExpiresAt: "2099-01-02T00:00:00Z",
	};
}

function completeSignInURL(url: string): string {
	const start = new URL(url);
	const redirect = new URL(start.searchParams.get("redirect_uri") ?? "");
	redirect.searchParams.set("state", start.searchParams.get("state") ?? "");
	redirect.searchParams.set("code", "c".repeat(43));
	return redirect.href;
}

async function harness() {
	const worker = await import("~/background/worker");
	let consent = false;
	const sent: { origin: string; path: string; body: string }[] = [];
	const logout = vi
		.fn<() => Promise<Response>>()
		.mockResolvedValue(new Response(null, { status: 204 }));
	const discovery = vi.fn<() => Promise<Response>>().mockResolvedValue(Response.json([]));
	const user = vi
		.fn<() => Promise<Response>>()
		.mockImplementation(async () =>
			Response.json({ displayName: "Test account", appRole: "USER" }),
		);
	const refresh = vi
		.fn<() => Promise<Response>>()
		.mockImplementation(async () => new Response(null, { status: 401 }));
	vi.stubGlobal("fetch", async (input: RequestInfo | URL, init?: RequestInit) => {
		const request = new Request(input, init);
		const url = new URL(request.url);
		sent.push({ origin: url.origin, path: url.pathname, body: await request.clone().text() });
		if (url.pathname.endsWith("/identity-providers")) {
			return discovery();
		}
		if (url.pathname.endsWith("/auth/client/logout")) {
			return logout();
		}
		if (url.pathname.endsWith("/auth/client/token")) {
			return Response.json(tokens(url.hostname));
		}
		if (url.pathname.endsWith("/auth/client/refresh")) {
			return refresh();
		}
		if (url.pathname.endsWith("/user/consent")) {
			return Response.json({ completed: consent });
		}
		if (url.pathname.endsWith("/user")) {
			return user();
		}
		throw new Error(`Unexpected request: ${url.pathname}`);
	});
	platform.identity.getRedirectURL.mockImplementation(
		(path) => `https://${ID}.chromiumapp.org/${path}`,
	);
	const launch = platform.identity.launchWebAuthFlow.mockImplementation(async ({ url }) =>
		completeSignInURL(url),
	);
	await writeInstance(A);
	const credentials: Credentials = { issuer: A, tokens: tokens("old-a") };
	const { epoch } = await worker.session.snapshot();
	await worker.session.adopt(credentials, epoch);
	return {
		...worker,
		sent,
		logout,
		discovery,
		launch,
		user,
		refresh,
		acceptConsent: () => {
			consent = true;
		},
		ask: async (request: RpcRequest) => worker.handleMessage(request, sender),
	};
}

beforeEach(() => {
	vi.resetModules();
	vi.resetAllMocks();
	platform.localGet.mockImplementation(async (key) => fakeBrowser.storage.local.get(key));
	platform.sessionGet.mockImplementation(async (key) => fakeBrowser.storage.session.get(key));
	platform.sessionSet.mockImplementation(async (items) => fakeBrowser.storage.session.set(items));
	fakeBrowser.reset();
	fakeBrowser.runtime.id = ID;
	platform.permissions.getAll.mockImplementation(async () => ({ origins: [] }));
	platform.permissions.remove.mockImplementation(async () => true);
	platform.scripting.getRegisteredContentScripts.mockImplementation(async () => []);
	platform.tabs.query.mockResolvedValue([]);
	platform.tabs.get.mockResolvedValue({ id: 42, url: "https://github.com/acme/project/pull/1" });
});

afterEach(() => {
	vi.restoreAllMocks();
	vi.unstubAllGlobals();
});

const REPORT_VIEW = "reportView:42";

/** A session read that, for the tab's report view, waits for the test before it answers. */
function holdingReportView(
	started: PromiseWithResolvers<undefined>,
	release: PromiseWithResolvers<undefined>,
) {
	return async (key: Parameters<typeof fakeBrowser.storage.session.get>[0]) => {
		const answer = await fakeBrowser.storage.session.get(key);
		if (key === REPORT_VIEW) {
			started.resolve(undefined);
			await release.promise;
		}
		return answer;
	};
}

/** Session writes where the first write of the tab's report view waits for the test. */
function holdingFirstReportViewWrite(
	started: PromiseWithResolvers<undefined>,
	release: PromiseWithResolvers<undefined>,
) {
	let held = false;
	return async (items: Record<string, unknown>) => {
		if (Object.hasOwn(items, REPORT_VIEW) && !held) {
			held = true;
			started.resolve(undefined);
			await release.promise;
		}
		await fakeBrowser.storage.session.set(items);
	};
}

/** A session write after which, once it kept the report view, the tab shows other work. */
async function navigatingAfterReportViewWrite(items: Record<string, unknown>): Promise<void> {
	await fakeBrowser.storage.session.set(items);
	if (Object.hasOwn(items, REPORT_VIEW)) {
		platform.tabs.get.mockResolvedValue({ id: 42, url: "https://github.com/acme/project/pull/2" });
	}
}

describe("worker read-only inline boundary", () => {
	it("rejects removed actions, settings mutations and caller-selected tabs before any network call", async () => {
		const h = await harness();
		const inline = {
			id: ID,
			url: `chrome-extension://${ID}/inline.html`,
			frameId: 2,
			tab: { id: 42 },
		};
		for (const type of [
			"request-review",
			"cancel-review",
			"retry-result-processing",
			"set-preferred-workspace",
		]) {
			await expect(
				h.handleMessage(
					{ type, tabId: 42, workspaceSlug: "team", workId: "1", jobId: "job" },
					inline,
				),
			).resolves.toMatchObject({ ok: false, error: { code: "invalid" } });
		}
		for (const message of [
			{ type: "sign-out" },
			{ type: "clear-instance" },
			{ type: "configure-instance", origin: B.origin },
		]) {
			await expect(h.handleMessage(message, inline)).resolves.toMatchObject({
				ok: false,
				error: { code: "forbidden" },
			});
		}
		await expect(h.handleMessage({ type: "get-context", tabId: 7 }, inline)).resolves.toMatchObject(
			{ ok: false, error: { code: "invalid" } },
		);
		await expect(h.handleMessage({ type: "get-context" }, sender)).resolves.toMatchObject({
			ok: false,
			error: { code: "forbidden" },
		});
		expect(h.sent).toStrictEqual([]);
		const snapshot = await h.session.snapshot();
		expect(snapshot.credentials?.issuer.origin).toBe(A.origin);
	});
});

describe("worker instance transitions", () => {
	it("keeps the final registered scripts consistent when an old instance read overlaps connection", async () => {
		const h = await harness();
		platform.permissions.getAll.mockResolvedValue({ origins: [`${A.origin}/*`, `${B.origin}/*`] });
		const readStarted = Promise.withResolvers<undefined>();
		const releaseRead = Promise.withResolvers<undefined>();
		platform.localGet.mockImplementationOnce(async (key) => {
			const captured = await fakeBrowser.storage.local.get(key);
			readStarted.resolve(undefined);
			await releaseRead.promise;
			return captured;
		});
		const reconciling = h.reconcile();
		await readStarted.promise;
		const connecting = h.ask({ type: "configure-instance", origin: B.origin });
		await vi.waitFor(() => expect(h.discovery).toHaveBeenCalledOnce());
		// Discovery and the fake Chrome APIs resolve in microtasks; give the competing publication
		// a turn to run while the old storage callback remains explicitly held.
		await setImmediate();
		releaseRead.resolve(undefined);
		await reconciling;
		await expect(connecting).resolves.toMatchObject({ ok: true });
		await expect(readInstance()).resolves.toStrictEqual(B);
		expect(platform.scripting.registerContentScripts.mock.lastCall?.[0][0]?.matches).toStrictEqual([
			`${A.origin}/*`,
		]);
	});

	it("answers state queries while a tab delays configuration reconciliation", async () => {
		const h = await harness();
		const delivery = Promise.withResolvers<undefined>();
		const started = Promise.withResolvers<undefined>();
		platform.tabs.query.mockResolvedValue([{ id: 7 }]);
		platform.tabs.sendMessage.mockImplementation(async () => {
			started.resolve(undefined);
			await delivery.promise;
		});
		const connecting = h.ask({ type: "configure-instance", origin: B.origin });
		await started.promise;
		try {
			await expect(h.ask({ type: "get-state" })).resolves.toMatchObject({
				ok: true,
				data: { instance: { origin: B.origin }, session: { status: "signed-out" } },
			});
		} finally {
			delivery.resolve(undefined);
			await connecting;
		}
	});

	it.each([
		["The user did not approve access.", "cancelled", "Sign-in was cancelled before it finished."],
		[
			"Authorization page could not be loaded.",
			"network",
			"Sign-in could not complete. Try again.",
		],
		[
			"Failed https://issuer.test/callback?code=private",
			"network",
			"Sign-in could not complete. Try again.",
		],
	])("reports the browser sign-in failure accurately: %s", async (message, code, expected) => {
		const h = await harness();
		h.launch.mockRejectedValueOnce(new Error(message));
		await expect(h.ask({ type: "sign-in", registrationId: "github" })).resolves.toMatchObject({
			ok: false,
			error: { code, message: expected },
		});
		expect(h.sent.some((entry) => entry.path.endsWith("/auth/client/token"))).toBe(false);
	});

	it("publishes B before a new sign-in while A's remote sign-out is still pending", async () => {
		const h = await harness();
		const revoke = Promise.withResolvers<Response>();
		h.logout.mockReturnValueOnce(revoke.promise);
		const connected = await h.ask({ type: "configure-instance", origin: B.origin });
		expect(connected).toMatchObject({
			ok: true,
			data: { instance: { origin: B.origin }, session: { status: "signed-out" } },
		});
		await expect(readInstance()).resolves.toStrictEqual(B);
		await expect(h.ask({ type: "sign-in", registrationId: "github" })).resolves.toMatchObject({
			ok: true,
		});
		await expect(h.session.snapshot()).resolves.toMatchObject({ credentials: { issuer: B } });
		expect(
			h.sent
				.filter((entry) => entry.path.endsWith("/auth/client/token"))
				.map((entry) => entry.origin),
		).toStrictEqual([B.origin]);
		expect(h.sent.find((entry) => entry.path.endsWith("/auth/client/logout"))).toMatchObject({
			origin: A.origin,
			body: JSON.stringify({ refreshToken: "refresh-old-a" }),
		});
		revoke.resolve(new Response(null, { status: 204 }));
	});

	it("signs out promptly without forgetting the instance while its issuer is unreachable", async () => {
		const h = await harness();
		const revoke = Promise.withResolvers<Response>();
		h.logout.mockReturnValueOnce(revoke.promise);
		await expect(h.ask({ type: "sign-out" })).resolves.toMatchObject({
			ok: true,
			data: { instance: { origin: A.origin }, session: { status: "signed-out" } },
		});
		await expect(readInstance()).resolves.toStrictEqual(A);
		await expect(h.session.snapshot()).resolves.not.toHaveProperty("credentials");
		revoke.resolve(new Response(null, { status: 204 }));
	});

	it("disconnects promptly and refuses new sign-in while remote sign-out is pending", async () => {
		const h = await harness();
		const revoke = Promise.withResolvers<Response>();
		h.logout.mockReturnValueOnce(revoke.promise);
		await expect(h.ask({ type: "clear-instance" })).resolves.toMatchObject({
			ok: true,
			data: { session: { status: "signed-out" } },
		});
		await expect(readInstance()).resolves.toBeUndefined();
		await expect(h.ask({ type: "sign-in", registrationId: "github" })).resolves.toMatchObject({
			ok: false,
			error: { code: "not-configured" },
		});
		await expect(h.session.snapshot()).resolves.not.toHaveProperty("credentials");
		expect(h.launch).not.toHaveBeenCalled();
		revoke.resolve(new Response(null, { status: 204 }));
	});

	it.each([
		["configure-instance", { type: "configure-instance", origin: B.origin } as const, B.origin],
		["clear-instance", { type: "clear-instance" } as const, undefined],
	])(
		"finishes %s without waiting for OAuth and revokes the late A family at A",
		async (_type, change, instanceAfter) => {
			const h = await harness();
			const opened = Promise.withResolvers<string>();
			const finish = Promise.withResolvers<string>();
			h.launch.mockImplementationOnce(async ({ url }) => {
				opened.resolve(url);
				return finish.promise;
			});
			const signingIn = h.ask({ type: "sign-in", registrationId: "github" });
			const startedUrl = await opened.promise;
			await expect(h.ask(change)).resolves.toMatchObject({ ok: true });
			finish.resolve(completeSignInURL(startedUrl));
			await expect(signingIn).resolves.toMatchObject({ ok: false, error: { code: "stale" } });
			await expect(h.session.snapshot()).resolves.not.toHaveProperty("credentials");
			const configured = await readInstance();
			expect(configured?.origin).toBe(instanceAfter);
			expect(h.sent.filter((entry) => entry.body.includes("refresh-a.test"))).toStrictEqual([
				{
					origin: A.origin,
					path: "/api/auth/client/logout",
					body: JSON.stringify({ refreshToken: "refresh-a.test" }),
				},
			]);
		},
	);

	it("captures instance and epoch together when disconnect overlaps the instance read", async () => {
		const h = await harness();
		const read = Promise.withResolvers<undefined>();
		const releaseRead = Promise.withResolvers<undefined>();
		const originalGet = fakeBrowser.storage.local.get.bind(fakeBrowser.storage.local);
		platform.localGet.mockImplementationOnce(async (key) => {
			const result = await originalGet(key);
			read.resolve(undefined);
			await releaseRead.promise;
			return result;
		});
		const opened = Promise.withResolvers<string>();
		const finish = Promise.withResolvers<string>();
		h.launch.mockImplementationOnce(async ({ url }) => {
			opened.resolve(url);
			return finish.promise;
		});
		const signingIn = h.ask({ type: "sign-in", registrationId: "github" });
		await read.promise;
		const disconnect = h.ask({ type: "clear-instance" });
		releaseRead.resolve(undefined);
		const url = await opened.promise;
		await expect(disconnect).resolves.toMatchObject({ ok: true });
		finish.resolve(completeSignInURL(url));
		await expect(signingIn).resolves.toMatchObject({ ok: false, error: { code: "stale" } });
		await expect(h.session.snapshot()).resolves.not.toHaveProperty("credentials");
		await expect(readInstance()).resolves.toBeUndefined();
	});

	it("does not restore an instance when its discovery finishes after a newer disconnect", async () => {
		const h = await harness();
		const started = Promise.withResolvers<undefined>();
		const discovery = Promise.withResolvers<Response>();
		h.discovery.mockImplementationOnce(async () => {
			started.resolve(undefined);
			return discovery.promise;
		});
		const connect = h.ask({ type: "configure-instance", origin: B.origin });
		await started.promise;
		await expect(h.ask({ type: "clear-instance" })).resolves.toMatchObject({ ok: true });
		discovery.resolve(Response.json([]));
		await expect(connect).resolves.toMatchObject({ ok: false, error: { code: "stale" } });
		await expect(readInstance()).resolves.toBeUndefined();
		await expect(h.session.snapshot()).resolves.not.toHaveProperty("credentials");
	});

	it("rechecks consent after it is accepted in the app without changing the session generation", async () => {
		const h = await harness();
		const generation = await h.session.generation();
		await expect(h.ask({ type: "get-state" })).resolves.toMatchObject({
			ok: true,
			data: { session: { status: "consent-required" } },
		});
		h.acceptConsent();
		await expect(h.ask({ type: "get-state" })).resolves.toMatchObject({
			ok: true,
			data: { session: { status: "signed-in" } },
		});
		await expect(h.session.generation()).resolves.toBe(generation);
	});

	it("drops cached identity and invalidates views after the server revokes the session", async () => {
		const h = await harness();
		h.acceptConsent();
		await expect(h.ask({ type: "get-state" })).resolves.toMatchObject({
			ok: true,
			data: { session: { status: "signed-in" } },
		});
		const generation = await h.session.generation();
		const events = vi.spyOn(fakeBrowser.runtime, "sendMessage");
		// Revocation and instance-admin demotion both make existing access and refresh tokens fail.
		h.user.mockImplementation(async () => new Response(null, { status: 401 }));
		await expect(h.ask({ type: "get-state" })).resolves.toMatchObject({
			ok: true,
			data: { session: { status: "signed-out" } },
		});
		expect(h.refresh).toHaveBeenCalledOnce();
		await expect(h.session.snapshot()).resolves.not.toHaveProperty("credentials");
		expect(events).toHaveBeenCalledWith({ type: "state-changed", generation: generation + 1 });
	});

	it("reads changed account authority on the next state request", async () => {
		const h = await harness();
		h.acceptConsent();
		await expect(h.ask({ type: "get-state" })).resolves.toMatchObject({
			ok: true,
			data: { session: { status: "signed-in", account: { instanceAdmin: false } } },
		});
		h.user.mockImplementation(async () =>
			Response.json({ displayName: "Test account", appRole: "APP_ADMIN" }),
		);
		await expect(h.ask({ type: "get-state" })).resolves.toMatchObject({
			ok: true,
			data: { session: { status: "signed-in", account: { instanceAdmin: true } } },
		});
		expect(h.user).toHaveBeenCalledTimes(2);
	});

	it("ends an expired session on the next state request without sending an expired credential", async () => {
		const h = await harness();
		h.acceptConsent();
		await expect(h.ask({ type: "get-state" })).resolves.toMatchObject({
			ok: true,
			data: { session: { status: "signed-in" } },
		});
		const generation = await h.session.generation();
		const events = vi.spyOn(fakeBrowser.runtime, "sendMessage");
		vi.spyOn(Date, "now").mockReturnValue(Date.parse("2099-01-02T00:00:01Z"));
		await expect(h.ask({ type: "get-state" })).resolves.toMatchObject({
			ok: true,
			data: { session: { status: "signed-out" } },
		});
		expect(h.user).toHaveBeenCalledOnce();
		expect(h.refresh).not.toHaveBeenCalled();
		await expect(h.session.snapshot()).resolves.not.toHaveProperty("credentials");
		expect(events).toHaveBeenCalledWith({ type: "state-changed", generation: generation + 1 });
	});

	it("coalesces simultaneous account reads but revalidates after their answer", async () => {
		const h = await harness();
		h.acceptConsent();
		const answer = Promise.withResolvers<Response>();
		h.user.mockReturnValueOnce(answer.promise);
		const first = h.ask({ type: "get-state" });
		const second = h.ask({ type: "get-state" });
		await vi.waitFor(() => expect(h.user).toHaveBeenCalledOnce());
		answer.resolve(Response.json({ displayName: "Test account", appRole: "USER" }));
		await expect(first).resolves.toMatchObject({ ok: true });
		await expect(second).resolves.toMatchObject({ ok: true });
		expect(h.user).toHaveBeenCalledOnce();
		await expect(h.ask({ type: "get-state" })).resolves.toMatchObject({ ok: true });
		expect(h.user).toHaveBeenCalledTimes(2);
	});
});

describe("report preferences belong to one session and work", () => {
	const inline = {
		id: ID,
		url: `chrome-extension://${ID}/inline.html`,
		frameId: 2,
		tab: { id: 42 },
	};

	it("retains a same-session choice but starts collapsed after account or work changes", async () => {
		const h = await harness();
		const generation = await h.session.generation();
		await expect(
			h.handleMessage(
				{ type: "set-report-view", generation, expanded: true, workspaceSlug: "team" },
				inline,
			),
		).resolves.toMatchObject({ ok: true });
		await expect(h.handleMessage({ type: "get-report-view" }, inline)).resolves.toMatchObject({
			data: { expanded: true, workspaceSlug: "team" },
		});
		platform.tabs.get.mockResolvedValue({ id: 42, url: "https://github.com/acme/project/pull/2" });
		await expect(h.handleMessage({ type: "get-report-view" }, inline)).resolves.toMatchObject({
			data: { expanded: false },
		});
		platform.tabs.get.mockResolvedValue({ id: 42, url: "https://github.com/acme/project/pull/1" });
		await h.session.detach();
		const { epoch } = await h.session.snapshot();
		await h.session.adopt({ issuer: B, tokens: tokens("account-b") }, epoch);
		await expect(h.handleMessage({ type: "get-report-view" }, inline)).resolves.toMatchObject({
			data: { expanded: false },
		});
		await expect(
			h.handleMessage(
				{ type: "set-report-view", generation, expanded: true, workspaceSlug: "team" },
				inline,
			),
		).resolves.toMatchObject({ ok: false, error: { code: "stale" } });
		await expect(h.handleMessage({ type: "get-report-view" }, inline)).resolves.toMatchObject({
			data: { expanded: false },
		});
	});

	it("drops a read whose account changes while session storage answers", async () => {
		const h = await harness();
		const generation = await h.session.generation();
		await h.handleMessage({ type: "set-report-view", generation, expanded: true }, inline);
		const started = Promise.withResolvers<undefined>();
		const release = Promise.withResolvers<undefined>();
		platform.sessionGet.mockImplementation(holdingReportView(started, release));
		const pending = h.handleMessage({ type: "get-report-view" }, inline);
		await started.promise;
		await h.session.detach();
		release.resolve(undefined);
		await expect(pending).resolves.toMatchObject({ ok: false, error: { code: "stale" } });
	});

	it("an old write cannot restore expansion or overwrite the new account's choice", async () => {
		const h = await harness();
		const generation = await h.session.generation();
		const started = Promise.withResolvers<undefined>();
		const release = Promise.withResolvers<undefined>();
		platform.sessionSet.mockImplementation(holdingFirstReportViewWrite(started, release));
		const old = h.handleMessage(
			{ type: "set-report-view", generation, expanded: true, workspaceSlug: "team" },
			inline,
		);
		await started.promise;
		await h.session.detach();
		const current = await h.session.generation();
		const newer = h.handleMessage(
			{
				type: "set-report-view",
				generation: current,
				expanded: false,
				workspaceSlug: "other-team",
			},
			inline,
		);
		release.resolve(undefined);
		await expect(old).resolves.toMatchObject({ ok: false, error: { code: "stale" } });
		await expect(newer).resolves.toMatchObject({ ok: true });
		await expect(h.handleMessage({ type: "get-report-view" }, inline)).resolves.toMatchObject({
			data: { expanded: false, workspaceSlug: "other-team" },
		});
		await expect(readReportView(42)).resolves.toMatchObject({
			generation: current,
			expanded: false,
			workspaceSlug: "other-team",
		});
	});

	it("discards a preference if the provider navigates while its write is pending", async () => {
		const h = await harness();
		const generation = await h.session.generation();
		platform.sessionSet.mockImplementation(navigatingAfterReportViewWrite);
		await expect(
			h.handleMessage({ type: "set-report-view", generation, expanded: true }, inline),
		).resolves.toMatchObject({ ok: false, error: { code: "stale" } });
		await expect(readReportView(42)).resolves.toBeUndefined();
	});
});
