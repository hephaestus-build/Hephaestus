import {
	type ReactElement,
	type ReactNode,
	useEffect,
	useRef,
	useState,
	useSyncExternalStore,
} from "react";

import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";

/** How long after a tip closed the next one still opens at once, as a toolbar's tooltips do. */
const SKIP_DELAY_MS = 500;
/** The hover delay of the first tip, so a pointer passing over an (i) does not open it. */
const OPEN_DELAY_MS = 150;

/**
 * Whether a tip is open or closed less than {@link SKIP_DELAY_MS} ago, shared by every tip on the
 * page: while it holds, the next tip a pointer reaches opens without its delay.
 */
const warmth = (() => {
	let open = 0;
	let warm = false;
	let cool: ReturnType<typeof setTimeout> | undefined;
	const listeners = new Set<() => void>();
	const set = (next: boolean) => {
		if (next !== warm) {
			warm = next;
			for (const listener of listeners) {
				listener();
			}
		}
	};
	return {
		subscribe: (listener: () => void) => {
			listeners.add(listener);
			return () => listeners.delete(listener);
		},
		get: () => warm,
		opened: () => {
			open += 1;
			clearTimeout(cool);
			set(true);
		},
		closed: () => {
			open = Math.max(0, open - 1);
			clearTimeout(cool);
			if (open === 0) {
				cool = setTimeout(() => set(false), SKIP_DELAY_MS);
			}
		},
	};
})();

export interface TipPopoverProps {
	/** The button the sentence sits behind; it keeps its own name, classes and data attributes. */
	trigger: ReactElement;
	/** What the button shows. */
	children?: ReactNode;
	/** The sentence, or the panel of an (i) button. */
	content: ReactNode;
	/** Names the panel for a screen reader. */
	label: string;
	/**
	 * A panel of a few paragraphs, as an (i) button opens: about 38 characters a line and roomy.
	 * Without it the panel fits one sentence, at most 20rem wide.
	 */
	wide?: boolean;
	align?: "start" | "center" | "end";
	/** The trigger's description: the sentence again, for a screen reader, as the popup is portalled. */
	"aria-describedby"?: string;
}

/**
 * A sentence behind a control, for a pointer, a keyboard and a touch screen alike: the panel opens
 * on hover, on keyboard focus, and on a tap or a press, and Escape or a press outside closes it.
 * It is a popover and not a tooltip, because a tooltip never opens on touch and cannot hold a
 * paragraph. Focus stays on the button. Once one tip was open, the next opens without its delay
 * for {@link SKIP_DELAY_MS}, so a reader moving along a row of (i) buttons is not kept waiting. The (i) buttons, the standing badges and the trend chips
 * all use it.
 */
export function TipPopover({
	trigger,
	children,
	content,
	label,
	wide = false,
	align = "center",
	"aria-describedby": describedBy,
}: TipPopoverProps) {
	const [open, setOpen] = useState(false);
	const warm = useSyncExternalStore(warmth.subscribe, warmth.get, () => false);
	useEffect(() => {
		if (!open) {
			return;
		}
		warmth.opened();
		return () => warmth.closed();
	}, [open]);
	// What made the last press, and whether one is under way. A click does not say it in every
	// browser, its pointerdown does.
	const pointer = useRef("mouse");
	const pressing = useRef(false);
	// A button marked `data-tooltip-only` inside a row that opens on a mouse press
	// (`PracticeTableRow`, marked `data-row-press`): that press closes the panel and goes on to the
	// row. Outside such a row, as in a level's header, a press opens the panel. A tap opens the panel
	// and stops at the button, so the row stays shut; a key press is the row's to ignore.
	const rowPress = (event: Event) =>
		event instanceof MouseEvent &&
		event.detail > 0 &&
		pointer.current === "mouse" &&
		event.target instanceof Element &&
		event.target.closest("[data-row-press] [data-tooltip-only]") !== null;
	return (
		<Popover
			open={open}
			onOpenChange={(next, { reason, event }) => {
				setOpen(reason === "trigger-press" && rowPress(event) ? false : next);
			}}
		>
			<PopoverTrigger
				openOnHover
				delay={warm ? 0 : OPEN_DELAY_MS}
				closeDelay={150}
				onPointerDown={(event) => {
					pointer.current = event.pointerType;
					pressing.current = true;
				}}
				// A tap focuses the button after its pointerup, so the press ends with its click.
				onPointerCancel={() => {
					pressing.current = false;
				}}
				onBlur={() => {
					pressing.current = false;
				}}
				onClick={(event) => {
					pressing.current = false;
					if (event.detail > 0 && pointer.current !== "mouse") {
						event.stopPropagation();
					}
				}}
				// A pointer's press focuses the button too; only a keyboard's focus opens the panel.
				onFocus={(event) => {
					if (!pressing.current && event.currentTarget.matches(":focus-visible")) {
						setOpen(true);
					}
				}}
				render={trigger}
				aria-describedby={describedBy}
			>
				{children}
			</PopoverTrigger>
			<PopoverContent
				aria-label={label}
				initialFocus={false}
				finalFocus={false}
				align={align}
				// A short fade and no slide or zoom, so the panel is there at once.
				motion="fade"
				// A panel taller than a phone screen scrolls inside itself.
				className={
					wide
						? "max-h-(--available-height) w-[38ch] max-w-[calc(100vw-2rem)] overflow-y-auto p-4"
						: "w-max max-w-xs"
				}
			>
				{content}
			</PopoverContent>
		</Popover>
	);
}
