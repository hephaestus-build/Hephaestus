import { useLocation } from "@docusaurus/router";
import useDocusaurusContext from "@docusaurus/useDocusaurusContext";
import IconCopy from "@theme/Icon/Copy";
import IconExternalLink from "@theme/Icon/ExternalLink";
import IconSuccess from "@theme/Icon/Success";
import { type ReactNode, useEffect, useRef, useState } from "react";

import styles from "./styles.module.css";

type CopyState = "idle" | "copied" | "failed";

const COPY_LABEL: Record<CopyState, string> = {
	idle: "Copy page",
	copied: "Copied",
	failed: "Copy failed. Try again.",
};

/*
 * Passed to ClipboardItem as a promise, so the click's user activation lasts through the request:
 * Safari refuses a clipboard write that follows an awaited fetch.
 */
async function fetchMarkdown(path: string): Promise<Blob> {
	const response = await fetch(path);
	// The dev server has no twins and answers any path with the app's HTML.
	if (!response.ok || response.headers.get("content-type")?.includes("text/html") === true) {
		throw new Error(`${path} has no Markdown twin`);
	}
	return new Blob([await response.text()], { type: "text/plain" });
}

/** Copy the page's Markdown twin, view it, or hand it to an assistant. `@signalwire/docusaurus-plugin-llms-txt` writes the twins. */
export default function PageActions(): ReactNode {
	const { pathname } = useLocation();
	const { siteConfig } = useDocusaurusContext();
	const [copyState, setCopyState] = useState<CopyState>("idle");
	const menu = useRef<HTMLDetailsElement>(null);

	const markdownPath = `${pathname.replace(/\/$/u, "")}.md`;
	const prompt = `Read ${new URL(markdownPath, siteConfig.url).href} so I can ask questions about it.`;

	useEffect(() => {
		if (copyState === "idle") {
			return;
		}
		const reset = setTimeout(() => setCopyState("idle"), 2000);
		return () => clearTimeout(reset);
	}, [copyState]);

	const closeMenu = () => {
		if (menu.current !== null) {
			menu.current.open = false;
		}
	};

	useEffect(() => {
		const details = menu.current;
		if (details === null) {
			return;
		}
		const closeOnOutside = (event: PointerEvent) => {
			if (event.target instanceof Node && !details.contains(event.target)) {
				details.open = false;
			}
		};
		const closeOnEscape = (event: KeyboardEvent) => {
			if (details.open && event.key === "Escape") {
				// A hidden item cannot keep focus: hand it back to the menu's own button.
				if (details.contains(document.activeElement)) {
					details.querySelector("summary")?.focus();
				}
				details.open = false;
			}
		};
		// Only a known target outside: Safari does not focus a clicked link, so a null target can be
		// the click on an item, which must still land.
		const closeOnFocusOut = (event: FocusEvent) => {
			if (event.relatedTarget instanceof Node && !details.contains(event.relatedTarget)) {
				details.open = false;
			}
		};
		document.addEventListener("pointerdown", closeOnOutside);
		document.addEventListener("keydown", closeOnEscape);
		details.addEventListener("focusout", closeOnFocusOut);
		return () => {
			document.removeEventListener("pointerdown", closeOnOutside);
			document.removeEventListener("keydown", closeOnEscape);
			details.removeEventListener("focusout", closeOnFocusOut);
		};
	}, []);

	const copy = async () => {
		const markdown = fetchMarkdown(markdownPath);
		// A refused write never reads the promise; settle it so its rejection is not left unhandled.
		void Promise.allSettled([markdown]);
		try {
			await navigator.clipboard.write([new ClipboardItem({ "text/plain": markdown })]);
			setCopyState("copied");
		} catch {
			setCopyState("failed");
		}
	};

	const items = [
		{ label: "View as Markdown", href: markdownPath },
		{
			label: "Open in ChatGPT",
			href: `https://chatgpt.com/?hints=search&q=${encodeURIComponent(prompt)}`,
		},
		{ label: "Open in Claude", href: `https://claude.ai/new?q=${encodeURIComponent(prompt)}` },
	];

	return (
		<div className={styles.actions}>
			<button
				type="button"
				className={styles.copy}
				onClick={() => {
					void copy();
				}}
			>
				{copyState === "copied" ? (
					<IconSuccess className={styles.icon} />
				) : (
					<IconCopy className={styles.icon} />
				)}
				{COPY_LABEL[copyState]}
			</button>
			<output className={styles.status}>{copyState === "idle" ? "" : COPY_LABEL[copyState]}</output>
			<details ref={menu} className={styles.menu}>
				<summary className={styles.more} aria-label="More page actions" title="More page actions" />
				<ul className={styles.list}>
					{items.map(({ label, href }) => (
						<li key={label}>
							<a href={href} target="_blank" rel="noopener noreferrer" onClick={closeMenu}>
								{label}
								<IconExternalLink width={12} height={12} />
							</a>
						</li>
					))}
				</ul>
			</details>
		</div>
	);
}
