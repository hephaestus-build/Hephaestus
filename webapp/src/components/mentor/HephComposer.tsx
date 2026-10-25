import { ArrowUpIcon, SquareIcon } from "lucide-react";
import { type ReactNode, type SubmitEvent, useRef, useState } from "react";
import { useMediaQuery } from "usehooks-ts";

import {
	InputGroup,
	InputGroupAddon,
	InputGroupButton,
	InputGroupTextarea,
} from "@/components/ui/input-group";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
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

/** A touch screen: its keyboard has no Shift, so Return there has to start a new line. */
const TOUCH = "(pointer: coarse)";

/**
 * Where the developer writes to Heph. Enter sends and Shift+Enter starts a new line. On a touch
 * screen, Return starts a new line and Send sends, as in a phone's messaging apps. An empty field
 * is one line tall and grows with what is written. The row below it holds the context and the
 * controls; where they do not fit side by side, as a long page name with Stop in a phone's panel,
 * the controls wrap below the context, and the context's name shortens before it leaves the row.
 */
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
	// Read through a subscription rather than by each render, and followed as it changes, such as
	// when a tablet gets a trackpad.
	const touch = useMediaQuery(TOUCH, { initializeWithValue: true });

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
					className="max-h-48 min-h-0"
					placeholder={placeholder}
					value={input}
					onChange={(event) => setInput(event.target.value)}
					enterKeyHint={touch ? "enter" : "send"}
					onKeyDown={(event) => {
						if (
							event.key === "Enter" &&
							!event.shiftKey &&
							!event.nativeEvent.isComposing &&
							!touch
						) {
							event.preventDefault();
							event.currentTarget.form?.requestSubmit();
						}
					}}
					// oxlint-disable-next-line jsx-a11y/no-autofocus -- Only where the composer is the one field of a surface the developer just opened: the Heph panel, or a thread they chose to reply in.
					autoFocus={focusOnMount}
				/>
				<InputGroupAddon align="block-end" className="flex-wrap">
					{rendersContent(context) && <div className="flex min-w-0 *:min-w-0">{context}</div>}
					<div className="ml-auto flex items-center gap-1">
						{stop && (
							<TooltipProvider delay={0}>
								<Tooltip>
									<TooltipTrigger
										render={
											<InputGroupButton
												variant="outline"
												size="icon-sm"
												aria-label={stop.label}
												onClick={stop.onStop}
											/>
										}
									>
										<SquareIcon fill="currentColor" strokeWidth={0} />
									</TooltipTrigger>
									<TooltipContent>{stop.label}</TooltipContent>
								</Tooltip>
							</TooltipProvider>
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
