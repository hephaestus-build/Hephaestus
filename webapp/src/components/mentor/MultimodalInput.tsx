import { ArrowDown, ArrowUp, Paperclip, Square } from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { type ChangeEvent, type RefObject, useEffect, useRef, useState } from "react";
import { toast } from "sonner";
import { useWindowSize } from "usehooks-ts";

import { cn } from "cn";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import type { Attachment } from "@/lib/types";

import { PreviewAttachment } from "./PreviewAttachment";

export interface MultimodalInputProps {
	status: "ready" | "submitted" | "error";
	onStop: () => void;
	attachments: Array<Attachment>;
	onAttachmentsChange: (attachments: Array<Attachment>) => void;
	onFileUpload: (files: File[]) => Promise<Array<Attachment | undefined>>;
	onSubmit: (data: { text: string; attachments: Array<Attachment> }) => void;
	className?: string;
	placeholder?: string;
	initialInput?: string;
	readonly?: boolean;
	disableAttachments?: boolean;
	isAtBottom?: boolean;
	scrollToBottom?: () => void;
	isCurrentVersion?: boolean;
}

export function MultimodalInput({
	status,
	onStop,
	attachments,
	onAttachmentsChange,
	onFileUpload,
	onSubmit,
	className,
	placeholder = "Send a message...",
	initialInput = "",
	readonly = false,
	disableAttachments = false,
	isAtBottom = true,
	scrollToBottom,
	isCurrentVersion = true,
}: MultimodalInputProps) {
	const textareaRef = useRef<HTMLTextAreaElement>(null);
	const fileInputRef = useRef<HTMLInputElement>(null);
	const { width } = useWindowSize();
	const [uploadQueue, setUploadQueue] = useState<Array<string>>([]);

	const [input, setInput] = useState(initialInput);

	const resetHeight = () => {
		if (textareaRef.current) {
			textareaRef.current.style.height = "auto";
		}
	};

	useEffect(() => {
		const textarea = textareaRef.current;
		if (!textarea) return;
		textarea.style.height = "auto";
		textarea.style.height = `${textarea.scrollHeight + 2}px`;
	}, []);

	const handleInput = (event: ChangeEvent<HTMLTextAreaElement>) => {
		setInput(event.target.value);
		event.currentTarget.style.height = "auto";
		event.currentTarget.style.height = `${event.currentTarget.scrollHeight + 2}px`;
	};

	const submitForm = () => {
		onSubmit({
			text: input,
			attachments,
		});

		setInput("");
		resetHeight();

		if (width && width > 768) {
			textareaRef.current?.focus();
		}
	};

	const handleFileChange = async (event: ChangeEvent<HTMLInputElement>) => {
		const files = Array.from(event.target.files ?? []);
		if (files.length === 0) return;

		setUploadQueue(files.map((file) => file.name));

		try {
			const uploadedAttachments = await onFileUpload(files);
			const successfullyUploadedAttachments = uploadedAttachments.filter(
				(attachment) => attachment !== undefined,
			);

			onAttachmentsChange([...attachments, ...successfullyUploadedAttachments]);
		} catch {
			// `finally` empties the queue either way, so without this the files vanish with no symptom.
			toast.error("Could not attach those files. Please try again.");
		} finally {
			setUploadQueue([]);
		}
	};

	useEffect(() => {
		if (status === "submitted" && scrollToBottom) {
			scrollToBottom();
		}
	}, [status, scrollToBottom]);

	const canSubmit = input.trim().length > 0 && uploadQueue.length === 0 && !readonly;

	return (
		<div className="relative w-full flex flex-col gap-4">
			<AnimatePresence>
				{!isAtBottom && isCurrentVersion && (
					<motion.div
						initial={{ opacity: 0, y: 10 }}
						animate={{ opacity: 1, y: 0 }}
						exit={{ opacity: 0, y: 10 }}
						transition={{ type: "spring", stiffness: 300, damping: 20 }}
						className="absolute left-1/2 -top-12 -translate-x-1/2 z-[95] backdrop-blur-sm rounded-full"
					>
						<Button
							aria-label="Scroll to latest message"
							className="rounded-full bg-background/80 dark:bg-background/80 border-border/50 shadow-lg hover:bg-background/90 dark:hover:bg-background/90"
							size="icon"
							variant="outline"
							onClick={(event) => {
								event.preventDefault();
								scrollToBottom?.();
							}}
						>
							<ArrowDown />
						</Button>
					</motion.div>
				)}
			</AnimatePresence>

			{!disableAttachments && (
				<input
					type="file"
					className="fixed -top-4 -left-4 size-0.5 opacity-0 pointer-events-none"
					ref={fileInputRef}
					multiple
					aria-label="Attach files"
					onChange={(event) => void handleFileChange(event)}
					tabIndex={-1}
				/>
			)}

			{(attachments.length > 0 || uploadQueue.length > 0) && (
				<div className="flex flex-row gap-2 overflow-x-scroll items-end">
					{attachments.map((attachment) => (
						<PreviewAttachment key={attachment.url} attachment={attachment} />
					))}

					{uploadQueue.map((filename) => (
						<PreviewAttachment
							key={filename}
							attachment={{
								url: "",
								name: filename,
								contentType: "",
							}}
							isUploading={true}
						/>
					))}
				</div>
			)}

			<div
				className={cn(
					"border-input placeholder:text-muted-foreground focus-within:border-ring focus-within:ring-ring/50 aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 aria-invalid:border-destructive dark:bg-input/30 flex field-sizing-content min-h-16 w-full rounded-xl border bg-transparent px-3 py-2 text-base shadow-xs transition-[color,box-shadow] outline-none focus-within:ring-[3px] disabled:cursor-not-allowed disabled:opacity-50 md:text-sm",
					"flex-col gap-1",
					readonly && "cursor-not-allowed opacity-60",
					className,
				)}
			>
				<div className="flex-1">
					<Textarea
						ref={textareaRef}
						aria-label="Message"
						placeholder={placeholder}
						value={input}
						onChange={handleInput}
						readOnly={readonly}
						className="border-0 bg-transparent outline-none overflow-hidden resize-none !text-base w-full p-0 shadow-none focus-visible:ring-0 min-h-0"
						rows={2}
						// oxlint-disable-next-line jsx-a11y/no-autofocus -- The composer is the only writable control on every surface that mounts it, each reached in order to type. A read-only replay takes no focus.
						autoFocus={!readonly}
						onKeyDown={(event) => {
							if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
								event.preventDefault();

								if (status !== "ready") {
									return;
								}

								if (canSubmit) {
									submitForm();
								}
							}
						}}
					/>
				</div>

				<div className="flex gap-2 justify-between">
					<div className="flex gap-2">
						{!disableAttachments && (
							<AttachmentsButton fileInputRef={fileInputRef} status={status} readonly={readonly} />
						)}
					</div>
					<div>
						{status === "submitted" ? (
							<StopButton onStop={onStop} />
						) : (
							<SendButton onSubmit={submitForm} disabled={!canSubmit} />
						)}
					</div>
				</div>
			</div>
		</div>
	);
}

function AttachmentsButton({
	fileInputRef,
	status,
	readonly,
}: {
	fileInputRef: RefObject<HTMLInputElement | null>;
	status: "ready" | "submitted" | "error";
	readonly: boolean;
}) {
	return (
		<Button
			aria-label="Attach a file"
			className="rounded-md rounded-bl-lg p-[7px] dark:border-zinc-700 hover:dark:bg-zinc-900 hover:bg-zinc-200"
			onClick={(event) => {
				event.preventDefault();
				fileInputRef.current?.click();
			}}
			disabled={status !== "ready" || readonly}
			variant="ghost"
			size="icon"
		>
			<Paperclip size={14} />
		</Button>
	);
}

function StopButton({ onStop }: { onStop: () => void }) {
	return (
		<Button
			aria-label="Stop generating"
			className="rounded-full p-1.5 border dark:border-zinc-600"
			onClick={(event) => {
				event.preventDefault();
				onStop();
			}}
			size="icon"
		>
			<Square fill="currentColor" strokeWidth={0} />
		</Button>
	);
}

function SendButton({ onSubmit, disabled }: { onSubmit: () => void; disabled: boolean }) {
	return (
		<Button
			aria-label="Send message"
			className="rounded-full p-1.5 border dark:border-zinc-600"
			onClick={(event) => {
				event.preventDefault();
				onSubmit();
			}}
			disabled={disabled}
			size="icon"
		>
			<ArrowUp size={14} strokeWidth={3} />
		</Button>
	);
}
