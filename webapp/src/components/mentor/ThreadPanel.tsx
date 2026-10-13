import { MessageSquareOffIcon, XIcon } from "lucide-react";
import { type KeyboardEvent, type ReactNode, useEffect, useId, useRef, useState } from "react";

import { useArrived } from "@/components/layout/detail-drawer/use-arrived";
import { Button } from "@/components/ui/button";
import {
	Drawer,
	DrawerBody,
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
import { useIsMobile } from "@/hooks/use-mobile";

export interface ThreadPanelProps {
	/** The panel's `id`, which the summary button that opened it controls. */
	id: string;
	/** Moves focus to the heading on open, for a thread opened to read rather than to reply in. */
	focusHeading?: boolean;
	onClose: () => void;
	/** The thread: its parent message, replies and composer, or the state that stands in for them. */
	children: ReactNode;
}

/**
 * One thread beside the conversation. From `md` it is a side column, not a dialog: the
 * conversation stays readable and usable next to it, so the thread has its own log and Escape or
 * Close hands focus back to the control that opened it. Below `md` there is no room beside, so it
 * is a drawer over the conversation.
 */
export function ThreadPanel({ id, focusHeading = false, onClose, children }: ThreadPanelProps) {
	const isMobile = useIsMobile();
	return isMobile ? (
		<ThreadDrawer id={id} onClose={onClose}>
			{children}
		</ThreadDrawer>
	) : (
		<ThreadAside id={id} focusHeading={focusHeading} onClose={onClose}>
			{children}
		</ThreadAside>
	);
}

function CloseButton({ onClick }: { onClick?: () => void }) {
	return (
		<Button variant="ghost" size="icon" aria-label="Close thread" onClick={onClick}>
			<XIcon />
		</Button>
	);
}

function ThreadAside({ id, focusHeading, onClose, children }: ThreadPanelProps) {
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
					className="text-base font-semibold outline-none"
				>
					Thread
				</h2>
				<CloseButton onClick={close} />
			</div>
			<div className="flex min-h-0 flex-1 flex-col">{children}</div>
		</aside>
	);
}

function ThreadDrawer({ id, onClose, children }: Omit<ThreadPanelProps, "focusHeading">) {
	const arrived = useArrived();
	return (
		<Drawer
			open={arrived}
			swipeDirection="right"
			onOpenChange={(open) => {
				if (!open) {
					onClose();
				}
			}}
		>
			<DrawerContent id={id} size="panel">
				<DrawerHeader className="flex-row items-center justify-between gap-2 px-4 py-2">
					<DrawerTitle>Thread</DrawerTitle>
					<CloseButton onClick={onClose} />
				</DrawerHeader>
				{/* Not a tab stop of its own: the thread inside is the scroll region, and it is one. */}
				<DrawerBody className="flex flex-col p-0" tabIndex={-1}>
					{children}
				</DrawerBody>
			</DrawerContent>
		</Drawer>
	);
}

/** Stands in for a thread whose parent message was deleted, or never was the developer's. */
export function ThreadGone() {
	return (
		<Empty>
			<EmptyHeader>
				<EmptyMedia variant="icon">
					<MessageSquareOffIcon />
				</EmptyMedia>
				<EmptyTitle>This thread is gone</EmptyTitle>
				<EmptyDescription>The message it replied to was deleted.</EmptyDescription>
			</EmptyHeader>
		</Empty>
	);
}
