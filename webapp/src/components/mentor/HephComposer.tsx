import { ArrowUpIcon, SquareIcon } from "lucide-react";
import { type ReactNode, type SubmitEvent, useRef, useState } from "react";

import {
	InputGroup,
	InputGroupAddon,
	InputGroupButton,
	InputGroupTextarea,
} from "@/components/ui/input-group";
import { readDraft, writeDraft } from "@/lib/composer-drafts";
import { rendersContent } from "@/lib/react-node";
import { hasText } from "@/lib/text";

export interface HephComposerProps {
	/** Names the field, so the main composer and a thread's are told apart on one screen. */
	label: string;
	placeholder: string;
	onSend: (text: string) => void;
	/**
	 * Offered while Heph answers in this scope. Sending stays open: a message then waits as a
	 * follow-up. `label` names it apart from another scope's Stop.
	 */
	stop?: { label: string; onStop: () => void };
	/** What travels with the message, such as the page it was asked from. */
	context?: ReactNode;
	/** Takes focus on mount, for a composer in a panel the developer just opened. */
	focusOnMount?: boolean;
	/** Keeps unsent text under this key, such as one per thread, so returning to it restores the draft. */
	draftKey?: string;
}

/** Where the developer writes to Heph. Enter sends and Shift+Enter starts a new line. */
export function HephComposer({
	label,
	placeholder,
	onSend,
	stop,
	context,
	focusOnMount = false,
	draftKey,
}: HephComposerProps) {
	const textareaRef = useRef<HTMLTextAreaElement>(null);
	const [input, setDraft] = useState(() => (draftKey === undefined ? "" : readDraft(draftKey)));
	const setInput = (text: string) => {
		setDraft(text);
		if (draftKey !== undefined) {
			writeDraft(draftKey, text);
		}
	};
	const canSend = hasText(input.trim());

	const handleSubmit = (event: SubmitEvent<HTMLFormElement>) => {
		event.preventDefault();
		if (!canSend) {
			return;
		}
		onSend(input.trim());
		setInput("");
		// Back to the text after a click on Send, but not on a touch screen, where focusing the field
		// raises the keyboard over the answer the developer is about to read.
		if (window.matchMedia("(pointer: fine)").matches) {
			textareaRef.current?.focus();
		}
	};

	return (
		<form onSubmit={handleSubmit}>
			<InputGroup>
				<InputGroupTextarea
					ref={textareaRef}
					aria-label={label}
					className="max-h-48"
					placeholder={placeholder}
					value={input}
					onChange={(event) => setInput(event.target.value)}
					onKeyDown={(event) => {
						if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
							event.preventDefault();
							event.currentTarget.form?.requestSubmit();
						}
					}}
					// oxlint-disable-next-line jsx-a11y/no-autofocus -- Only where the composer is the one field of a surface the developer just opened: the Heph panel, or a thread they chose to reply in.
					autoFocus={focusOnMount}
				/>
				<InputGroupAddon align="block-end">
					{rendersContent(context) && <div className="min-w-0">{context}</div>}
					<div className="ml-auto flex items-center gap-1">
						{stop && (
							<InputGroupButton
								variant="outline"
								size="icon-sm"
								aria-label={stop.label}
								onClick={stop.onStop}
							>
								<SquareIcon fill="currentColor" strokeWidth={0} />
							</InputGroupButton>
						)}
						<InputGroupButton
							type="submit"
							variant="default"
							size="icon-sm"
							aria-label="Send message"
							disabled={!canSend}
						>
							<ArrowUpIcon />
						</InputGroupButton>
					</div>
				</InputGroupAddon>
			</InputGroup>
		</form>
	);
}
