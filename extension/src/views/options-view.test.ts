// @vitest-environment jsdom
import { QueryClientProvider } from "@tanstack/react-query";
import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, expect, it, vi } from "vitest";

import type { AppState, RpcEvent, RpcRequest, SignInOptions, SiteAccessEntry } from "~/shared/rpc";
import { required } from "~/testing/required";
import { createQueryClient } from "~/ui/worker-state";
import { OptionsView } from "~/views/OptionsView";

const platform = vi.hoisted(() => ({
	sendMessage: vi.fn<(request: RpcRequest) => Promise<unknown>>(),
	addListener:
		vi.fn<(listener: (event: RpcEvent, sender: { id: string; url: string }) => void) => void>(),
	request: vi.fn<(permissions: { origins: string[] }) => Promise<boolean>>(),
	remove: vi.fn<(permissions: { origins: string[] }) => Promise<boolean>>(),
}));
vi.mock("@wxt-dev/browser", () => ({
	browser: {
		runtime: {
			id: "test-extension",
			getURL: (path: string) => `chrome-extension://test-extension${path}`,
			getManifest: () => ({ version: "0.80.0" }),
			sendMessage: platform.sendMessage,
			onMessage: { addListener: platform.addListener },
		},
		permissions: { request: platform.request, remove: platform.remove },
	},
}));

const SIGNED_OUT: AppState = {
	generation: 0,
	developmentBuild: false,
	session: { status: "signed-out" },
};
const HOSTED = {
	origin: "https://hephaestus.build",
	host: "hephaestus.build",
	webAppOrigin: "https://hephaestus.build",
};
const SIGN_IN_OPTIONS: SignInOptions = {
	options: [{ registrationId: "github", displayName: "GitHub", providerType: "GITHUB" }],
	devSignIn: false,
	registered: true,
	extensionId: "test-extension",
};

type Handler = (request: RpcRequest) => unknown;

let container: HTMLDivElement;
let root: Root;

function answer(handler: Handler): void {
	platform.sendMessage.mockImplementation(async (request) => {
		const data = await handler(request);
		if (data instanceof Error) {
			const code = "code" in data && typeof data.code === "string" ? data.code : "server";
			return { ok: false, generation: 0, error: { code, message: data.message } };
		}
		return { ok: true, generation: 0, data };
	});
}

/** Answers each command from its route, anything else with `otherwise`, or fails as unexpected. */
function routed(
	routes: Partial<Record<RpcRequest["type"], () => unknown>>,
	otherwise?: () => unknown,
): Handler {
	return (request) => {
		const route = routes[request.type] ?? otherwise;
		if (route === undefined) {
			throw new Error(`Unexpected ${request.type}`);
		}
		return route();
	};
}

/** Answers as the worker does, stamped with the generation a state carries (1 for anything else). */
function answerStamped(handler: Handler): void {
	platform.sendMessage.mockImplementation(async (request) => {
		const data = await handler(request);
		const generation =
			typeof data === "object" && data !== null && "generation" in data
				? Number(data.generation)
				: 1;
		return { ok: true, generation, data };
	});
}

/** The first answer at once, and every later one once `later` settles. */
function firstThen<T>(first: T, later: Promise<T>): () => Promise<T> {
	let calls = 0;
	return async () => {
		calls += 1;
		return calls === 1 ? first : later;
	};
}

async function render(): Promise<void> {
	await act(async () => {
		root.render(
			createElement(
				QueryClientProvider,
				{ client: createQueryClient() },
				createElement(OptionsView),
			),
		);
	});
}

async function until(assertion: () => void): Promise<void> {
	await act(async () => {
		await vi.waitFor(assertion);
	});
}

/** A button's visible words: icons carry a hidden `<title>` that is not part of its name. */
function visibleName(element: Element): string {
	const copy = element.cloneNode(true);
	if (copy instanceof Element) {
		for (const icon of copy.querySelectorAll("svg")) {
			icon.remove();
		}
	}
	return (copy.textContent ?? "").trim();
}

function button(name: string): HTMLButtonElement {
	const match = [...container.querySelectorAll("button")].find(
		(element) => visibleName(element) === name || element.getAttribute("aria-label") === name,
	);
	if (match === undefined) {
		throw new Error(`No button "${name}" in: ${container.textContent}`);
	}
	return match;
}

async function click(element: HTMLElement): Promise<void> {
	await act(async () => {
		element.click();
	});
}

async function fill(label: string, value: string): Promise<void> {
	const labelElement = [...container.querySelectorAll("label")].find((element) =>
		element.textContent.startsWith(label),
	);
	const input = container.querySelector<HTMLInputElement>(
		`#${CSS.escape(labelElement?.htmlFor ?? "")}`,
	);
	if (input === null) {
		throw new Error(`No field "${label}"`);
	}
	await act(async () => {
		Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set?.call(input, value);
		input.dispatchEvent(new Event("input", { bubbles: true }));
	});
}

function requests(kind: RpcRequest["type"]): RpcRequest[] {
	return platform.sendMessage.mock.calls
		.map(([request]) => request)
		.filter((request) => request.type === kind);
}

beforeEach(() => {
	vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true);
	platform.sendMessage.mockReset();
	platform.request.mockReset();
	platform.remove.mockReset();
	container = document.createElement("div");
	document.body.append(container);
	root = createRoot(container);
});

afterEach(async () => {
	await act(async () => root.unmount());
	container.remove();
	vi.unstubAllGlobals();
});

it("offers hosted Hephaestus first and connects to it after Chrome grants that one address", async () => {
	let state = SIGNED_OUT;
	answer(
		routed(
			{
				"get-state": () => state,
				"configure-instance": () => {
					state = { ...SIGNED_OUT, instance: HOSTED };
					return state;
				},
			},
			() => SIGN_IN_OPTIONS,
		),
	);
	platform.request.mockResolvedValue(true);
	await render();
	await until(() => expect(container.textContent).toContain("Continue with Hephaestus"));
	expect(container.textContent).toContain("hephaestus.build");
	// The address form is there for a self-hosted instance, but folded away.
	expect(container.querySelector("details")?.open).toBe(false);

	await click(button("Continue with Hephaestus"));

	expect(platform.request).toHaveBeenCalledWith({ origins: ["https://hephaestus.build/*"] });
	await until(() =>
		expect(requests("configure-instance")).toStrictEqual([
			{ type: "configure-instance", origin: "https://hephaestus.build", webAppOrigin: undefined },
		]),
	);
});

it("changes nothing when Chrome does not grant access to the hosted address", async () => {
	answer(() => SIGNED_OUT);
	platform.request.mockResolvedValue(false);
	await render();
	await until(() => expect(container.textContent).toContain("Continue with Hephaestus"));

	await click(button("Continue with Hephaestus"));

	await until(() =>
		expect(container.querySelector('[role="alert"]')?.textContent).toContain(
			"Chrome did not allow the extension to reach hephaestus.build",
		),
	);
	expect(requests("configure-instance")).toStrictEqual([]);
	expect(button("Continue with Hephaestus").disabled).toBe(false);
});

it("connects to a self-hosted address typed into the disclosure instead", async () => {
	answer(() => SIGNED_OUT);
	platform.request.mockResolvedValue(true);
	await render();
	await until(() => expect(container.textContent).toContain("Use a self-hosted instance"));
	await act(async () => {
		required(container.querySelector("details"), "the self-hosted disclosure").open = true;
	});
	await fill("Hephaestus address", "heph.example.test");
	await click(button("Connect"));

	expect(platform.request).toHaveBeenCalledWith({ origins: ["https://heph.example.test/*"] });
	await until(() =>
		expect(requests("configure-instance")).toStrictEqual([
			{ type: "configure-instance", origin: "https://heph.example.test", webAppOrigin: undefined },
		]),
	);
});

it("says a closed sign-in window changed nothing, instead of reporting an error", async () => {
	answer(
		routed({
			"get-state": () => ({ ...SIGNED_OUT, instance: HOSTED }),
			"list-sign-in-options": () => SIGN_IN_OPTIONS,
			"sign-in": () =>
				Object.assign(new Error("Sign-in was cancelled before it finished."), {
					code: "cancelled",
				}),
		}),
	);
	await render();
	await until(() => expect(container.textContent).toContain("Sign in with GitHub"));

	await click(button("Sign in with GitHub"));

	await until(() =>
		expect(container.querySelector('[role="status"]')?.textContent).toContain(
			"Sign-in was cancelled and nothing changed",
		),
	);
	expect(container.querySelector('[role="alert"]')).toBeNull();
	expect(button("Sign in with GitHub").disabled).toBe(false);
});

it("disconnects only after the reader confirms changing the instance", async () => {
	answer(
		routed(
			{
				"get-state": () => ({ ...SIGNED_OUT, instance: HOSTED }),
				"clear-instance": () => SIGNED_OUT,
			},
			() => SIGN_IN_OPTIONS,
		),
	);
	await render();
	await until(() => expect(container.textContent).toContain("Hosted Hephaestus"));

	await click(button("Change instance…"));
	expect(requests("clear-instance")).toStrictEqual([]);
	await click(button("Keep it"));
	expect(requests("clear-instance")).toStrictEqual([]);

	await click(button("Change instance…"));
	await click(button("Disconnect"));

	await until(() => expect(requests("clear-instance")).toHaveLength(1));
});

it("sends a signed-in reader without a connected workspace to the web app, not to a dead end", async () => {
	const signedIn: AppState = {
		generation: 0,
		developmentBuild: false,
		instance: HOSTED,
		session: {
			status: "signed-in",
			account: { displayName: "Ada Lovelace", username: "ada", instanceAdmin: false },
			sessionExpiresAt: "2026-10-03T09:00:00Z",
		},
	};
	const noSites: SiteAccessEntry[] = [];
	answer(routed({ "list-site-access": () => noSites }, () => signedIn));
	await render();
	await until(() =>
		expect(container.textContent).toContain("No GitHub or GitLab site to allow yet"),
	);

	const link = [...container.querySelectorAll("a")].find((anchor) =>
		anchor.textContent.includes("Open Hephaestus"),
	);
	expect(link?.getAttribute("href")).toBe("https://hephaestus.build");
	expect(link?.getAttribute("target")).toBe("_blank");
	// Setting up is not finished until a site is allowed.
	expect(container.querySelector('[aria-current="step"]')?.textContent).toContain("Allow a site");
});

it("renders the published instance when a state event resets the options query during connection", async () => {
	const initial: AppState = {
		generation: 0,
		developmentBuild: true,
		session: { status: "signed-out" },
	};
	const published: AppState = {
		...initial,
		generation: 1,
		instance: {
			origin: "http://localhost:18480",
			host: "localhost:18480",
			webAppOrigin: "http://localhost:14280",
		},
	};
	const state = Promise.withResolvers<AppState>();
	answerStamped(
		routed({
			"get-state": firstThen(initial, state.promise),
			"list-sign-in-options": () => ({
				options: [],
				devSignIn: true,
				registered: true,
				extensionId: "test-extension",
			}),
		}),
	);
	await render();
	await until(() => expect(container.textContent).toContain("Continue with Hephaestus"));
	const listener = platform.addListener.mock.calls[0]?.[0];
	expect(listener).toBeDefined();
	await act(async () => {
		listener?.(
			{ type: "state-changed", generation: 1 },
			{ id: "test-extension", url: "chrome-extension://test-extension/background.js" },
		);
	});
	expect(container.textContent).not.toContain("Continue with Hephaestus");
	await act(async () => {
		state.resolve(published);
	});
	await until(() => expect(container.textContent).toContain("Sign in to localhost:18480"));
	expect(requests("get-state")).toHaveLength(2);
});
