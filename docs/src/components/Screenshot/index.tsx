import useBaseUrl from "@docusaurus/useBaseUrl";
import ThemedImage from "@theme/ThemedImage";
import type { ReactNode } from "react";

import styles from "./styles.module.css";

interface ScreenshotProps {
	/** `<page>/<name>` under `static/img/user/`, without the color scheme and the extension. */
	src: string;
	/** What the screen shows, in at most 155 characters. */
	alt: string;
}

/** A user-guide screenshot in the reader's color scheme, from `vp run generate:docs-screenshots`. */
export default function Screenshot({ src, alt }: ScreenshotProps): ReactNode {
	return (
		<ThemedImage
			className={styles.screenshot}
			alt={alt}
			width={1280}
			height={800}
			loading="lazy"
			sources={{
				light: useBaseUrl(`/img/user/${src}.light.webp`),
				dark: useBaseUrl(`/img/user/${src}.dark.webp`),
			}}
		/>
	);
}
