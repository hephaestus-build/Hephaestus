import { expect, waitFor } from "storybook/test";

const LAYOUT_SLACK_PX = 1;
const MIN_TARGET_PX = 24;

export async function expectNoPageOverflow() {
	await expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(
		document.documentElement.clientWidth + LAYOUT_SLACK_PX,
	);
}

/**
 * Overlay popups are positioned against the viewport rather than the document, so that an open one
 * cannot widen the page (SC 1.4.10). The price is that nothing moves them for free: this asserts the
 * popup still holds its offset from the trigger after `scroll` has moved the trigger.
 */
export async function expectOverlayFollowsTrigger(
	trigger: HTMLElement,
	popup: HTMLElement,
	scroll: () => void,
) {
	const offsetOf = () => {
		const triggerRect = trigger.getBoundingClientRect();
		const popupRect = popup.getBoundingClientRect();
		return { x: popupRect.left - triggerRect.left, y: popupRect.top - triggerRect.top };
	};

	const before = offsetOf();
	const triggerTopBefore = trigger.getBoundingClientRect().top;
	scroll();
	await waitFor(() => {
		void expect(
			Math.abs(trigger.getBoundingClientRect().top - triggerTopBefore),
			"The scroll callback did not move the trigger, so the assertion below would prove nothing.",
		).toBeGreaterThan(LAYOUT_SLACK_PX);
	});

	// Base UI repositions on the next frame, not synchronously with the scroll event.
	await waitFor(() => {
		const after = offsetOf();
		void expect(
			Math.abs(after.x - before.x),
			"The popup drifted horizontally off its trigger.",
		).toBeLessThanOrEqual(LAYOUT_SLACK_PX);
		void expect(
			Math.abs(after.y - before.y),
			"The popup drifted vertically off its trigger.",
		).toBeLessThanOrEqual(LAYOUT_SLACK_PX);
	});
}

export function horizontalScrollParentOf(element: HTMLElement): HTMLElement {
	for (let node = element.parentElement; node != null; node = node.parentElement) {
		if (["auto", "scroll"].includes(getComputedStyle(node).overflowX)) {
			return node;
		}
	}
	throw new Error(
		"A table wider than the viewport has no horizontally scrollable ancestor, so its right-hand columns cannot be reached.",
	);
}

/**
 * A wide table is SC 1.4.10's own exception only while it scrolls in place, rather than dragging the
 * page sideways or clipping its right-hand columns away.
 */
export async function expectTablesScrollInPlace(
	root: HTMLElement | Document = document,
	{ expectOverflow = false }: { expectOverflow?: boolean } = {},
) {
	const tables = root.querySelectorAll<HTMLElement>("table");
	await expect(tables.length).toBeGreaterThan(0);
	for (const table of tables) {
		const scroller = horizontalScrollParentOf(table);
		await expect(scroller.getBoundingClientRect().width).toBeLessThanOrEqual(
			window.innerWidth + LAYOUT_SLACK_PX,
		);
		const overflows = table.scrollWidth > scroller.clientWidth + LAYOUT_SLACK_PX;
		if (expectOverflow) {
			await expect(
				overflows,
				"Expected this table to be wider than its scroller, so that the scrolling assertion below means something.",
			).toBe(true);
		}
		if (overflows) {
			scroller.scrollLeft = scroller.scrollWidth;
			await expect(scroller.scrollLeft).toBeGreaterThan(0);
		}
	}
}

/**
 * The drawer popup is `fixed`, so it never widens the document and {@link expectNoPageOverflow}
 * cannot see it. At 320px the panel is the whole viewport, so content that overflows it is content a
 * reader has to drag sideways to finish — SC 1.4.10 on the only box that can break here.
 *
 * Measured on the body rather than the popup: the popup paints a `::after` swipe bleed at
 * `left: 100%`, and an absolutely positioned pseudo still counts toward its ancestor's
 * `scrollWidth`. That box is `pointer-events-none`, the popup is `overflow-x: visible`, so it
 * reaches nobody — but it makes the popup's own `scrollWidth` read one bleed too wide, every time.
 */
export async function expectNoPanelOverflow(panel: HTMLElement) {
	const body = panel.querySelector<HTMLElement>('[data-slot="drawer-body"]');
	if (body == null) {
		throw new Error("A drawer panel with no body has no content box to measure.");
	}
	await expect(body.scrollWidth).toBeLessThanOrEqual(body.clientWidth + LAYOUT_SLACK_PX);
}

/**
 * Nothing reaches past the viewport (SC 1.4.10), and the failure names the element that did rather
 * than reporting that one number exceeds another.
 */
export async function expectNoOverflowingElement(root: HTMLElement = document.body) {
	const limit = window.innerWidth + LAYOUT_SLACK_PX;
	const offenders = [...root.querySelectorAll<HTMLElement>("*")]
		.map((element) => ({ element, rect: element.getBoundingClientRect() }))
		.filter(({ rect }) => rect.width > 0 && (rect.right > limit || rect.left < -LAYOUT_SLACK_PX))
		.map(
			({ element, rect }) =>
				`${element.tagName.toLowerCase()}.${element.className.split(" ").slice(0, 3).join(".")} [${Math.round(rect.left)}…${Math.round(rect.right)}]`,
		);
	await expect(offenders, `Reaches past the ${window.innerWidth}px viewport`).toEqual([]);
}

export async function expectTargetSize(control: HTMLElement) {
	const rect = control.getBoundingClientRect();
	await expect(rect.width).toBeGreaterThanOrEqual(MIN_TARGET_PX);
	await expect(rect.height).toBeGreaterThanOrEqual(MIN_TARGET_PX);
}

/** SC 2.5.8's *Spacing* exception: an undersized target passes when its 24 px circle hits no other. */
export async function expectTargetSpacing(controls: HTMLElement[]) {
	await expect(controls.length).toBeGreaterThan(0);
	const centres = controls.map((control) => {
		const rect = control.getBoundingClientRect();
		return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
	});
	for (const [index, centre] of centres.entries()) {
		for (const other of centres.slice(index + 1)) {
			await expect(Math.hypot(centre.x - other.x, centre.y - other.y)).toBeGreaterThanOrEqual(
				MIN_TARGET_PX,
			);
		}
	}
}

export async function expectWithinViewport(control: HTMLElement) {
	const rect = control.getBoundingClientRect();
	await expect(rect.top).toBeGreaterThanOrEqual(-LAYOUT_SLACK_PX);
	await expect(rect.bottom).toBeLessThanOrEqual(window.innerHeight + LAYOUT_SLACK_PX);
	await expect(rect.left).toBeGreaterThanOrEqual(-LAYOUT_SLACK_PX);
	await expect(rect.right).toBeLessThanOrEqual(window.innerWidth + LAYOUT_SLACK_PX);
}

/** Not for a control inside a horizontal scroller — it is legitimately off-screen until scrolled. */
export async function expectControlOnScreen(control: HTMLElement) {
	await expectWithinViewport(control);
	await expectTargetSize(control);
}

/** The last match wins: layers portal in open order, so a confirm raised from a sheet comes after it. */
export function openDialogPopup(): HTMLElement {
	const popups = document.querySelectorAll<HTMLElement>('[role="dialog"], [role="alertdialog"]');
	const popup = [...popups].at(-1);
	if (popup == null) {
		throw new Error("No open dialog: expected an element with role dialog or alertdialog.");
	}
	return popup;
}

/** A centred `fixed` popup hangs off *both* edges once it outgrows the viewport, and cannot be scrolled back. */
export async function expectDialogFitsViewport(popup: HTMLElement = openDialogPopup()) {
	// The layout box, not `getBoundingClientRect`: the `zoom-in-95` enter animation may still be
	// mid-flight, and its 0.95 scale would let a slightly-too-big popup pass.
	await expect(popup.offsetHeight).toBeLessThanOrEqual(window.innerHeight);
	await expect(popup.offsetWidth).toBeLessThanOrEqual(window.innerWidth);

	await expectWithinViewport(popup);
}

/** Exactly one scroller, so reaching the bottom of a long form cannot scroll the title out of sight. */
export async function expectDialogBodyScrolls(popup: HTMLElement = openDialogPopup()) {
	const heading = popup.querySelector<HTMLElement>('h2, [role="heading"]');
	if (heading == null) {
		throw new Error("A dialog with no heading has nothing to keep pinned while its body scrolls.");
	}

	const [scroller, ...alsoScroll] = [popup, ...popup.querySelectorAll<HTMLElement>("*")].filter(
		(node) =>
			node.scrollHeight > node.clientHeight + LAYOUT_SLACK_PX &&
			["auto", "scroll"].includes(getComputedStyle(node).overflowY),
	);
	if (scroller == null) {
		throw new Error(
			"Nothing in the dialog scrolls, so its body cannot grow under a pinned heading.",
		);
	}
	await expect(alsoScroll).toHaveLength(0);

	const before = heading.getBoundingClientRect().top;
	scroller.scrollTop = scroller.scrollHeight;
	await expect(scroller.scrollTop).toBeGreaterThan(0);
	await expect(Math.abs(heading.getBoundingClientRect().top - before)).toBeLessThanOrEqual(
		LAYOUT_SLACK_PX,
	);
	await expectWithinViewport(heading);
}

/**
 * Only a drawer's own regions reach its edges. Everything else is content and sits inside their
 * padding.
 *
 * The failure this catches is a host rendering a banner as a sibling of `DrawerBody` rather than
 * inside it: it lands as a direct child of the panel, which has no padding of its own, and touches
 * both edges while every field below it is inset.
 */
export async function expectPanelContentInset(panel: HTMLElement) {
	const regions = new Set(["drawer-header", "drawer-body", "drawer-footer", "drawer-content"]);
	const box = panel.getBoundingClientRect();
	// The panel's own border, which its regions sit inside.
	const BORDER = 2;
	const flush = [...panel.querySelectorAll<HTMLElement>("*")].filter((element) => {
		const slot = element.dataset.slot ?? "";
		if (regions.has(slot) || element.tagName === "FORM") {
			return false;
		}
		const rect = element.getBoundingClientRect();
		// Narrow elements are decoration — separators, bleeds — not content that should be inset.
		if (rect.width < 40) {
			return false;
		}
		return Math.abs(rect.left - box.left) <= BORDER || Math.abs(box.right - rect.right) <= BORDER;
	});
	await expect(
		flush.map((element) => `${element.tagName.toLowerCase()}.${element.className.split(" ")[0]}`),
	).toEqual([]);
}
