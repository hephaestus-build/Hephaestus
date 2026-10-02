import { CopyIcon, PencilIcon, ThumbsDownIcon, ThumbsUpIcon } from "lucide-react";

import { cn } from "cn";
import type { ChatMessageVote } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";

interface MessageActionsProps {
	/** The words the copy action puts on the clipboard. */
	content: string;
	/** The reader's vote on this reply, if they cast one. */
	vote?: ChatMessageVote;
	onCopy: (text: string) => void;
	/** Offered on Heph's replies. */
	onVote?: (isUpvote: boolean) => void;
	/** Offered on the reader's own messages. */
	onEdit?: () => void;
}

/** The row under one message. It rests hidden until the message is hovered or a control in it is focused. */
export function MessageActions({ content, vote, onCopy, onVote, onEdit }: MessageActionsProps) {
	return (
		<TooltipProvider delay={0}>
			<div className="flex flex-row gap-0.5 opacity-0 transition-opacity group-hover/message:opacity-100 focus-within:opacity-100 [@media(hover:none)]:opacity-100">
				<Tooltip>
					<TooltipTrigger
						render={
							<Button
								aria-label="Copy message"
								className="pointer-coarse:w-10"
								variant="quiet"
								size="icon"
								onClick={() => onCopy(content)}
							/>
						}
					>
						<CopyIcon />
					</TooltipTrigger>
					<TooltipContent>Copy</TooltipContent>
				</Tooltip>

				{onEdit && (
					<Tooltip>
						<TooltipTrigger
							render={
								<Button
									aria-label="Edit message"
									className="pointer-coarse:w-10"
									variant="quiet"
									size="icon"
									onClick={onEdit}
								/>
							}
						>
							<PencilIcon />
						</TooltipTrigger>
						<TooltipContent>Edit message</TooltipContent>
					</Tooltip>
				)}

				{onVote && (
					<>
						<Tooltip>
							<TooltipTrigger
								render={
									<Button
										aria-label="Good response"
										aria-pressed={vote?.isUpvoted === true}
										className={cn(
											"text-muted-foreground hover:bg-provider-success-foreground/10 hover:text-provider-success-foreground pointer-coarse:w-10",
											{
												"text-provider-success-foreground": vote?.isUpvoted === true,
												"opacity-50 hover:opacity-100": vote?.isUpvoted === false,
											},
										)}
										variant="ghost"
										size="icon"
										onClick={() => onVote(true)}
									/>
								}
							>
								<ThumbsUpIcon />
							</TooltipTrigger>
							<TooltipContent>Good response</TooltipContent>
						</Tooltip>

						<Tooltip>
							<TooltipTrigger
								render={
									<Button
										aria-label="Bad response"
										aria-pressed={vote?.isUpvoted === false}
										className={cn(
											"text-muted-foreground hover:bg-provider-danger-foreground/10 hover:text-provider-danger-foreground pointer-coarse:w-10",
											{
												"text-provider-danger-foreground": vote?.isUpvoted === false,
												"opacity-50 hover:opacity-100": vote?.isUpvoted === true,
											},
										)}
										variant="ghost"
										size="icon"
										onClick={() => onVote(false)}
									/>
								}
							>
								<ThumbsDownIcon />
							</TooltipTrigger>
							<TooltipContent>Bad response</TooltipContent>
						</Tooltip>
					</>
				)}
			</div>
		</TooltipProvider>
	);
}
