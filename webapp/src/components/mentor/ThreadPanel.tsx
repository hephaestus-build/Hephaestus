import { MessageSquareOffIcon, XIcon } from "lucide-react";
import {
	type ComponentProps,
	type KeyboardEvent,
	type ReactNode,
	useEffect,
	useId,
	useRef,
	useState,
} from "react";

import { cn } from "cn";
import { FOCUS_RING } from "@/components/common/focus";
import { useArrived } from "@/components/layout/detail-drawer/use-arrived";
import { Button } from "@/components/ui/button";
import {
	Drawer,
	DrawerBody,
	DrawerClose,
	DrawerContent,
	DrawerHeader,
	DrawerTitle,
} from "@/components/ui/drawer";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";

export interface ThreadPanelProps {
	/** The panel's `id`, which the summary button that opened it controls. */
	id: string;
	/** Moves focus to the heading on open, for a thread opened to read rather than to reply in. */
	focusHeading?: boolean;
	/**
	 * The width in `rem` of the region the conversation and the thread share, as the page measures
	 * it; undefined until it has. It already leaves out the sidebar and every other part of the page.
	 * In `rem`, the unit the column and the conversation are sized in, so larger text in the browser
	 * needs a wider region for the same column beside the same readable conversation.
	 */
	availableRem: number | undefined;
	onClose: () => void;
	/** The thread: its parent message, replies and composer, or the state that stands in for them. */
	children: ReactNode;
}

/** The side column's `w-[26rem]`, and the narrowest conversation that stays readable beside it. */
const COLUMN_REM = 26;
const CONVERSATION_MIN_REM = 24;

/**
 * The CSS pixels of one `rem`: the root font size, which the reader's text-size setting in the
 * browser changes. NaN where it cannot be read, which leaves a thread in the drawer.
 */
export function pixelsPerRem(): number {
	return Number.parseFloat(getComputedStyle(document.documentElement).fontSize);
}

/**
 * One thread beside the conversation. Where the region leaves the conversation at least
 * {@link CONVERSATION_MIN_REM} beside the column, it is a side column, not a dialog: the
 * conversation stays readable and usable next to it, so the thread has its own log and Escape or
 * Close hands focus back to the control that opened it. With less room, or before the region is
 * measured, it is a drawer over the conversation.
 */
export function ThreadPanel({
	id,
	focusHeading = false,
	availableRem,
	onClose,
	children,
}: ThreadPanelProps) {
	const beside = availableRem !== undefined && availableRem >= COLUMN_REM + CONVERSATION_MIN_REM;
	return beside ? (
		<ThreadAside id={id} focusHeading={focusHeading} onClose={onClose}>
			{children}
		</ThreadAside>
	) : (
		<ThreadDrawer id={id} onClose={onClose}>
			{children}
		</ThreadDrawer>
	);
}

/** Takes the props of `DrawerClose` when it renders it, so the drawer's own close runs. */
function CloseButton(props: ComponentProps<typeof Button>) {
	return (
		<Button variant="ghost" size="icon" aria-label="Close thread" {...props}>
			<XIcon />
		</Button>
	);
}

function ThreadAside({
	id,
	focusHeading,
	onClose,
	children,
}: Omit<ThreadPanelProps, "availableRem">) {
	const headingId = useId();
	const headingRef = useRef<HTMLHeadingElement>(null);
	// The control that opened the thread, read once when the panel mounts.
	const [opener] = useState(() =>
		document.activeElement instanceof HTMLElement ? document.activeElement : null,
	);

	useEffect(() => {
		if (focusHeading === true) {
			headingRef.current?.focus();
		}
	}, [focusHeading]);

	const close = () => {
		onClose();
		opener?.focus();
	};

	const handleKeyDown = (event: KeyboardEvent<HTMLElement>) => {
		if (event.key === "Escape" && !event.defaultPrevented) {
			event.preventDefault();
			close();
		}
	};

	return (
		// oxlint-disable-next-line jsx-a11y/no-noninteractive-element-interactions -- The landmark listens for a key its controls do not handle; it takes no focus or clicks itself.
		<aside
			id={id}
			aria-labelledby={headingId}
			className="flex min-h-0 w-[26rem] shrink-0 flex-col border-l border-border"
			onKeyDown={handleKeyDown}
		>
			<div className="flex items-center justify-between gap-2 border-b border-border px-4 py-2">
				<h2
					id={headingId}
					ref={headingRef}
					tabIndex={-1}
					className={cn("rounded-sm text-base font-semibold", FOCUS_RING)}
				>
					Thread
				</h2>
				<CloseButton onClick={close} />
			</div>
			<div className="flex min-h-0 flex-1 flex-col">{children}</div>
		</aside>
	);
}

function ThreadDrawer({
	id,
	onClose,
	children,
}: Omit<ThreadPanelProps, "focusHeading" | "availableRem">) {
	const arrived = useArrived();
	// A cached thread can focus its reply field in the mount commit, before the drawer captures it.
	const [opener] = useState(() =>
		document.activeElement instanceof HTMLElement && document.activeElement !== document.body
			? document.activeElement
			: null,
	);
	// Every way out (Close, Escape, a press on the page, a swipe) shuts the drawer first, and the
	// thread leaves the address once the drawer has left, as a detail drawer level does. Leaving the
	// address first would unmount the drawer mid-exit, before it hands focus back.
	const [closing, setClosing] = useState(false);
	return (
		<Drawer
			open={arrived && !closing}
			swipeDirection="right"
			onOpenChange={(open) => {
				if (!open) {
					setClosing(true);
				}
			}}
			onOpenChangeComplete={(open) => {
				if (!open && closing) {
					onClose();
				}
			}}
		>
			<DrawerContent id={id} size="panel" finalFocus={() => opener}>
				<DrawerHeader className="flex-row items-center justify-between gap-2 px-4 py-2">
					<DrawerTitle>Thread</DrawerTitle>
					<DrawerClose render={<CloseButton />} />
				</DrawerHeader>
				{/* Not a tab stop of its own: the thread inside is the scroll region, and it is one. */}
				<DrawerBody className="flex flex-col p-0" tabIndex={-1}>
					{children}
				</DrawerBody>
			</DrawerContent>
		</Drawer>
	);
}

/**
 * Stands in for a thread the server does not know as the developer's, such as one from a wrong
 * link. A thread whose message was deleted is not this: it still opens, with its replies.
 */
export function ThreadGone() {
	return (
		<Empty>
			<EmptyHeader>
				<EmptyMedia variant="icon">
					<MessageSquareOffIcon />
				</EmptyMedia>
				<EmptyTitle>We could not find this thread</EmptyTitle>
				<EmptyDescription>The link may be wrong, or the thread is not yours.</EmptyDescription>
			</EmptyHeader>
		</Empty>
	);
}
