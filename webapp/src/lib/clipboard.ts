import { toast } from "sonner";

const COPY_FAILED = "Couldn't copy that to the clipboard.";

/**
 * A refused write — a denied permission, an unfocused document — is the reader's to hear about;
 * `copied` is announced only once the write has landed.
 */
export function copyToClipboard(content: string, copied?: string): void {
	navigator.clipboard
		.writeText(content)
		.then(() => {
			if (copied !== undefined) {
				toast.success(copied);
			}
		})
		.catch(() => {
			toast.error(COPY_FAILED);
		});
}

/** One piece of content in two formats: plain text (which may be Markdown) and HTML. */
export interface RichText {
	text: string;
	html: string;
}

/**
 * Copies content that may still be on its way, as plain text and HTML together, so a rich editor
 * pastes a formatted list and a plain field pastes the text. The one `ClipboardItem` is written in
 * the same task as the press that asked for it, holding each format as a promise: Safari refuses a
 * write that starts after the user gesture has gone, and the content may take several requests to
 * gather. Where the rich write is unsupported or refused, the plain text is written instead.
 * Settles once the reader has been told how it went.
 */
export async function copyRichText<T extends RichText>(
	content: Promise<T>,
	copied: (content: T) => string,
): Promise<void> {
	try {
		try {
			await writeRich(content);
		} catch {
			const { text } = await content;
			await navigator.clipboard.writeText(text);
		}
		toast.success(copied(await content));
	} catch {
		toast.error(COPY_FAILED);
	}
}

/** Everything before the first `await` runs in the caller's task, which is what keeps the gesture. */
async function writeRich(content: Promise<RichText>): Promise<void> {
	if (typeof ClipboardItem === "undefined") {
		throw new TypeError("This browser copies plain text only.");
	}
	const blob = async (type: string, pick: (resolved: RichText) => string) =>
		new Blob([pick(await content)], { type });
	const text = blob("text/plain", (resolved) => resolved.text);
	const html = blob("text/html", (resolved) => resolved.html);
	// A write refused before the browser reads the formats leaves their rejections unread; the
	// caller reports a failed gather from `content` itself.
	void Promise.allSettled([text, html]);
	await navigator.clipboard.write([new ClipboardItem({ "text/plain": text, "text/html": html })]);
}
