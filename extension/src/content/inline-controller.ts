import { browser } from "@wxt-dev/browser";
import type { ContentScriptContext } from "wxt/utils/content-script-context";
import {
	createShadowRootUi,
	type ShadowRootContentScriptUi,
} from "wxt/utils/content-script-ui/shadow-root";

import { alignToTitle } from "~/content/align";
import {
	findListRows,
	findReportSlot,
	gitLabPageAllowed,
	INSPECT_TAG,
	REPORT_HOST_TAG,
	type ReportSlot,
} from "~/content/anchors";
import { createInspectButton, setInspectExpanded, WORK_NUMBER } from "~/content/inspect-button";
import { type ReportFrame, renderReport } from "~/content/report-host";
import { DARK_SCHEME_QUERY, providerTheme, THEME_ATTRIBUTES } from "~/content/theme";
import {
	type FramePalette,
	type FrameProvider,
	frameToPageSchema,
	isDarkPalette,
	OPEN_PARAMETER,
	type PageToFrame,
	PROVIDER_PARAMETER,
	THEME_PARAMETER,
	WORK_PARAMETER,
} from "~/shared/frame-messages";
import {
	type ListPage,
	parseListPage,
	parseWorkPage,
	type WorkPage,
	type WorkPageProvider,
} from "~/shared/work-url";
import reportCss from "~/content/report.css?inline";

/**
 * How long the frame has to say it is ready before the report shows the generic failure line. A frame
 * that never loads (something else on the page replaced its document, say) must not leave an empty
 * box that looks like an answer.
 */
export const FRAME_READY_BUDGET_MS = 10_000;

/**
 * The origin the inline page runs at. The frame is loaded from the per-session dynamic URL, but its
 * document belongs to the extension's own origin, which is what `event.origin` reports and what a
 * `postMessage` must target — and which no page can post from.
 */
function frameOrigin(): string {
	return `chrome-extension://${browser.runtime.id}`;
}

function frameProvider(provider: WorkPageProvider): FrameProvider {
	return provider === "GITHUB" ? "github" : "gitlab";
}

function withoutFragment(href: string): string {
	const url = new URL(href);
	url.hash = "";
	return url.href;
}

function sendTheme(iframe: HTMLIFrameElement, theme: FramePalette): void {
	const message: PageToFrame = { type: "hephaestus:theme", theme };
	iframe.contentWindow?.postMessage(message, frameOrigin());
}

/**
 * Marks the host with the provider, its layout and the light or dark family of its palette. The host's
 * styles take the provider's own design tokens, which follow its selected theme by themselves; the
 * family only picks the fallbacks for a provider that has no tokens.
 */
function markHost(
	host: HTMLElement,
	mounted: Pick<Mounted, "provider" | "slot">,
	theme: FramePalette,
) {
	host.dataset.provider = frameProvider(mounted.provider);
	host.dataset.layout = mounted.slot.layout;
	host.dataset.theme = isDarkPalette(theme) ? "dark" : "light";
}

/** Lines a list row's preview up with its title now and whenever the row is laid out again. */
function alignedPreview(host: HTMLElement, slot: ReportSlot): ResizeObserver | undefined {
	const title = slot.alignWith;
	if (title === undefined) {
		return undefined;
	}
	alignToTitle(host, title);
	const observer = new ResizeObserver(() => {
		alignToTitle(host, title);
	});
	observer.observe(slot.parent);
	return observer;
}

/** Whether two slots put the report in the same place: same parent and neighbour. */
function sameSlot(a: ReportSlot, b: ReportSlot): boolean {
	return a.parent === b.parent && a.before === b.before;
}

interface Mounted {
	key: string;
	provider: WorkPageProvider;
	slot: ReportSlot;
	ui: ShadowRootContentScriptUi<ReportFrame>;
	/** The token this mounting's frame echoes; anything else is a message from an older frame. */
	open: string;
	ready: boolean;
	timer: number;
	/** Keeps a list row's preview lined up with its title while the row's layout changes. */
	aligned: ResizeObserver | undefined;
}

/**
 * Keeps one practice review report in the work's content column while the tab shows a supported
 * piece of work: a generic box with the extension-origin frame in it, which looks the work up as soon
 * as it loads. The box is the same for every page, whatever the answer — not followed, signed out, or
 * a full review — so nothing about the work, the account or the review is ever written into the page.
 *
 * `sync` is idempotent and cheap, because the provider's single-page navigation and its re-renders
 * both call it: other work replaces the report, a replaced or moved slot (the conversation and the
 * diff each have one) re-mounts it, and a page without a slot has none. What the reader had open is
 * the frame's to remember, with the worker, never the page's.
 *
 * On a repository's list, each recognised row gets the same inspect button, and nothing is looked up
 * until the reader presses one. Then a single preview — the same report, about that row's work — opens
 * inside the row; pressing it again, pressing another row's, or moving through the list closes it.
 */
export class InlineController {
	readonly #ctx: ContentScriptContext;
	/** The system preference a page following the system resolves its palette by. */
	readonly #system: MediaQueryList;
	#mounted: Mounted | undefined;
	/** Moves whenever the report that should exist changes; a mount begun under an older one is void. */
	#generation = 0;
	#pending: { key: string; slot: ReportSlot } | undefined;
	/** The address the last sync judged, which may be ahead of a `location` not yet committed. */
	#href = "";
	/** The address of the list the tab shows, filters and page included, when it shows one. */
	#list: string | undefined;
	/** The canonical address of the list row whose preview is open. */
	#open: string | undefined;

	constructor(ctx: ContentScriptContext) {
		this.#ctx = ctx;
		ctx.addEventListener(window, "message", (event) => {
			this.#onFrameMessage(event);
		});
		// A provider's theme switch applies without a reload, so the report follows it live, and so
		// does a system switch while the page follows the system.
		this.#system = window.matchMedia(DARK_SCHEME_QUERY);
		ctx.addEventListener(this.#system, "change", () => {
			this.#applyTheme();
		});
		const theme = new MutationObserver(() => {
			// Structural observers first invalidate a frame whose host was detached in this turn.
			// Even a same-node reinsert creates a new iframe document, initially at the page's origin.
			queueMicrotask(() => this.#applyTheme());
		});
		theme.observe(document.documentElement, {
			attributes: true,
			attributeFilter: THEME_ATTRIBUTES,
		});
		ctx.onInvalidated(() => {
			theme.disconnect();
			this.#leaveList();
			this.#unmount();
		});
	}

	/** A removed host or ancestor destroys its iframe document, even if already reinserted. */
	pageChanged(records: MutationRecord[]): void {
		const host = this.#mounted?.ui.shadowHost;
		if (
			host !== undefined &&
			records.some((record) =>
				[...record.removedNodes].some((node) => node === host || node.contains(host)),
			)
		) {
			this.#unmount();
		}
	}

	/**
	 * @param href The address to judge. A location change is announced by the Navigation API before
	 * the new URL is committed, so its handler passes the destination instead of reading `location`.
	 */
	sync(href: string = location.href): void {
		this.#href = href;
		const projectPage = gitLabPageAllowed(document.body);
		const page = parseWorkPage(href);
		if (page !== undefined && (page.provider === "GITHUB" || projectPage)) {
			this.#leaveList();
			const slot = findReportSlot(document, page.provider);
			this.#show(page.canonicalUrl, page.provider, slot);
			return;
		}
		const list = parseListPage(href);
		if (list !== undefined && (list.provider === "GITHUB" || projectPage)) {
			this.#syncList(list, href);
			return;
		}
		this.#leaveList();
		this.#unmount();
	}

	/**
	 * Keeps exactly one inspect control on each row, for the work the row names now, and the one open
	 * preview inside its row. A provider may reuse a row for other work, so a control for anything else
	 * goes, and a preview whose work no row names any longer closes.
	 */
	#syncList(page: ListPage, href: string): void {
		const address = withoutFragment(href);
		if (this.#list !== address) {
			// Another list, or the same list filtered or paged: a preview was about a row of the list as
			// it was, and the worker refuses it from here on.
			this.#open = undefined;
		}
		this.#list = address;
		const provider = frameProvider(page.provider);
		const rows = findListRows(document, page);
		const kept = new Set<Element>();
		for (const { after, work } of rows) {
			const next = after.nextElementSibling;
			let control =
				next?.localName === INSPECT_TAG && next.getAttribute(WORK_NUMBER) === String(work.number)
					? next
					: undefined;
			if (control === undefined) {
				control = createInspectButton(work, provider, (target) => {
					this.#toggle(target);
				});
				after.after(control);
			}
			setInspectExpanded(control, work.canonicalUrl === this.#open);
			kept.add(control);
		}
		for (const control of document.querySelectorAll(INSPECT_TAG)) {
			if (!kept.has(control)) {
				control.remove();
			}
		}
		const open = rows.find(({ work }) => work.canonicalUrl === this.#open);
		if (open === undefined) {
			this.#open = undefined;
			this.#unmount();
			return;
		}
		this.#show(open.work.canonicalUrl, page.provider, open.preview, open.work);
	}

	#toggle(work: WorkPage): void {
		this.#open = this.#open === work.canonicalUrl ? undefined : work.canonicalUrl;
		this.sync(this.#href);
	}

	/** Leaves list mode: its buttons go, and so does any preview. */
	#leaveList(): void {
		if (this.#list === undefined) {
			return;
		}
		this.#list = undefined;
		this.#open = undefined;
		for (const button of document.querySelectorAll(INSPECT_TAG)) {
			button.remove();
		}
	}

	/**
	 * Keeps the report for `key` in `slot`, or none without a slot. `row` is the list row a preview is
	 * about; without it the report is about the tab's own page.
	 */
	#show(
		key: string,
		provider: WorkPageProvider,
		slot: ReportSlot | undefined,
		row?: WorkPage,
	): void {
		if (slot === undefined) {
			this.#unmount();
			return;
		}
		const mounted = this.#mounted;
		if (
			mounted !== undefined &&
			mounted.key === key &&
			sameSlot(mounted.slot, slot) &&
			mounted.ui.shadowHost.isConnected
		) {
			return;
		}
		const pending = this.#pending;
		if (pending?.key === key && sameSlot(pending.slot, slot)) {
			return;
		}
		this.#unmount();
		void this.#mount(key, provider, slot, row);
	}

	/**
	 * Building the shadow root is asynchronous, and the provider can navigate or re-render meanwhile. A
	 * mount superseded by then is dropped rather than attached to the old slot, and the page is judged
	 * afresh.
	 */
	async #mount(
		key: string,
		provider: WorkPageProvider,
		slot: ReportSlot,
		row: WorkPage | undefined,
	): Promise<void> {
		const generation = this.#generation;
		this.#pending = { key, slot };
		const theme = this.#pageTheme(provider);
		const open = crypto.randomUUID();
		const source = new URL(browser.runtime.getURL("/inline.html"));
		source.searchParams.set(OPEN_PARAMETER, open);
		// The look to paint first; later theme changes arrive as messages.
		source.searchParams.set(PROVIDER_PARAMETER, frameProvider(provider));
		source.searchParams.set(THEME_PARAMETER, theme);
		if (row !== undefined) {
			source.searchParams.set(WORK_PARAMETER, row.canonicalUrl);
		}
		const ui = await createShadowRootUi<ReportFrame>(this.#ctx, {
			name: REPORT_HOST_TAG,
			position: "inline",
			// `report.css` states the reset itself, with the display a block section needs.
			inheritStyles: true,
			css: reportCss,
			anchor: slot.parent,
			append: (parent, element) => {
				if (slot.before === null) {
					parent.append(element);
				} else {
					slot.before.before(element);
				}
			},
			onMount: (container) => renderReport(container, source.href),
		});
		if (generation !== this.#generation) {
			return;
		}
		this.#pending = undefined;
		if (
			!slot.parent.isConnected ||
			(slot.before !== null && slot.before.parentElement !== slot.parent)
		) {
			this.sync(this.#href);
			return;
		}
		ui.mount();
		const aligned = alignedPreview(ui.shadowHost, slot);
		const timer = this.#ctx.setTimeout(() => {
			if (this.#mounted?.open === open && !this.#mounted.ready) {
				ui.mounted?.fail();
			}
		}, FRAME_READY_BUDGET_MS);
		this.#mounted = { key, provider, slot, ui, open, ready: false, timer, aligned };
		markHost(ui.shadowHost, this.#mounted, theme);
	}

	#onFrameMessage(event: MessageEvent): void {
		const mounted = this.#mounted;
		// The origin is the check: only the extension's own frame can post from it, and a page cannot
		// forge it. `event.source` is not compared, because in a content script's isolated world it is
		// not the same object as `iframe.contentWindow`. The token only tells this mounting's frame from
		// an earlier one.
		if (mounted === undefined || event.origin !== frameOrigin()) {
			return;
		}
		const message = frameToPageSchema.safeParse(event.data);
		const frame = mounted.ui.mounted;
		if (!message.success || message.data.open !== mounted.open || frame === undefined) {
			return;
		}
		if (message.data.type === "hephaestus:size") {
			frame.iframe.style.blockSize = `${message.data.height}px`;
		} else {
			if (!mounted.ready) {
				mounted.ready = true;
				clearTimeout(mounted.timer);
				frame.reveal();
			}
			// Not on `load`, which can fire before the frame listens: it posts ready once it does. The
			// URL gave the theme at mounting; every ready, including a document reload, gets it now.
			sendTheme(frame.iframe, this.#pageTheme(mounted.provider));
		}
	}

	#pageTheme(provider: WorkPageProvider): FramePalette {
		return providerTheme(document.documentElement, provider, this.#system.matches);
	}

	#applyTheme(): void {
		const mounted = this.#mounted;
		if (mounted === undefined) {
			return;
		}
		const theme = this.#pageTheme(mounted.provider);
		markHost(mounted.ui.shadowHost, mounted, theme);
		const frame = mounted.ui.mounted;
		if (mounted.ready && frame !== undefined) {
			sendTheme(frame.iframe, theme);
		}
	}

	/** Takes the report out at once: its frame unloads, so nothing keeps asking unseen. */
	#unmount(): void {
		this.#generation += 1;
		this.#pending = undefined;
		const mounted = this.#mounted;
		if (mounted !== undefined) {
			clearTimeout(mounted.timer);
			mounted.aligned?.disconnect();
			mounted.ui.remove();
		}
		this.#mounted = undefined;
	}
}
