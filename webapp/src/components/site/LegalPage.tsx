import type { AnchorHTMLAttributes, ImgHTMLAttributes } from "react";
import { Streamdown } from "streamdown";

import { MarkdownCode } from "@/components/common/MarkdownCode";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Skeleton } from "@/components/ui/skeleton";
import { isSafeLegalHref, isSafeLegalImageSrc, type ResolvedLegalContent } from "@/lib/legal";

const DISCLAIMER_BANNER =
	"This deployment has not published its own legal pages. The content below is a placeholder and does not identify the operator of this instance.";

const ERROR_COPY = "We could not load this page. Reload to try again.";

// Treat host-supplied legal overrides as untrusted at the browser rendering boundary.
function SafeAnchor({ href, children, className }: AnchorHTMLAttributes<HTMLAnchorElement>) {
	if (!isSafeLegalHref(href)) {
		return <span className={className}>{children}</span>;
	}
	const isExternal = /^https?:/iu.test(href);
	return (
		<a
			href={href}
			className={className}
			rel={isExternal ? "noopener noreferrer" : undefined}
			target={isExternal ? "_blank" : undefined}
		>
			{children}
		</a>
	);
}

function SafeImage({ src, alt, className }: ImgHTMLAttributes<HTMLImageElement>) {
	if (!isSafeLegalImageSrc(src)) {
		return null;
	}
	return <img src={src} alt={alt ?? ""} className={className} />;
}

const SAFE_COMPONENTS = { a: SafeAnchor, code: MarkdownCode, img: SafeImage };

export type LegalContentState =
	| { status: "loading" }
	| { status: "error" }
	| { status: "ready"; content: ResolvedLegalContent };

export interface LegalPageProps {
	title: string;
	state: LegalContentState;
}

export function LegalPage({ title, state }: LegalPageProps) {
	return (
		<div className="mx-auto flex max-w-4xl flex-col gap-4">
			<h1 className="text-3xl font-bold">{title}</h1>
			{state.status === "loading" && (
				<div role="status" aria-busy="true" className="space-y-3">
					<span className="sr-only">Loading {title}</span>
					<Skeleton className="h-6 w-2/3" />
					<Skeleton className="h-24" />
				</div>
			)}
			{state.status === "error" && (
				<Alert variant="destructive">
					<AlertDescription>{ERROR_COPY}</AlertDescription>
				</Alert>
			)}
			{state.status === "ready" && (
				<>
					{state.content.source === "disclaimer" && (
						<Alert variant="destructive">
							<AlertDescription>{DISCLAIMER_BANNER}</AlertDescription>
						</Alert>
					)}
					<article lang="en" className="prose max-w-none dark:prose-invert">
						{/* Empty `rehypePlugins` drops Streamdown's default rehype-raw, and its bundled
						    rehype-harden ships `allowedProtocols: ["*"]`, so SAFE_COMPONENTS is the only
						    thing keeping `javascript:` and unknown schemes out of the DOM. */}
						<Streamdown
							mode="static"
							rehypePlugins={[]}
							remarkRehypeOptions={{ allowDangerousHtml: false }}
							parseIncompleteMarkdown={false}
							components={SAFE_COMPONENTS}
						>
							{state.content.markdown}
						</Streamdown>
					</article>
				</>
			)}
		</div>
	);
}
