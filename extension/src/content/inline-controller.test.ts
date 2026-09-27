// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ContentScriptContext } from "wxt/utils/content-script-context";
import type {
	ShadowRootContentScriptUi,
	ShadowRootContentScriptUiOptions,
} from "wxt/utils/content-script-ui/shadow-root";

import { FRAME_READY_BUDGET_MS, InlineController } from "~/content/inline-controller";
import { FAILED_TEXT, LOADING_TEXT, type ReportFrame } from "~/content/report-host";
import provider from "~/entrypoints/provider.content";
import { required } from "~/testing/required";

type ReportUi = ShadowRootContentScriptUi<ReportFrame>;

/** Each call to `createShadowRootUi` waits until the test builds it, as the real one awaits CSS. */
const builds: { options: ShadowRootContentScriptUiOptions<ReportFrame>; build: () => void }[] = [];

vi.mock("@wxt-dev/browser", () => ({
	browser: {
		runtime: {
			id: "ijkajblcbajjpjbknfgdiiiljipafiko",
			getURL: (path: string) => `chrome-extension://ijkajblcbajjpjbknfgdiiiljipafiko${path}`,
			onMessage: { addListener: () => undefined, removeListener: () => undefined },
		},
	},
}));

vi.mock("wxt/utils/content-script-ui/shadow-root", () => ({
	createShadowRootUi: async (
		_ctx: unknown,
		options: ShadowRootContentScriptUiOptions<ReportFrame>,
	) => {
		const built = Promise.withResolvers<ReportUi>();
		builds.push({
			options,
			build: () => {
				const shadowHost = document.createElement(options.name);
				const uiContainer = document.createElement("div");
				const shadow = shadowHost.attachShadow({ mode: "open" });
				shadow.append(uiContainer);
				let mounted: ReportFrame | undefined;
				const ui = {
					shadowHost,
					uiContainer,
					shadow,
					get mounted() {
						return mounted;
					},
					mount: () => {
						const { anchor, append } = options;
						if (anchor instanceof Element && typeof append === "function") {
							append(anchor, shadowHost);
						}
						mounted = options.onMount(uiContainer, shadow, shadowHost);
					},
					remove: () => {
						shadowHost.remove();
						mounted = undefined;
					},
					autoMount: () => undefined,
				} satisfies ReportUi;
				built.resolve(ui);
			},
		});
		return built.promise;
	},
}));

const MR1 = "https://gitlab.example.test/team/app/-/merge_requests/1";
const MR1_CHANGES = `${MR1}/diffs`;
const MR2 = "https://gitlab.example.test/team/app/-/merge_requests/2";
const EXTENSION_ORIGIN = "chrome-extension://ijkajblcbajjpjbknfgdiiiljipafiko";

/** A merge request page with its overview and its changes, each in its own tab pane. */
function mergeRequestPage(showing: "overview" | "changes" = "overview"): {
	overview: HTMLElement;
	changes: HTMLElement;
} {
	document.body.innerHTML = `
		<div id="overview" ${showing === "overview" ? "" : 'style="display: none"'}>
			<div class="issuable-discussion">
				<div class="detail-page-description">The private description stays the provider's.</div>
				<div id="widget-state" class="mr-state-widget"></div>
				<div id="notes"></div>
			</div>
		</div>
		<div id="changes" ${showing === "changes" ? "" : 'style="display: none"'}>
			<section class="rd-app-content" aria-label="Diff files"><div class="rd-app-diffs-list"></div></section>
		</div>`;
	const overview = document.getElementById("overview");
	const changes = document.getElementById("changes");
	if (overview === null || changes === null) {
		throw new Error("Missing panes");
	}
	return { overview, changes };
}

const cleanup: (() => void)[] = [];
const frames: (() => void)[] = [];

function context(): ContentScriptContext {
	const fake = {
		addEventListener: (target: EventTarget, type: string, handler: EventListener) => {
			target.addEventListener(type, handler);
			cleanup.push(() => target.removeEventListener(type, handler));
		},
		onInvalidated: (dispose: () => void) => {
			cleanup.push(dispose);
			return () => undefined;
		},
		setTimeout: (handler: () => void, timeout: number) => {
			const id = window.setTimeout(handler, timeout);
			cleanup.push(() => window.clearTimeout(id));
			return id;
		},
		requestAnimationFrame: (render: FrameRequestCallback) => {
			frames.push(() => render(0));
			return frames.length;
		},
	};
	// oxlint-disable-next-line typescript/no-unsafe-type-assertion -- The controller and provider entrypoint use only these lifecycle members.
	return fake as unknown as ContentScriptContext;
}

/** Deliver DOM observer notifications and one batched reconciliation, without a clock delay. */
async function renderFrame(): Promise<void> {
	await Promise.resolve();
	for (const render of frames.splice(0)) {
		render();
	}
	await Promise.resolve();
}

function reports(root: ParentNode = document): HTMLElement[] {
	return [...root.querySelectorAll<HTMLElement>("hephaestus-report")];
}

function report(): { host: HTMLElement; iframe: HTMLIFrameElement; token: string } {
	const [host] = reports();
	const iframe = host?.shadowRoot?.querySelector("iframe");
	if (host === undefined || iframe === null || iframe === undefined) {
		throw new Error("No report is mounted");
	}
	return { host, iframe, token: new URL(iframe.src).searchParams.get("open") ?? "" };
}

function statusLine(): HTMLElement | null {
	return report().host.shadowRoot?.querySelector<HTMLElement>("[role=status]") ?? null;
}

async function mount(href = MR1): Promise<InlineController> {
	const controller = new InlineController(context());
	controller.sync(href);
	builds.at(-1)?.build();
	await vi.waitFor(() => {
		expect(reports()).toHaveLength(1);
	});
	return controller;
}

function fromFrame(data: unknown, origin = EXTENSION_ORIGIN): void {
	window.dispatchEvent(new MessageEvent("message", { data, origin }));
}

/** The system colour preference, switchable like a real one. */
const system = Object.assign(new EventTarget(), { matches: false });

function flipSystem(dark: boolean): void {
	system.matches = dark;
	system.dispatchEvent(new Event("change"));
}

/**
 * Records what the page posts to the frame. jsdom gives an iframe inside a shadow root no window, so
 * the frame gets a stand-in that records its messages.
 */
function postsTo(iframe: HTMLIFrameElement) {
	const post = vi.fn<(message: unknown, targetOrigin: string) => void>();
	Object.defineProperty(iframe, "contentWindow", {
		configurable: true,
		value: { postMessage: post },
	});
	return post;
}

function lastTheme(post: { mock: { calls: unknown[][] } }): unknown {
	const message = post.mock.calls.at(-1)?.[0];
	return typeof message === "object" && message !== null && "theme" in message
		? message.theme
		: undefined;
}

/** Layout is not what these tests are about; a list preview's resize watch reports nothing. */
function inertResizeObserver() {
	return { observe: vi.fn<() => void>(), disconnect: vi.fn<() => void>() };
}

beforeEach(() => {
	vi.stubGlobal("ResizeObserver", vi.fn(inertResizeObserver));
	builds.length = 0;
	frames.length = 0;
	document.body.innerHTML = "";
	system.matches = false;
	vi.stubGlobal("matchMedia", () => system);
	for (const name of ["data-color-mode", "data-light-theme", "data-dark-theme"]) {
		document.documentElement.removeAttribute(name);
	}
	document.documentElement.className = "";
	document.body.dataset.page = "projects:merge_requests:show";
});

afterEach(() => {
	for (const dispose of cleanup.splice(0)) {
		dispose();
	}
	vi.useRealTimers();
	vi.restoreAllMocks();
	vi.unstubAllGlobals();
});

/** Takes a node out and puts it back exactly where it was, as a provider re-render can. */
function reinsert(node: HTMLElement): void {
	const parent = required(node.parentElement, "the node's parent");
	const next = node.nextSibling;
	node.remove();
	if (next === null) {
		parent.append(node);
	} else {
		next.before(node);
	}
}

describe("the report in the page", () => {
	it("goes in the merge request's reports area as one generic host around the frame", async () => {
		mergeRequestPage();
		await mount();
		const { host, iframe } = report();
		expect(host.parentElement?.className).toBe("issuable-discussion");
		expect(host.nextElementSibling?.id).toBe("notes");
		expect(host.dataset).toMatchObject({
			provider: "gitlab",
			layout: "gitlab-merge-request",
			theme: "light",
		});
		const source = new URL(iframe.src);
		expect(iframe.src.split("?")[0]).toBe(`${EXTENSION_ORIGIN}/inline.html`);
		expect([...source.searchParams.keys()].toSorted()).toStrictEqual(["open", "provider", "theme"]);
		expect(iframe.hidden).toBe(true);
		expect(iframe.title).toBe("Hephaestus practice review");
	});

	it("says nothing about the work or the account in the page, whatever the frame will say", async () => {
		mergeRequestPage();
		await mount();
		const { host } = report();
		expect(statusLine()?.textContent).toContain(LOADING_TEXT);
		// Everything the page can read about the report: its attributes and its shadow tree's text.
		expect([...host.attributes].map((attribute) => attribute.name).toSorted()).toStrictEqual([
			"data-layout",
			"data-provider",
			"data-theme",
		]);
		// The mark's own title, the report's name and the generic line: the same on every page.
		expect(host.shadowRoot?.textContent.replaceAll(/\s+/gu, " ").trim()).toBe(
			`Hephaestus Practice review${LOADING_TEXT}`,
		);
	});

	it("shows the frame only once its own frame says it is ready, and sizes it as told", async () => {
		mergeRequestPage();
		await mount();
		const { iframe, token } = report();
		fromFrame({ type: "hephaestus:ready", open: token }, "https://gitlab.example.test");
		fromFrame({ type: "hephaestus:ready", open: "0000000000000000" });
		expect(iframe.hidden).toBe(true);
		fromFrame({ type: "hephaestus:ready", open: token });
		expect(iframe.hidden).toBe(false);
		expect(statusLine()).toBeNull();
		fromFrame({ type: "hephaestus:size", open: token, height: 320 });
		expect(iframe.style.blockSize).toBe("320px");
		fromFrame({ type: "hephaestus:size", open: "0000000000000000", height: 900 });
		fromFrame({ type: "hephaestus:size", open: token, height: 999_999 });
		expect(iframe.style.blockSize).toBe("320px");
	});

	it("replaces the loading line with a generic failure when the frame never becomes ready", async () => {
		vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
		mergeRequestPage();
		await mount();
		vi.advanceTimersByTime(FRAME_READY_BUDGET_MS);
		expect(statusLine()?.textContent).toContain(FAILED_TEXT);
		expect(report().iframe.hidden).toBe(true);
	});

	it("never shows the failure once the frame was ready", async () => {
		vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
		mergeRequestPage();
		await mount();
		fromFrame({ type: "hephaestus:ready", open: report().token });
		vi.advanceTimersByTime(FRAME_READY_BUDGET_MS);
		expect(report().host.shadowRoot?.textContent).not.toContain(FAILED_TEXT);
	});

	it("takes nothing from the page to open the report: no expansion survives in the address", async () => {
		mergeRequestPage();
		await mount();
		fromFrame({ type: "hephaestus:size", open: report().token, height: 600, expanded: true });
		const again = new InlineController(context());
		document.body.innerHTML = "";
		mergeRequestPage();
		again.sync(MR1);
		builds.at(-1)?.build();
		await vi.waitFor(() => expect(reports()).toHaveLength(1));
		expect(new URL(report().iframe.src).searchParams.has("expanded")).toBe(false);
	});
});

describe("following the reader across the page", () => {
	it("moves to the changes tab's slot, and back, with one report at a time", async () => {
		const { overview, changes } = mergeRequestPage();
		const controller = await mount();
		const first = report().token;
		overview.style.display = "none";
		changes.style.display = "";
		controller.sync(MR1_CHANGES);
		builds.at(-1)?.build();
		await vi.waitFor(() => expect(reports(changes)).toHaveLength(1));
		expect(reports()).toHaveLength(1);
		expect(report().host.dataset.layout).toBe("gitlab-diff");
		expect(report().host.nextElementSibling?.className).toBe("rd-app-diffs-list");
		// A new frame, not the old one's messages.
		expect(report().token).not.toBe(first);
		fromFrame({ type: "hephaestus:ready", open: first });
		expect(report().iframe.hidden).toBe(true);
	});

	it("does nothing when a sync finds the report already in its slot", async () => {
		mergeRequestPage();
		const controller = await mount();
		const { iframe } = report();
		controller.sync(MR1);
		controller.sync(`${MR1}#note_1`);
		expect(builds).toHaveLength(1);
		expect(report().iframe).toBe(iframe);
	});

	it("replaces the report when the tab shows other work", async () => {
		mergeRequestPage();
		const controller = await mount();
		const first = report().token;
		controller.sync(MR2);
		expect(reports()).toHaveLength(0);
		builds.at(-1)?.build();
		await vi.waitFor(() => expect(reports()).toHaveLength(1));
		expect(report().token).not.toBe(first);
	});

	it("never mounts an old work's report after the provider navigated mid-mount", async () => {
		mergeRequestPage();
		const controller = new InlineController(context());
		controller.sync(MR1);
		controller.sync(MR2);
		builds[0]?.build();
		builds[1]?.build();
		await vi.waitFor(() => expect(reports()).toHaveLength(1));
		await Promise.resolve();
		expect(reports()).toHaveLength(1);
	});

	it("removes the report when the page stops being work or loses its slot", async () => {
		mergeRequestPage();
		const controller = await mount();
		controller.sync("https://gitlab.example.test/team/app/-/merge_requests");
		expect(reports()).toHaveLength(0);
		controller.sync(MR1);
		builds.at(-1)?.build();
		await vi.waitFor(() => expect(reports()).toHaveLength(1));
		document.getElementById("overview")?.remove();
		controller.sync(MR1);
		expect(reports()).toHaveLength(0);
	});

	it.each([
		["host", (host: HTMLElement) => host],
		["ancestor", (host: HTMLElement) => required(host.parentElement, "the host's parent")],
	])(
		"renews readiness after the same %s is removed and reinserted before reconciliation",
		async (_removed, pick) => {
			mergeRequestPage();
			vi.stubGlobal("location", { href: MR1 });
			provider.main(context());
			builds[0]?.build();
			await vi.waitFor(() => expect(reports()).toHaveLength(1));
			const first = report();
			const post = postsTo(first.iframe);
			fromFrame({ type: "hephaestus:ready", open: first.token });
			post.mockClear();
			reinsert(pick(first.host));
			document.documentElement.classList.add("gl-dark");
			await renderFrame();
			expect(post).not.toHaveBeenCalled();
			builds.at(-1)?.build();
			await vi.waitFor(() => expect(reports()).toHaveLength(1));
			const current = report();
			expect(current.token).not.toBe(first.token);
			expect(current.iframe.hidden).toBe(true);
			expect(statusLine()?.textContent).toContain(LOADING_TEXT);
			fromFrame({ type: "hephaestus:ready", open: first.token });
			expect(current.iframe.hidden).toBe(true);
			const freshPost = postsTo(current.iframe);
			fromFrame({ type: "hephaestus:ready", open: current.token });
			expect(current.iframe.hidden).toBe(false);
			expect(freshPost).toHaveBeenCalledExactlyOnceWith(
				{ type: "hephaestus:theme", theme: "dark" },
				EXTENSION_ORIGIN,
			);
		},
	);

	it("comes back as a fresh frame when the provider re-renders its content without navigating", async () => {
		mergeRequestPage();
		vi.stubGlobal("location", { href: MR1 });
		provider.main(context());
		builds[0]?.build();
		await vi.waitFor(() => expect(reports()).toHaveLength(1));
		const first = report().token;
		const discussion = document.querySelector(".issuable-discussion");
		discussion?.replaceWith(discussion.cloneNode(false));
		const fresh = document.querySelector(".issuable-discussion");
		fresh?.insertAdjacentHTML(
			"beforeend",
			'<div id="widget-state" class="mr-state-widget"></div><div id="notes"></div>',
		);
		await renderFrame();
		builds.at(-1)?.build();
		await vi.waitFor(() => expect(reports()).toHaveLength(1));
		expect(report().token).not.toBe(first);
		// Its own insertion does not make it mount again.
		await renderFrame();
		expect(reports()).toHaveLength(1);
	});
});

describe("the report's theme", () => {
	it("answers its own frame's readiness with the provider's theme, not the frame's load", async () => {
		document.documentElement.classList.add("gl-dark");
		mergeRequestPage();
		await mount();
		const { iframe, token } = report();
		expect(new URL(iframe.src).searchParams.get("theme")).toBe("dark");
		const post = postsTo(iframe);
		iframe.dispatchEvent(new Event("load"));
		expect(post).not.toHaveBeenCalled();
		fromFrame({ type: "hephaestus:ready", open: token });
		expect(post).toHaveBeenCalledExactlyOnceWith(
			{ type: "hephaestus:theme", theme: "dark" },
			EXTENSION_ORIGIN,
		);
	});

	it("answers a reloaded document's ready with the current theme, keeping origin and token checks", async () => {
		mergeRequestPage();
		await mount();
		const { iframe, token } = report();
		const post = postsTo(iframe);
		fromFrame({ type: "hephaestus:ready", open: token });
		document.documentElement.classList.add("gl-dark");
		await vi.waitFor(() => expect(lastTheme(post)).toBe("dark"));
		post.mockClear();
		fromFrame({ type: "hephaestus:ready", open: token }, "https://gitlab.example.test");
		fromFrame({ type: "hephaestus:ready", open: "0000000000000000" });
		expect(post).not.toHaveBeenCalled();
		fromFrame({ type: "hephaestus:ready", open: token });
		expect(post).toHaveBeenCalledExactlyOnceWith(
			{ type: "hephaestus:theme", theme: "dark" },
			EXTENSION_ORIGIN,
		);
		expect(iframe.hidden).toBe(false);
		expect(statusLine()).toBeNull();
	});

	it("follows the provider's theme without reloading the frame", async () => {
		mergeRequestPage();
		await mount();
		const { iframe, token, host } = report();
		const post = postsTo(iframe);
		fromFrame({ type: "hephaestus:ready", open: token });
		document.documentElement.classList.add("gl-dark");
		await vi.waitFor(() => expect(host.dataset.theme).toBe("dark"));
		expect(lastTheme(post)).toBe("dark");
		const source = iframe.src;
		document.documentElement.classList.replace("gl-dark", "gl-system");
		await vi.waitFor(() => expect(lastTheme(post)).toBe("light"));
		flipSystem(true);
		expect(lastTheme(post)).toBe("dark");
		expect(iframe.src).toBe(source);
	});

	it("opens a dimmed GitHub page's frame dimmed, and follows high contrast in place", async () => {
		const html = document.documentElement;
		html.dataset.colorMode = "dark";
		html.dataset.darkTheme = "dark_dimmed";
		document.body.innerHTML = `
			<div class="js-pull-discussion-timeline"><div class="js-discussion">
				<div data-partial-name="pullRequestsConversationsRoute.Body"></div>
				<div data-partial-name="pullRequestsConversationsRoute.Timeline"></div>
			</div></div>`;
		await mount("https://github.com/octo/app/pull/1");
		const { iframe, token, host } = report();
		expect(host.dataset.layout).toBe("github-pull-request");
		expect(new URL(iframe.src).searchParams.get("theme")).toBe("dark_dimmed");
		const post = postsTo(iframe);
		fromFrame({ type: "hephaestus:ready", open: token });
		html.dataset.darkTheme = "dark_high_contrast";
		await vi.waitFor(() => expect(lastTheme(post)).toBe("dark_high_contrast"));
	});
});

const PULLS = "https://github.com/octo/app/pulls";
const pull = (number: number) => `https://github.com/octo/app/pull/${number}`;

/** GitHub's list view, as the pull request list renders it. */
function pullList(numbers: readonly number[]): void {
	document.body.innerHTML = `<ul id="rows" role="list" data-listview-component="items-list">${numbers
		.map(
			(number) => `
			<li id="row-${number}" tabindex="0" aria-label="Change ${number}.">
				<div data-listview-item-title-container="true">
					<h3 id="heading-${number}"><a id="title-${number}" data-testid="listitem-title-link" tabindex="-1" href="${pull(number)}">Change ${number}</a></h3>
				</div>
				<div>opened by someone</div>
			</li>`,
		)
		.join("")}</ul>`;
}

function buttons(): HTMLElement[] {
	return [...document.querySelectorAll<HTMLElement>("hephaestus-inspect")];
}

function press(number: number): void {
	const control = buttons().find((button) => button.dataset.workNumber === String(number));
	control?.shadowRoot?.querySelector("button")?.click();
}

function expanded(): string[] {
	return buttons()
		.filter((button) => button.shadowRoot?.querySelector("button")?.ariaExpanded === "true")
		.map((button) => button.dataset.workNumber ?? "");
}

/** Opens row `number`'s preview and builds its frame. */
async function openPreview(number: number): Promise<{ host: HTMLElement; work: string | null }> {
	press(number);
	builds.at(-1)?.build();
	await vi.waitFor(() => expect(reports()).toHaveLength(1));
	const { host, iframe } = report();
	return { host, work: new URL(iframe.src).searchParams.get("work") };
}

describe("a repository's list", () => {
	it("puts the same inspect button after each row's title heading, and looks nothing up", async () => {
		pullList([12, 13]);
		const controller = new InlineController(context());
		controller.sync(PULLS);
		const inserted = buttons();
		expect(inserted.map((button) => button.dataset.workNumber)).toStrictEqual(["12", "13"]);
		// Beside the heading, not in it: the heading's name stays the provider's.
		expect(inserted[0]?.previousElementSibling?.id).toBe("heading-12");
		expect(document.getElementById("heading-12")?.querySelector("hephaestus-inspect")).toBeNull();
		const labels = inserted.map((button) => {
			const inner = button.shadowRoot?.querySelector("button");
			return [inner?.getAttribute("aria-label"), inner?.getAttribute("aria-expanded")];
		});
		expect(labels).toStrictEqual([
			["Hephaestus review of #12", "false"],
			["Hephaestus review of #13", "false"],
		]);
		// Nothing but the buttons: no report, no frame, no lookup.
		await Promise.resolve();
		expect(builds).toHaveLength(0);
		expect(reports()).toHaveLength(0);
	});

	it("adds no second button when the list is synced again", () => {
		pullList([12]);
		const controller = new InlineController(context());
		controller.sync(PULLS);
		controller.sync(PULLS);
		controller.sync(`${PULLS}?q=is%3Aopen`);
		expect(buttons()).toHaveLength(1);
	});

	it("opens one preview inside the row pressed, about that row, and closes it on a second press", async () => {
		pullList([12, 13]);
		const controller = new InlineController(context());
		controller.sync(PULLS);
		const { host, work } = await openPreview(13);
		expect(host.parentElement?.id).toBe("row-13");
		expect(host.dataset.layout).toBe("list-preview");
		expect(work).toBe(pull(13));
		expect(expanded()).toStrictEqual(["13"]);
		press(13);
		expect(reports()).toHaveLength(0);
		expect(expanded()).toStrictEqual([]);
	});

	it("moves the one preview to another row pressed", async () => {
		pullList([12, 13]);
		const controller = new InlineController(context());
		controller.sync(PULLS);
		await openPreview(12);
		const { host, work } = await openPreview(13);
		expect(reports()).toHaveLength(1);
		expect(host.parentElement?.id).toBe("row-13");
		expect(work).toBe(pull(13));
	});

	it("keeps the row's own click and key handlers out of the button", () => {
		pullList([12]);
		const controller = new InlineController(context());
		controller.sync(PULLS);
		const row = vi.fn<(event: Event) => void>();
		const list = document.getElementById("rows");
		for (const type of ["click", "keydown", "keyup", "pointerdown", "mousedown"]) {
			list?.addEventListener(type, row);
		}
		const inner = buttons()[0]?.shadowRoot?.querySelector("button");
		for (const type of ["keydown", "keyup"]) {
			inner?.dispatchEvent(new KeyboardEvent(type, { key: " ", bubbles: true, composed: true }));
		}
		for (const type of ["pointerdown", "mousedown"]) {
			inner?.dispatchEvent(new MouseEvent(type, { bubbles: true, composed: true }));
		}
		inner?.click();
		expect(row).not.toHaveBeenCalled();
		expect(expanded()).toStrictEqual(["12"]);
	});

	it("closes the preview when the reader filters or pages the list", async () => {
		pullList([12]);
		const controller = new InlineController(context());
		controller.sync(PULLS);
		await openPreview(12);
		controller.sync(`${PULLS}?q=is%3Aclosed`);
		expect(reports()).toHaveLength(0);
		expect(expanded()).toStrictEqual([]);
	});

	it("follows a row the provider reuses for other work, closing the preview about the old", async () => {
		pullList([12, 13]);
		vi.stubGlobal("location", { href: PULLS });
		provider.main(context());
		await openPreview(12);
		// Only the title link's address changes; the row and its nodes stay.
		document.getElementById("title-12")?.setAttribute("href", pull(40));
		await renderFrame();
		expect(reports()).toHaveLength(0);
		expect(buttons().map((button) => button.dataset.workNumber)).toStrictEqual(["40", "13"]);
		const { host, work } = await openPreview(40);
		expect(host.parentElement?.id).toBe("row-12");
		expect(work).toBe(pull(40));
		// And back: still one control per row, never an accumulation.
		document.getElementById("title-12")?.setAttribute("href", pull(12));
		await renderFrame();
		expect(reports()).toHaveLength(0);
		expect(buttons().map((button) => button.dataset.workNumber)).toStrictEqual(["12", "13"]);
	});

	it("takes its buttons and preview away when the tab leaves the list", async () => {
		pullList([12]);
		const controller = new InlineController(context());
		controller.sync(PULLS);
		await openPreview(12);
		controller.sync("https://github.com/octo/app");
		expect(buttons()).toHaveLength(0);
		expect(reports()).toHaveLength(0);
	});

	it("gives an unknown list shape nothing", () => {
		document.body.innerHTML = `<ul><li><a href="${pull(12)}">A list without the provider's hooks</a></li></ul>`;
		const controller = new InlineController(context());
		controller.sync(PULLS);
		expect(buttons()).toHaveLength(0);
	});
});
