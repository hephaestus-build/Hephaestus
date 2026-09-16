import { type AnchorHTMLAttributes, type ImgHTMLAttributes, useEffect, useState } from "react";
import { Streamdown } from "streamdown";

import { MarkdownCode } from "@/components/common/MarkdownCode";
import environment from "@/environment";
import {
	isSafeLegalHref,
	isSafeLegalImageSrc,
	type LegalPageId,
	type ResolvedLegalContent,
	resolveLegalContent,
} from "@/lib/legal";

const DISCLAIMER_BANNER =
	"This deployment has not been configured with a legal profile. The content below is a placeholder and does not identify the operator of this instance.";

const ERROR_COPY = "Unable to load legal content.";

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

const warnedDisclaimer = new Set<LegalPageId>();

export interface LegalPageProps {
	page: LegalPageId;
	title: string;
	resolver?: typeof resolveLegalContent;
	profileOverride?: string;
}

export function LegalPage({
	page,
	title,
	resolver = resolveLegalContent,
	profileOverride,
}: LegalPageProps) {
	const profile = profileOverride ?? environment.legal.profile;

	return (
		<div className="mx-auto flex max-w-4xl flex-col gap-4">
			<h1 className="text-3xl font-bold">{title}</h1>
			{/* Keyed by what identifies the content: a switch remounts, so the previous page's markdown
			    is never on screen while the new one loads. */}
			<LegalContent key={`${page}:${profile}`} page={page} profile={profile} resolver={resolver} />
		</div>
	);
}

interface LegalContentProps {
	page: LegalPageId;
	profile: string;
	resolver: typeof resolveLegalContent;
}

function LegalContent({ page, profile, resolver }: LegalContentProps) {
	const [resolved, setResolved] = useState<ResolvedLegalContent | null>(null);
	const [loadError, setLoadError] = useState<Error | null>(null);

	useEffect(() => {
		const controller = new AbortController();
		resolver(page, { signal: controller.signal, profile })
			.then((content) => {
				if (controller.signal.aborted) {
					return;
				}
				setResolved(content);
				if (content.source === "disclaimer" && !warnedDisclaimer.has(page)) {
					warnedDisclaimer.add(page);
					// oxlint-disable-next-line no-console -- Remediation addressed to whoever deployed the instance, once per page; the reader's half of this is the disclaimer banner.
					console.warn(
						`[legal] Disclaimer fallback served for page=${page}. Configure LEGAL_PROFILE or mount /legal-overrides/. See docs/admin/legal-pages.`,
					);
				}
			})
			.catch((error: unknown) => {
				if (
					controller.signal.aborted ||
					(error instanceof DOMException && error.name === "AbortError")
				) {
					return;
				}
				setLoadError(error instanceof Error ? error : new Error("Failed to load legal content"));
			});
		return () => controller.abort();
	}, [page, profile, resolver]);

	return (
		<>
			{resolved?.source === "disclaimer" ? (
				<div
					role="alert"
					className="rounded-md border border-destructive/40 bg-destructive/10 px-4 py-3 text-sm text-destructive"
				>
					{DISCLAIMER_BANNER}
				</div>
			) : null}

			{loadError ? (
				<div
					role="alert"
					className="rounded-md border border-destructive/40 bg-destructive/10 px-4 py-3 text-sm text-destructive"
				>
					{ERROR_COPY}
				</div>
			) : null}

			{resolved ? (
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
						{resolved.markdown}
					</Streamdown>
				</article>
			) : null}
		</>
	);
}
