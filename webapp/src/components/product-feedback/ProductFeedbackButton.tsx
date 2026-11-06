import { MessageSquarePlus } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

export interface ProductFeedbackButtonProps {
	onSendFeedback: () => void;
}

/** The same action opens the composer at every viewport width. */
export function ProductFeedbackButton({ onSendFeedback }: ProductFeedbackButtonProps) {
	return (
		<Tooltip>
			<TooltipTrigger
				render={
					<Button
						variant="ghost"
						className="w-8 sm:w-auto"
						aria-label="Send feedback about Hephaestus"
						onClick={onSendFeedback}
					/>
				}
			>
				<MessageSquarePlus data-icon="inline-start" aria-hidden />
				<span className="hidden sm:inline">Send feedback</span>
			</TooltipTrigger>
			<TooltipContent>Send feedback about Hephaestus</TooltipContent>
		</Tooltip>
	);
}
