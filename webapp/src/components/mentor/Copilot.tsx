import { Sparkles, SquareArrowOutUpRight, SquarePen, X } from "lucide-react";

import { cn } from "cn";
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

import { HephIcon } from "@/components/brand/HephIcon";

export interface CopilotProps {
	/** Content to display in the panel (typically Chat component) */
	children: React.ReactNode;
	/** Optional CSS class name for the launcher's container */
	className?: string;
	/** Trigger starting a fresh chat session */
	onNewChat?: () => void;
	/** Open the current chat in the full mentor route */
	onOpenFullChat?: () => void;
	/** Whether there are any messages in the current conversation */
	hasMessages?: boolean;
}

/**
 * The mentor's launcher and its panel. A `Drawer` rather than a `Popover`: the panel holds a
 * conversation, so it is a dialog, and scroll lock, focus and every dismissal come with it.
 */
export function Copilot({
	children,
	className,
	onNewChat,
	onOpenFullChat,
	hasMessages = false,
}: CopilotProps) {
	return (
		<Drawer swipeDirection="right">
			<div className={cn("fixed right-6 bottom-6 z-50", className)}>
				<DrawerTrigger
					render={
						<Button
							shape="pill"
							size="icon"
							className="size-16 shadow-lg transition-all duration-200 hover:shadow-xl active:scale-95"
							aria-label="Open Heph, AI mentor"
						/>
					}
				>
					{/* The class, not the `size` prop: Button sizes a child svg to `size-4` unless a `size-*` class says otherwise. */}
					<HephIcon className="size-14" pad={8} />
				</DrawerTrigger>
			</div>
			<DrawerContent size="panel">
				<DrawerHeader className="flex-row items-center justify-between gap-3 px-4 py-2">
					<DrawerTitle className="flex items-center gap-2">
						<HephIcon className="-mx-1.5" size={32} pad={4} />
						Heph{" "}
						<Badge variant="muted">
							<Sparkles /> AI Mentor
						</Badge>
					</DrawerTitle>
					<DrawerDescription className="sr-only">
						Ask about the feedback on your work, or anything else.
					</DrawerDescription>
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
				<DrawerBody className="flex flex-col p-0">{children}</DrawerBody>
			</DrawerContent>
		</Drawer>
	);
}
