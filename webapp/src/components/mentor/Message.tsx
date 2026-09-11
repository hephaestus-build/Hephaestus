import { isStaticToolUIPart } from "ai";
import { AnimatePresence, motion } from "motion/react";
import { type InputHTMLAttributes, useState } from "react";
import { Streamdown } from "streamdown";

import type { ChatMessageVote } from "@/api/types.gen";
import { MarkdownCode } from "@/components/common/MarkdownCode";
import type { ChatMessage, ChatTools } from "@/lib/types";
import { cn } from "@/lib/utils";

import { MentorAvatar } from "./MentorAvatar";
import { MessageActions } from "./MessageActions";
import { MessageEditor } from "./MessageEditor";
import { PreviewAttachment } from "./PreviewAttachment";
import type { PartRendererMap } from "./renderers/types";
import { sanitizeMessageText } from "./sanitize-message-text";

export interface MessageProps {
	message: ChatMessage;
	vote?: ChatMessageVote;
	isLoading?: boolean;
	readonly?: boolean;
	variant?: "default" | "artifact";
	onMessageEdit?: (messageId: string, newContent: string) => void;
	onCopy?: (content: string) => void;
	onVote?: (messageId: string, isUpvote: boolean) => void;
	className?: string;
	initialEditMode?: boolean;
	partRenderers?: PartRendererMap;
}

function MarkdownTaskCheckbox(props: InputHTMLAttributes<HTMLInputElement>) {
	return <input {...props} aria-label={props.checked ? "Completed task" : "Incomplete task"} />;
}

const MESSAGE_MARKDOWN_COMPONENTS = {
	code: MarkdownCode,
	input: MarkdownTaskCheckbox,
};

export function PreviewMessage({
	message,
	vote,
	isLoading = false,
	readonly = false,
	variant = "default",
	onMessageEdit,
	onCopy,
	onVote,
	className,
	initialEditMode = false,
	partRenderers,
}: MessageProps) {
	const [mode, setMode] = useState<"view" | "edit">(initialEditMode ? "edit" : "view");

	const attachmentsFromMessage = message.parts.filter((part) => part.type === "file");

	const isArtifact = variant === "artifact";

	return (
		<AnimatePresence>
			<motion.div
				className={cn(
					"w-full max-w-3xl px-4 group/message",
					{
						"pl-16": isArtifact && message.role === "user" && mode !== "edit",
					},
					className,
				)}
				initial={{ y: 5 }}
				animate={{ y: 0 }}
				data-role={message.role}
			>
				<div
					className={cn(
						"flex gap-4 w-full group-data-[role=user]/message:ml-auto group-data-[role=user]/message:max-w-2xl",
						{
							"w-full": mode === "edit",
							"group-data-[role=user]/message:w-fit": mode !== "edit",
						},
					)}
				>
					{message.role === "assistant" && <MentorAvatar streaming={isLoading} />}

					<div className="flex flex-col gap-4 w-full">
						{attachmentsFromMessage.length > 0 && (
							<div className="flex flex-row justify-end gap-2">
								{attachmentsFromMessage.map((attachment) => (
									<PreviewAttachment
										key={attachment.url}
										attachment={{
											name: attachment.filename ?? "file",
											contentType: attachment.mediaType,
											url: attachment.url,
										}}
									/>
								))}
							</div>
						)}

						{message.parts.map((part, index) => {
							const { type } = part;
							const key = `message-${message.id}-part-${index}`;

							if (type === "text") {
								if (mode === "view") {
									return (
										<div
											key={key}
											className={cn("flex flex-col gap-4", {
												"self-end w-fit min-w-0 bg-primary text-primary-foreground px-3 py-2 rounded-xl ml-5":
													message.role === "user",
											})}
										>
											<Streamdown components={MESSAGE_MARKDOWN_COMPONENTS}>
												{sanitizeMessageText(part.text)}
											</Streamdown>
										</div>
									);
								}

								return (
									<div key={key} className="flex flex-row gap-2 items-start">
										<div className="size-8" />

										<MessageEditor
											key={message.id}
											initialContent={part.text}
											onCancel={() => setMode("view")}
											onSend={(content) => {
												onMessageEdit?.(message.id, content);
												setMode("view");
											}}
										/>
									</div>
								);
							}

							if (isStaticToolUIPart<ChatTools>(part)) {
								const Renderer = partRenderers?.[part.type];
								return Renderer ? (
									<Renderer
										key={part.toolCallId || key}
										message={message}
										part={part}
										variant={variant}
									/>
								) : null;
							}

							return null;
						})}

						{!readonly && (
							<MessageActions
								className="-mt-3"
								key={`action-${message.id}`}
								messageContentToCopy={message.parts
									.filter((p) => p.type === "text")
									.map((p) => p.text)
									.join("\n")}
								messageRole={message.role}
								vote={vote}
								isLoading={isLoading}
								isInEditMode={mode === "edit"}
								variant={variant}
								onCopy={(text) => onCopy?.(text)}
								onVote={
									message.role === "assistant"
										? (isUpvote) => onVote?.(message.id, isUpvote)
										: undefined
								}
								onEdit={message.role === "user" ? () => setMode("edit") : undefined}
							/>
						)}
					</div>
				</div>
			</motion.div>
		</AnimatePresence>
	);
}

export const ThinkingMessage = () => {
	const role = "assistant";

	return (
		<motion.div
			className="w-full mx-auto max-w-3xl px-4 group/message min-h-96"
			initial={{ y: 5 }}
			animate={{ y: 0 }}
			data-role={role}
		>
			<div
				className={cn(
					"flex gap-4 group-data-[role=user]/message:px-3 w-full group-data-[role=user]/message:w-fit group-data-[role=user]/message:ml-auto group-data-[role=user]/message:max-w-2xl group-data-[role=user]/message:py-2 rounded-xl",
					{
						"group-data-[role=user]/message:bg-muted": true,
					},
				)}
			>
				<MentorAvatar streaming={true} />

				<div className="flex flex-col gap-2 w-full">
					<div className="flex flex-col gap-4 text-muted-foreground">Hmm...</div>
				</div>
			</div>
		</motion.div>
	);
};
