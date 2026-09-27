import * as WebBrowser from "expo-web-browser";
import { useMemo } from "react";
import { useColorScheme } from "react-native";
import { EnrichedMarkdownText, type MarkdownStyle } from "react-native-enriched-markdown";
import { useReducedMotion } from "react-native-reanimated";

import { untrustedMarkdown } from "./untrusted-markdown";

export interface MarkdownProps {
	markdown: string;
	/** A reply still arriving: the renderer tolerates half-written syntax and fades new text in. */
	streaming?: boolean;
	testID?: string;
}

/**
 * Native Markdown for feedback and Heph's replies, both written by something other than the reader.
 * Nothing loads on its own: images arrive as links, HTML as text, links show no preview, and a link
 * opens in the in-app browser only when it is HTTPS. A task list is read-only, since ticking a box
 * here would save nothing.
 */
export function Markdown({ markdown, streaming = false, testID }: MarkdownProps) {
	const dark = useColorScheme() === "dark";
	const reduceMotion = useReducedMotion();
	const safe = useMemo(() => untrustedMarkdown(markdown), [markdown]);
	return (
		<EnrichedMarkdownText
			testID={testID}
			markdown={safe}
			flavor="github"
			selectable
			enableLinkPreview={false}
			enableTaskListItemToggle={false}
			streamingAnimation={streaming && !reduceMotion}
			markdownStyle={dark ? darkStyle : lightStyle}
			onLinkPress={({ url }) => {
				if (url.startsWith("https://")) {
					void WebBrowser.openBrowserAsync(url);
				}
			}}
		/>
	);
}

// The renderer takes concrete colour strings, not platform colours, so each scheme is spelled out.
function style(text: string, muted: string, accent: string, codeBackground: string): MarkdownStyle {
	return {
		paragraph: { fontSize: 17, color: text, lineHeight: 24 },
		h1: { fontSize: 22, color: text },
		h2: { fontSize: 20, color: text },
		h3: { fontSize: 17, color: text },
		link: { color: accent },
		blockquote: { color: muted },
		list: { color: text },
		code: { color: text, backgroundColor: codeBackground },
		codeBlock: { color: text, backgroundColor: codeBackground },
	};
}

const lightStyle = style("#17191F", "#596174", "#315FDC", "#ECEFF5");
const darkStyle = style("#F8FAFC", "#A9B1C3", "#8EAEFF", "#232834");
