import { beforeEach, describe, expect, it, vi } from "vitest";

import type { WorkspaceOnboarding } from "~/api/types.gen";
import type { MentorTurn } from "~/background/api";
import {
	attestPanel,
	type MentorApi,
	type MentorDependencies,
	mentorPanel,
	openPanel,
	prepareTurn,
	relayTurn,
} from "~/background/mentor";
import type { MentorBinding } from "~/background/storage";
import { READY_ON_GITHUB } from "~/components/report/fixtures";
import { type TurnEvent, turnRequestSchema } from "~/shared/mentor";
import type { ReviewContext } from "~/shared/review-context";

const ID = "ijkajblcbajjpjbknfgdiiiljipafiko";

const chrome = vi.hoisted(() => ({
	setOptions: vi.fn<(options: object) => Promise<void>>(),
	open: vi.fn<(options: object) => Promise<void>>(),
	getOptions: vi.fn<(options: object) => Promise<{ enabled?: boolean; path?: string }>>(),
}));

vi.mock("@wxt-dev/browser", () => ({
	browser: {
		sidePanel: chrome,
		runtime: {
			getURL: (path: string) => `chrome-extension://ijkajblcbajjpjbknfgdiiiljipafiko${path}`,
		},
	},
}));

const PAGE = READY_ON_GITHUB.pageUrl;
const OTHER = "https://github.com/HephaestusTest/lifecycle-validation/pull/41";
const THREAD = "5b0f7c2e-3a1d-4e8b-9c6f-2d4e6a8b0c1e";
const NEW_THREAD = "6c1a8d3f-4b2e-4f9c-8d70-3e5f7a9b1c2d";
const MESSAGE = "0d9a3f1e-1b2c-4d5e-8f60-718293a4b5c6";
const READY_AI: WorkspaceOnboarding = {
	enabled: false,
	links: [],
	needsSetup: false,
	workspaceName: "Test",
	aiChoice: "CLOUD",
	aiChoiceRequired: false,
	aiOptions: [{ choice: "CLOUD", mentorReady: true, models: [], practiceReviewsReady: true }],
};

function harness(options: {
	context?: ReviewContext;
	binding?: MentorBinding;
	onboarding?: WorkspaceOnboarding;
	tabUrls?: string[];
}) {
	let { binding } = options;
	const tabUrls = [...(options.tabUrls ?? [])];
	const api: MentorApi = {
		generation: 3,
		assertCurrent: vi.fn(async () => undefined),
		onboarding: vi.fn(async () => options.onboarding ?? READY_AI),
		thread: vi.fn(async () => undefined),
		mentorTurn: vi.fn(),
	};
	const writes: (MentorBinding | undefined)[] = [];
	const deps: MentorDependencies = {
		context: async () => options.context ?? READY_ON_GITHUB,
		environment: async () => ({
			api,
			webAppOrigin: "https://hephaestus.build",
			// The tab's address as Chrome reads it each time; the last entry repeats.
			tabUrl: async () => (tabUrls.length > 1 ? tabUrls.shift() : (tabUrls[0] ?? PAGE)),
		}),
		readBinding: async () => binding,
		writeBinding: async (_tab, next) => {
			writes.push(next);
			binding = next;
		},
	};
	return { deps, api, writes };
}

function turn(text: string, id = NEW_THREAD) {
	return turnRequestSchema.parse({
		type: "start",
		body: {
			id,
			message: { id: MESSAGE, role: "user", parts: [{ type: "text", text }] },
			trigger: "submit-message",
		},
	});
}

const REFERENCE =
	"About pull request #1 in HephaestusTest/lifecycle-validation: https://github.com/HephaestusTest/lifecycle-validation/pull/1";

function broken() {
	return new ReadableStream<Uint8Array>({
		pull(controller) {
			controller.error(new Error("reset"));
		},
	});
}

beforeEach(() => {
	vi.clearAllMocks();
	chrome.setOptions.mockResolvedValue();
	chrome.open.mockResolvedValue();
});

describe("opening the panel", () => {
	it("configures and opens the tab's panel in the same turn as the press", () => {
		void openPanel(42);
		// Both calls are made before anything is awaited; Chrome needs the press to still be handled.
		expect(chrome.setOptions).toHaveBeenCalledWith({
			tabId: 42,
			path: "mentor.html?tab=42",
			enabled: true,
		});
		expect(chrome.open).toHaveBeenCalledWith({ tabId: 42 });
	});

	it("attests a panel only at the address the worker set for that very tab", async () => {
		chrome.getOptions.mockResolvedValue({ enabled: true, path: "mentor.html?tab=42" });
		await expect(attestPanel(42)).resolves.toBeUndefined();
		chrome.getOptions.mockResolvedValue({
			enabled: true,
			path: `chrome-extension://${ID}/mentor.html?tab=42`,
		});
		await expect(attestPanel(42)).resolves.toBeUndefined();
		for (const options of [
			{ enabled: true, path: "mentor.html?tab=7" },
			{ enabled: false, path: "mentor.html?tab=42" },
			{ enabled: true },
			{ enabled: true, path: "options.html" },
		]) {
			chrome.getOptions.mockResolvedValue(options);
			await expect(attestPanel(42)).rejects.toMatchObject({ code: "forbidden" });
		}
	});
});

describe("mentorPanel", () => {
	it("names the work from its address, with the reference the first message will carry", async () => {
		const { deps } = harness({});
		await expect(mentorPanel(deps, 42)).resolves.toMatchObject({
			status: "ready",
			workspace: READY_ON_GITHUB.workspace,
			work: { noun: "pull request", label: "#1", canonicalUrl: PAGE },
			reference: REFERENCE,
			onboardingUrl: `https://hephaestus.build/w/${READY_ON_GITHUB.workspace.slug}/onboarding`,
		});
	});

	it("carries the server's reason Heph will not answer, as the web app reads it", async () => {
		const { deps } = harness({
			onboarding: { ...READY_AI, aiChoice: "NO_AI" },
		});
		await expect(mentorPanel(deps, 42)).resolves.toMatchObject({ notice: { reason: "no-ai" } });
	});

	it("holds the tab's conversation only for its work, generation and workspace", async () => {
		const binding = {
			generation: 3,
			workspaceSlug: READY_ON_GITHUB.workspace.slug,
			workUrl: PAGE,
			threadId: THREAD,
		};
		await expect(mentorPanel(harness({ binding }).deps, 42)).resolves.toMatchObject({
			threadId: THREAD,
			threadUrl: `https://hephaestus.build/w/${READY_ON_GITHUB.workspace.slug}/mentor/${THREAD}`,
			heldAbout: undefined,
		});
		await expect(
			mentorPanel(harness({ binding: { ...binding, workUrl: OTHER } }).deps, 42),
		).resolves.toMatchObject({
			threadId: undefined,
			heldAbout: "HephaestusTest/lifecycle-validation #41",
		});
		for (const stale of [
			{ ...binding, generation: 2 },
			{ ...binding, workspaceSlug: "other" },
		]) {
			await expect(mentorPanel(harness({ binding: stale }).deps, 42)).resolves.toMatchObject({
				threadId: undefined,
				heldAbout: undefined,
			});
		}
	});

	it("answers every state that is not a conversation as a state", async () => {
		const { deps } = harness({ context: { status: "signed-out", instanceHost: "h.test" } });
		await expect(mentorPanel(deps, 42)).resolves.toStrictEqual({
			status: "signed-out",
			instanceHost: "h.test",
		});
	});

	it("refuses an answer once the tab has moved on", async () => {
		const { deps } = harness({ tabUrls: [OTHER] });
		await expect(mentorPanel(deps, 42)).rejects.toMatchObject({ code: "stale" });
	});
});

describe("prepareTurn", () => {
	it.each([
		["No AI", { ...READY_AI, aiChoice: "NO_AI" as const }],
		["no ready model", { ...READY_AI, aiOptions: [] }],
		["a required choice", { ...READY_AI, aiChoice: undefined, aiChoiceRequired: true }],
		["an unavailable choice", { ...READY_AI, aiChoice: "IN_HOUSE_ONLY" as const }],
	])("does not send or bind a conversation for %s", async (_label, onboarding) => {
		const { deps, writes, api } = harness({ onboarding });
		await expect(prepareTurn(deps, 42, turn(`${REFERENCE}\n\nWhy?`))).rejects.toMatchObject({
			code: "forbidden",
		});
		expect(writes).toStrictEqual([]);
		expect(api.mentorTurn).not.toHaveBeenCalled();
	});

	it("starts a conversation only with the reference to the work the tab shows, and binds the tab first", async () => {
		const { deps, writes } = harness({});
		const prepared = await prepareTurn(deps, 42, turn(`${REFERENCE}\n\nWhy did it fail?`));
		expect(writes).toStrictEqual([
			{
				generation: 3,
				workspaceSlug: READY_ON_GITHUB.workspace.slug,
				workUrl: PAGE,
				threadId: NEW_THREAD,
			},
		]);
		// Only ids and the work's address are kept; never a word of the conversation.
		expect(JSON.stringify(writes)).not.toContain("Why did it fail");
		expect(prepared.body).toStrictEqual({
			id: NEW_THREAD,
			message: {
				id: MESSAGE,
				role: "user",
				parts: [{ type: "text", text: `${REFERENCE}\n\nWhy did it fail?` }],
			},
			trigger: "submit-message",
		});
	});

	it.each([
		["no reference", "Why did it fail?"],
		[
			"another work's reference",
			`About pull request #41 in HephaestusTest/lifecycle-validation: ${OTHER}\n\nWhy?`,
		],
		["the reference alone", `${REFERENCE}\n\n  `],
	])("refuses a new conversation with %s, and binds nothing", async (_label, text) => {
		const { deps, writes, api } = harness({});
		await expect(prepareTurn(deps, 42, turn(text))).rejects.toMatchObject({ code: "stale" });
		expect(writes).toStrictEqual([]);
		expect(api.mentorTurn).not.toHaveBeenCalled();
	});

	it("continues the conversation the tab holds without repeating the reference", async () => {
		const binding = {
			generation: 3,
			workspaceSlug: READY_ON_GITHUB.workspace.slug,
			workUrl: PAGE,
			threadId: THREAD,
		};
		const { deps, writes } = harness({ binding });
		await expect(prepareTurn(deps, 42, turn("And the tests?", THREAD))).resolves.toMatchObject({
			workspaceSlug: READY_ON_GITHUB.workspace.slug,
		});
		expect(writes).toStrictEqual([]);
	});

	it("refuses to continue a conversation about work the tab has left", async () => {
		const binding = {
			generation: 3,
			workspaceSlug: READY_ON_GITHUB.workspace.slug,
			workUrl: OTHER,
			threadId: THREAD,
		};
		const { deps } = harness({ binding });
		const pending = prepareTurn(deps, 42, turn("And?", THREAD));
		await expect(pending).rejects.toMatchObject({ code: "stale" });
		await expect(pending).rejects.toThrow("HephaestusTest/lifecycle-validation #41");
	});

	it("refuses a turn when the tab navigates while it is being prepared, and binds nothing", async () => {
		const { deps, writes } = harness({ tabUrls: [PAGE, OTHER] });
		await expect(prepareTurn(deps, 42, turn(`${REFERENCE}\n\nWhy?`))).rejects.toMatchObject({
			code: "stale",
		});
		expect(writes).toStrictEqual([]);
	});
});

function streamOf(...chunks: string[]): ReadableStream<Uint8Array> {
	const encoder = new TextEncoder();
	return new ReadableStream({
		start(controller) {
			for (const chunk of chunks) {
				controller.enqueue(encoder.encode(chunk));
			}
			controller.close();
		},
	});
}

describe("relayTurn", () => {
	it("relays the stream's bytes as they arrive, keep-alive comments included, then its end", async () => {
		const events: TurnEvent[] = [];
		const accepted: MentorTurn = {
			status: 200,
			contentType: "text/event-stream",
			stream: streamOf('data: {"type":"start"}\n\n', ": ping\n\n", "data: [DONE]\n\n"),
			error: undefined,
		};
		await relayTurn(
			accepted,
			(event) => {
				events.push(event);
			},
			new AbortController().signal,
		);
		expect(events).toStrictEqual([
			{ type: "response", status: 200, contentType: "text/event-stream" },
			{ type: "chunk", text: 'data: {"type":"start"}\n\n' },
			{ type: "chunk", text: ": ping\n\n" },
			{ type: "chunk", text: "data: [DONE]\n\n" },
			{ type: "end" },
		]);
	});

	it("relays a refusal as its reason, never as a stream", async () => {
		const events: TurnEvent[] = [];
		await relayTurn(
			{ status: 428, contentType: "application/problem+json", stream: null, error: {} },
			(event) => {
				events.push(event);
			},
			new AbortController().signal,
		);
		expect(events).toHaveLength(3);
		expect(events[0]).toStrictEqual({ type: "response", status: 428, contentType: "text/plain" });
		expect(events.find((event) => event.type === "chunk")?.text).toContain("current notice");
		expect(events[2]).toStrictEqual({ type: "end" });
	});

	it("says the reply stopped when the stream breaks, but not when the reader stopped it", async () => {
		const lost: TurnEvent[] = [];
		await relayTurn(
			{ status: 200, contentType: "text/event-stream", stream: broken(), error: undefined },
			(event) => {
				lost.push(event);
			},
			new AbortController().signal,
		);
		expect(lost.at(-1)).toMatchObject({ type: "failed" });
		const stopped: TurnEvent[] = [];
		const controller = new AbortController();
		controller.abort();
		await relayTurn(
			{ status: 200, contentType: "text/event-stream", stream: broken(), error: undefined },
			(event) => {
				stopped.push(event);
			},
			controller.signal,
		);
		expect(stopped.some((event) => event.type === "failed")).toBe(false);
	});
});
