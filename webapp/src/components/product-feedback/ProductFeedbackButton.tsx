import { Link } from "@tanstack/react-router";
import { ChevronDown, History, MessageSquarePlus } from "lucide-react";

import { Button } from "@/components/ui/button";
import { ButtonGroup } from "@/components/ui/button-group";
import {
	DropdownMenu,
	DropdownMenuContent,
	DropdownMenuGroup,
	DropdownMenuItem,
	DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useIsMobile } from "@/hooks/use-mobile";

export interface ProductFeedbackButtonProps {
	onSendFeedback: () => void;
	workspaceSlug?: string;
}

/** Send opens the composer directly; the adjacent menu keeps report history within reach. */
export function ProductFeedbackButton({
	onSendFeedback,
	workspaceSlug,
}: ProductFeedbackButtonProps) {
	const compact = useIsMobile();
	return (
		<ButtonGroup aria-label="Feedback about Hephaestus">
			{!compact && (
				<Button
					variant="outline"
					className="w-8 sm:w-auto"
					aria-label="Send feedback about Hephaestus"
					onClick={onSendFeedback}
				>
					<MessageSquarePlus data-icon="inline-start" aria-hidden />
					<span className="hidden sm:inline">Send feedback</span>
				</Button>
			)}
			<DropdownMenu>
				<DropdownMenuTrigger
					aria-label="Feedback options"
					render={<Button variant="outline" size="icon" />}
				>
					{compact ? <MessageSquarePlus aria-hidden /> : <ChevronDown aria-hidden />}
				</DropdownMenuTrigger>
				<DropdownMenuContent align="end">
					<DropdownMenuGroup>
						{compact && (
							<DropdownMenuItem onClick={onSendFeedback}>
								<MessageSquarePlus />
								Send feedback
							</DropdownMenuItem>
						)}
						<DropdownMenuItem
							render={<Link to="/product-feedback" search={{ chromeWorkspace: workspaceSlug }} />}
						>
							<History />
							Your reports
						</DropdownMenuItem>
					</DropdownMenuGroup>
				</DropdownMenuContent>
			</DropdownMenu>
		</ButtonGroup>
	);
}
