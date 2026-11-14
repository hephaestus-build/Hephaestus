import { UntrustedMarkdownRenderer } from "@/components/common/UntrustedMarkdownRenderer";

import { feedbackDisplayMarkdown } from "./feedback-display";

/** A stored comment as it reads on the work. It parses with the renderer's own library, so it loads with it. */
export function FeedbackMarkdown({ body }: { body: string }) {
	return <UntrustedMarkdownRenderer>{feedbackDisplayMarkdown(body)}</UntrustedMarkdownRenderer>;
}
