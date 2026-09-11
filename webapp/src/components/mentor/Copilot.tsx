import { Sparkles, SquareArrowOutUpRight, SquarePen, X } from "lucide-react";
import { type RefObject, useEffect, useRef, useState } from "react";

import { cn } from "cn";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";

import { HephIcon } from "@/components/brand/HephIcon";

/** The `document.body` inline styles the scroll lock overwrites, kept so it can put them back. */
interface BodyScrollStyles {
	overflow: string;
	paddingRight: string;
	touchAction: string;
}

type BodyScrollRef = RefObject<BodyScrollStyles | null>;

/**
 * Freeze the page behind the popover, compensating for the scrollbar it removes so the layout does
 * not shift sideways. Re-entrant: a second lock while one is held is a no-op, so the styles captured
 * are always the page's own rather than the frozen ones.
 */
function lockBodyScroll(previous: BodyScrollRef): void {
	if (previous.current) return;
	const body = document.body;
	previous.current = {
		overflow: body.style.overflow,
		paddingRight: body.style.paddingRight,
		touchAction: body.style.touchAction,
	};
	const scrollBarWidth = window.innerWidth - document.documentElement.clientWidth;
	if (scrollBarWidth > 0) body.style.paddingRight = `${scrollBarWidth}px`;
	body.style.overflow = "hidden";
	body.style.touchAction = "none";
}

/** Restore what {@link lockBodyScroll} captured. A no-op unless a lock is held. */
function unlockBodyScroll(previous: BodyScrollRef): void {
	const prev = previous.current;
	if (!prev) return;
	const body = document.body;
	body.style.overflow = prev.overflow;
	body.style.paddingRight = prev.paddingRight;
	body.style.touchAction = prev.touchAction;
	previous.current = null;
}

export interface CopilotProps {
	/** Content to display in the popover (typically Chat component) */
	children: React.ReactNode;
	/** Whether the widget is initially open */
	defaultOpen?: boolean;
	/** Controlled open state */
	open?: boolean;
	/** Handler for open state changes */
	onOpenChange?: (open: boolean) => void;
	/** Optional CSS class name for the container */
	className?: string;
	/** Trigger starting a fresh chat session */
	onNewChat?: () => void;
	/** Open the current chat in the full mentor route */
	onOpenFullChat?: () => void;
	/** Whether there are any messages in the current conversation */
	hasMessages?: boolean;
}

export function Copilot({
	children,
	defaultOpen = false,
	open: controlledOpen,
	onOpenChange,
	className,
	onNewChat,
	onOpenFullChat,
	hasMessages = false,
}: CopilotProps) {
	const [internalOpen, setInternalOpen] = useState(defaultOpen);

	// Use controlled state if provided, otherwise use internal state
	const isOpen = controlledOpen ?? internalOpen;
	const setIsOpen = onOpenChange ?? setInternalOpen;

	// Handle trigger click
	const handleTriggerClick = () => {
		setIsOpen(!isOpen);
	};

	// Handle close via close button in content
	const handleClose = () => {
		setIsOpen(false);
	};

	const prevBodyStylesRef = useRef<BodyScrollStyles | null>(null);
	useEffect(() => {
		if (!isOpen) unlockBodyScroll(prevBodyStylesRef);
		return () => unlockBodyScroll(prevBodyStylesRef);
	}, [isOpen]);

	// Handle open change with click outside detection
	const handleOpenChange = (open: boolean, eventDetails?: { reason?: string }) => {
		// Close on click outside but prevent default behavior
		if (!open && eventDetails?.reason === "outside-press") {
			setIsOpen(false);
			return;
		}
		setIsOpen(open);
	};

	return (
		<div className={cn("fixed bottom-6 right-6 z-50", className)}>
			<Popover open={isOpen} onOpenChange={handleOpenChange}>
				<PopoverTrigger
					render={
						<Button
							onClick={handleTriggerClick}
							className="size-16 rounded-full shadow-lg hover:shadow-xl transition-all duration-200 active:scale-95"
							size="icon"
							aria-label="Open Heph, AI mentor"
						>
							<HephIcon size={56} pad={8} />
						</Button>
					}
				/>
				<PopoverContent
					className="p-0 h-[calc(100dvh-10rem)] rounded-2xl overflow-hidden shadow-2xl border-1 overscroll-contain w-[calc(100vw-3rem)] sm:w-[28rem] md:w-[32rem] max-w-[32rem]"
					side="top"
					align="end"
					alignOffset={0}
					sideOffset={8}
					onPointerEnter={() => lockBodyScroll(prevBodyStylesRef)}
					onPointerLeave={() => unlockBodyScroll(prevBodyStylesRef)}
					onTouchStart={() => lockBodyScroll(prevBodyStylesRef)}
					onTouchEnd={() => unlockBodyScroll(prevBodyStylesRef)}
					onWheelCapture={(e) => {
						e.stopPropagation();
					}}
				>
					<div className="flex flex-col w-full h-full bg-background rounded-2xl overflow-hidden">
						<div className="flex items-center justify-between p-2 pl-4 border-b">
							<h3 className="text-sm font-medium text-muted-foreground flex items-center gap-2">
								<HephIcon className="-mx-1.5" size={32} pad={4} />
								Heph{" "}
								<Badge variant="outline" className="text-muted-foreground">
									<Sparkles /> AI Mentor
								</Badge>
							</h3>
							<TooltipProvider delay={0}>
								<div className="flex items-center gap-1">
									{onOpenFullChat && (
										<Tooltip>
											<TooltipTrigger
												render={
													<Button
														variant="outline"
														size="icon"
														onClick={onOpenFullChat}
														aria-label="Open in mentor view"
														disabled={!hasMessages}
													/>
												}
											>
												<SquareArrowOutUpRight />
											</TooltipTrigger>
											<TooltipContent>Open in full screen</TooltipContent>
										</Tooltip>
									)}
									{onNewChat && (
										<Tooltip>
											<TooltipTrigger
												render={
													<Button
														variant="outline"
														size="icon"
														onClick={onNewChat}
														aria-label="Start new chat"
														disabled={!hasMessages}
													/>
												}
											>
												<SquarePen />
											</TooltipTrigger>
											<TooltipContent>New chat</TooltipContent>
										</Tooltip>
									)}
									<Tooltip>
										<TooltipTrigger
											render={
												<Button
													variant="outline"
													size="icon"
													onClick={handleClose}
													aria-label="Close Heph, AI mentor"
												/>
											}
										>
											<X />
										</TooltipTrigger>
										<TooltipContent>Close</TooltipContent>
									</Tooltip>
								</div>
							</TooltipProvider>
						</div>
						<div className="flex-1 min-h-0 overscroll-contain">{children}</div>
					</div>
				</PopoverContent>
			</Popover>
		</div>
	);
}
