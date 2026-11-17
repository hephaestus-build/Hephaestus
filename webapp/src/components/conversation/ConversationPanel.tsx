import { ArrowLeftIcon, SquareArrowOutUpRight, X } from "lucide-react";
import { type ReactNode, useEffect, useRef } from "react";

import { HephaestusMark } from "@/components/brand/HephaestusMark";
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

export interface ConversationPanelProps {
	/** The conversation, or the thread pushed over it. */
	children: ReactNode;
	/** Opens what the panel shows on the conversation page, the open thread included. */
	onOpenInConversation: () => void;
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
 * The header's Ask Hephaestus button and its panel: the developer's one conversation with Hephaestus,
 * on any page. The button sits in the header, so the bottom-right corner stays free for the page and
 * for Get started. A `Drawer` rather than a `Popover`: the panel holds a conversation, so it is a
 * dialog, and scroll lock, focus and every dismissal come with it. A thread opens as a view pushed
 * over the conversation, because a side panel has no room for a second column.
 */
export function ConversationPanel({
	children,
	onOpenInConversation,
	thread,
	unread = false,
	open,
	onOpenChange,
}: ConversationPanelProps) {
	const titleRef = useRef<HTMLHeadingElement>(null);
	const focusTitle = thread?.focusTitle === true;
	useEffect(() => {
		if (focusTitle) {
			titleRef.current?.focus();
		}
	}, [focusTitle]);
	const name = unread ? "Ask Hephaestus, new answer" : "Ask Hephaestus";
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
			<Tooltip>
				<TooltipTrigger
					render={
						<DrawerTrigger
							render={<Button variant="ghost" size="icon" className="relative" aria-label={name} />}
						>
							<HephaestusMark size={24} pad={1} />
							{unread && (
								<span className="absolute top-0.5 right-0.5 size-2.5 rounded-full bg-highlight ring-2 ring-background" />
							)}
						</DrawerTrigger>
					}
				/>
				<TooltipContent>{name}</TooltipContent>
			</Tooltip>
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
									<HephaestusMark className="-mx-1.5" size={32} pad={4} />
									Ask Hephaestus <Badge variant="muted">AI</Badge>
								</>
							)}
						</DrawerTitle>
					</div>
					<DrawerDescription className="sr-only">
						Your conversation, the same as on the Ask Hephaestus page.
					</DrawerDescription>
					<TooltipProvider delay={0}>
						<div className="flex items-center gap-1">
							<Tooltip>
								<TooltipTrigger
									render={
										<Button
											variant="outline"
											size="icon"
											onClick={onOpenInConversation}
											aria-label="Open full page"
										/>
									}
								>
									<SquareArrowOutUpRight />
								</TooltipTrigger>
								<TooltipContent>Open full page</TooltipContent>
							</Tooltip>
							<Tooltip>
								<TooltipTrigger
									render={
										<DrawerClose
											render={
												<Button variant="outline" size="icon" aria-label="Close Ask Hephaestus" />
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
