import { ArrowLeftIcon, Sparkles, SquareArrowOutUpRight, X } from "lucide-react";
import { type ReactNode, useEffect, useRef } from "react";

import { cn } from "cn";
import { HephIcon } from "@/components/brand/HephIcon";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
	Drawer,
	DrawerBody,
	DrawerClose,
	DrawerContent,
	DrawerDescription,
	DrawerHeader,
	DrawerTitle,
	DrawerTrigger,
} from "@/components/ui/drawer";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";

export interface HephPanelProps {
	/** The conversation, or the thread pushed over it. */
	children: ReactNode;
	/** For the launcher's container. */
	className?: string;
	/** Opens what the panel shows on the mentor page, the open thread included. */
	onOpenInMentor: () => void;
	/** A message arrived that the developer has not seen, such as an answer that landed while the panel was closed. */
	unread?: boolean;
	/** Whether the panel is open, for a caller that closes it itself; left out, the panel keeps its own. */
	open?: boolean;
	onOpenChange?: (open: boolean) => void;
	/** Set while a thread is pushed over the conversation. Escape then goes back, not out. */
	thread?: {
		onBack: () => void;
		/** Moves focus to the title, for a thread opened to read rather than to reply in. */
		focusTitle: boolean;
	};
}

/**
 * Heph's launcher and its panel: the developer's one conversation with Heph, on any page. A
 * `Drawer` rather than a `Popover`: the panel holds a conversation, so it is a dialog, and scroll
 * lock, focus and every dismissal come with it. A thread opens as a view pushed over the
 * conversation, because a side panel has no room for a second column.
 */
export function HephPanel({
	children,
	className,
	onOpenInMentor,
	thread,
	unread = false,
	open,
	onOpenChange,
}: HephPanelProps) {
	const titleRef = useRef<HTMLHeadingElement>(null);
	const focusTitle = thread?.focusTitle === true;
	useEffect(() => {
		if (focusTitle) {
			titleRef.current?.focus();
		}
	}, [focusTitle]);
	return (
		<Drawer
			swipeDirection="right"
			open={open}
			onOpenChange={(next, details) => {
				// One level per Escape, as a stack of detail drawers closes: out of a pushed thread to
				// the conversation first, and only then out of the panel.
				if (!next && thread && details.reason === "escape-key") {
					details.cancel();
					thread.onBack();
					return;
				}
				onOpenChange?.(next);
			}}
		>
			<div className={cn("fixed right-6 bottom-6 z-50", className)}>
				<DrawerTrigger
					render={
						<Button
							shape="pill"
							size="icon"
							className="relative size-16 shadow-lg transition-all duration-200 hover:shadow-xl motion-safe:active:scale-95"
							aria-label={unread ? "Open Heph, AI mentor, new answer" : "Open Heph, AI mentor"}
						/>
					}
				>
					<HephIcon size={56} pad={8} />
					{unread && (
						<span className="absolute top-1 right-1 size-3.5 rounded-full bg-mentor ring-2 ring-background" />
					)}
				</DrawerTrigger>
			</div>
			<DrawerContent size="panel">
				<DrawerHeader className="flex-row items-center justify-between gap-3 px-4 py-2">
					<div className="flex min-w-0 items-center gap-2">
						{thread && (
							<Button
								variant="ghost"
								size="icon"
								aria-label="Back to the conversation"
								onClick={thread.onBack}
							>
								<ArrowLeftIcon />
							</Button>
						)}
						<DrawerTitle ref={titleRef} tabIndex={-1} className="flex items-center gap-2">
							{thread ? (
								"Thread"
							) : (
								<>
									<HephIcon className="-mx-1.5" size={32} pad={4} />
									Heph{" "}
									<Badge variant="muted">
										<Sparkles /> AI mentor
									</Badge>
								</>
							)}
						</DrawerTitle>
					</div>
					<DrawerDescription className="sr-only">
						Your conversation with Heph, the same as on the Heph page.
					</DrawerDescription>
					<TooltipProvider delay={0}>
						<div className="flex items-center gap-1">
							<Tooltip>
								<TooltipTrigger
									render={
										<Button
											variant="outline"
											size="icon"
											onClick={onOpenInMentor}
											aria-label="Open the Heph page"
										/>
									}
								>
									<SquareArrowOutUpRight />
								</TooltipTrigger>
								<TooltipContent>Open the Heph page</TooltipContent>
							</Tooltip>
							<Tooltip>
								<TooltipTrigger
									render={
										<DrawerClose
											render={
												<Button variant="outline" size="icon" aria-label="Close Heph, AI mentor" />
											}
										/>
									}
								>
									<X />
								</TooltipTrigger>
								<TooltipContent>Close</TooltipContent>
							</Tooltip>
						</div>
					</TooltipProvider>
				</DrawerHeader>
				{/* Not a tab stop of its own: the conversation inside is the scroll region, and it is one. */}
				<DrawerBody className="flex flex-col p-0" tabIndex={-1}>
					{children}
				</DrawerBody>
			</DrawerContent>
		</Drawer>
	);
}
