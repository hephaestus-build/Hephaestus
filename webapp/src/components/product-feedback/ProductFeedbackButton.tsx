import { MessageSquarePlus } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Kbd } from "@/components/ui/kbd";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { FEEDBACK_SHORTCUT_ARIA, FEEDBACK_SHORTCUT_LABEL } from "@/lib/keyboard-shortcuts";

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
						aria-keyshortcuts={FEEDBACK_SHORTCUT_ARIA}
						onClick={onSendFeedback}
					/>
				}
			>
				<MessageSquarePlus data-icon="inline-start" aria-hidden />
				<span className="hidden sm:inline">Send feedback</span>
			</TooltipTrigger>
			<TooltipContent>
				<span className="flex items-center gap-2">
					Send feedback about Hephaestus <Kbd>{FEEDBACK_SHORTCUT_LABEL}</Kbd>
				</span>
			</TooltipContent>
		</Tooltip>
	);
}
