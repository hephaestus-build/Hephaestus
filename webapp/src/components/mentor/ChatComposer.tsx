import { ArrowUpIcon, SquareIcon } from "lucide-react";
import { type SubmitEvent, useRef, useState } from "react";

import {
	InputGroup,
	InputGroupAddon,
	InputGroupButton,
	InputGroupTextarea,
} from "@/components/ui/input-group";
import { hasText } from "@/lib/text";

export interface ChatComposerProps {
	/** Heph is answering: the composer offers to stop instead of to send. */
	busy: boolean;
	onSubmit: (text: string) => void;
	onStop: () => void;
	placeholder?: string;
}

/** Where the reader writes to Heph. Enter sends and Shift+Enter starts a new line. */
export function ChatComposer({
	busy,
	onSubmit,
	onStop,
	placeholder = "Ask me anything…",
}: ChatComposerProps) {
	const textareaRef = useRef<HTMLTextAreaElement>(null);
	const [input, setInput] = useState("");
	const canSubmit = hasText(input.trim()) && !busy;

	const handleSubmit = (event: SubmitEvent<HTMLFormElement>) => {
		event.preventDefault();
		if (!canSubmit) {
			return;
		}
		onSubmit(input);
		setInput("");
		// Back to the text after a click on Send, but not on a touch screen, where focusing the field
		// raises the keyboard over the reply the reader is about to read.
		if (window.matchMedia("(pointer: fine)").matches) {
			textareaRef.current?.focus();
		}
	};

	return (
		<form onSubmit={handleSubmit}>
			<InputGroup>
				<InputGroupTextarea
					ref={textareaRef}
					aria-label="Message"
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
					// oxlint-disable-next-line jsx-a11y/no-autofocus -- The composer is the only writable control on every surface that mounts it, each reached in order to type.
					autoFocus
				/>
				<InputGroupAddon align="block-end">
					{busy ? (
						<InputGroupButton
							variant="default"
							size="icon-sm"
							className="ml-auto"
							aria-label="Stop generating"
							onClick={onStop}
						>
							<SquareIcon fill="currentColor" strokeWidth={0} />
						</InputGroupButton>
					) : (
						<InputGroupButton
							type="submit"
							variant="default"
							size="icon-sm"
							className="ml-auto"
							aria-label="Send message"
							disabled={!canSubmit}
						>
							<ArrowUpIcon />
						</InputGroupButton>
					)}
				</InputGroupAddon>
			</InputGroup>
		</form>
	);
}
