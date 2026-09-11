import { type ChangeEvent, type KeyboardEvent, useEffect, useRef, useState } from "react";

import { cn } from "cn";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";

interface MessageEditorProps {
	initialContent: string;
	isSubmitting?: boolean;
	placeholder?: string;
	onCancel: () => void;
	onSend: (content: string) => void;
	className?: string;
}

export function MessageEditor({
	initialContent,
	isSubmitting = false,
	placeholder = "",
	onCancel,
	onSend,
	className,
}: MessageEditorProps) {
	const [draftContent, setDraftContent] = useState(initialContent);
	const textareaRef = useRef<HTMLTextAreaElement>(null);

	const adjustHeight = () => {
		if (textareaRef.current) {
			textareaRef.current.style.height = "auto";
			textareaRef.current.style.height = `${textareaRef.current.scrollHeight + 2}px`;
		}
	};

	useEffect(() => {
		const textarea = textareaRef.current;
		if (!textarea) return;
		textarea.style.height = "auto";
		textarea.style.height = `${textarea.scrollHeight + 2}px`;
	}, []);

	const handleInput = (event: ChangeEvent<HTMLTextAreaElement>) => {
		setDraftContent(event.target.value);
		adjustHeight();
	};

	const handleSend = () => {
		onSend(draftContent);
	};

	const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
		if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) {
			event.preventDefault();
			handleSend();
		}
		if (event.key === "Escape") {
			event.preventDefault();
			onCancel();
		}
	};

	const hasChanges = draftContent !== initialContent;
	const canSend = draftContent.trim().length > 0 && hasChanges && !isSubmitting;

	return (
		<div
			className={cn(
				"border-input placeholder:text-muted-foreground focus-within:border-ring focus-within:ring-ring/50 aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 aria-invalid:border-destructive dark:bg-input flex field-sizing-content min-h-16 w-full rounded-xl border bg-white px-3 py-2 text-base shadow-xs transition-[color,box-shadow] outline-none focus-within:ring-[3px] disabled:cursor-not-allowed disabled:opacity-50 md:text-sm",
				"flex-col gap-1",
				className,
			)}
		>
			<div className="flex-1">
				<Textarea
					ref={textareaRef}
					aria-label="Edit message"
					className="border-0 bg-transparent outline-none overflow-hidden resize-none !text-base w-full p-0 shadow-none focus-visible:ring-0 min-h-0"
					placeholder={placeholder}
					value={draftContent}
					onChange={handleInput}
					onKeyDown={handleKeyDown}
					disabled={isSubmitting}
					// oxlint-disable-next-line jsx-a11y/no-autofocus -- This box only replaces a message once the reader presses Edit on it, so the caret belongs in the text they asked to change.
					autoFocus
				/>
			</div>

			<div className="flex gap-2 justify-end">
				<Button
					variant="outline"
					className="rounded-full h-8 px-3"
					onClick={onCancel}
					disabled={isSubmitting}
					size="sm"
				>
					Cancel
				</Button>
				<Button
					variant="default"
					className="rounded-full h-8 px-3"
					disabled={!canSend}
					onClick={handleSend}
					size="sm"
				>
					{isSubmitting ? "Sending..." : "Send"}
				</Button>
			</div>
		</div>
	);
}
