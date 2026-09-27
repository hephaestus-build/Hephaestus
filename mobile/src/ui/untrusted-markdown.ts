import type {
	Html,
	Image,
	ImageReference,
	Link,
	LinkReference,
	Parents,
	RootContent,
	Text,
} from "mdast";
import { fromMarkdown } from "mdast-util-from-markdown";
import { gfmFromMarkdown, gfmToMarkdown } from "mdast-util-gfm";
import { toMarkdown } from "mdast-util-to-markdown";
import { gfm } from "micromark-extension-gfm";
import { visit } from "unist-util-visit";

/**
 * Markdown from practice feedback and from Heph, made safe for the native renderer. That renderer
 * loads every image it is given, remote or `file://`, the moment it draws it, so an image becomes a
 * link the reader may choose to open. Raw HTML becomes the text it was.
 *
 * Markdown with neither is returned as it came, so a reply that is still arriving keeps the
 * half-written syntax the renderer knows how to wait for.
 */
export function untrustedMarkdown(markdown: string): string {
	const tree = fromMarkdown(markdown, {
		extensions: [gfm()],
		mdastExtensions: [gfmFromMarkdown()],
	});
	let replaced = 0;
	const swap = (parent: Parents | undefined, index: number | undefined, node: RootContent) => {
		if (parent !== undefined && index !== undefined) {
			// Each node swapped here is content its parent holds, and so is its replacement.
			(parent.children as RootContent[])[index] = node;
			replaced += 1;
		}
	};
	visit(tree, "image", (image, index, parent) => {
		swap(parent, index, imageLink(image));
	});
	visit(tree, "imageReference", (image, index, parent) => {
		swap(parent, index, imageReferenceLink(image));
	});
	visit(tree, "html", (html, index, parent) => {
		swap(parent, index, htmlText(html));
	});
	return replaced === 0 ? markdown : toMarkdown(tree, { extensions: [gfmToMarkdown()] });
}

function label(alt: string | null | undefined): Text[] {
	return [{ type: "text", value: alt === null || alt === undefined || alt === "" ? "Image" : alt }];
}

function imageLink(image: Image): Link {
	return { type: "link", url: image.url, title: image.title ?? null, children: label(image.alt) };
}

function imageReferenceLink(image: ImageReference): LinkReference {
	return {
		type: "linkReference",
		identifier: image.identifier,
		label: image.label ?? null,
		referenceType: image.referenceType,
		children: label(image.alt),
	};
}

function htmlText(html: Html): Text {
	return { type: "text", value: html.value };
}
