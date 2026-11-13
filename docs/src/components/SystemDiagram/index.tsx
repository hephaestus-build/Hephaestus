import { ThemedComponent } from "@docusaurus/theme-common";
import useBaseUrl from "@docusaurus/useBaseUrl";
import ThemedImage from "@theme/ThemedImage";
import { type ReactNode, useId } from "react";

import styles from "./styles.module.css";

interface SystemDiagramProps {
	/** The source name in `docs/diagrams/`, without `.hyl`. */
	name: string;
	/** The view and its scope, such as "Deployment view: Hephaestus on one host with k3s". */
	title: string;
	/** What the diagram shows, in at most 155 characters. The text around it gives its facts. */
	alt: string;
	/** Every connection of the view, as text. The SVG text is paths, so this is what a screen reader and a search find. */
	children: ReactNode;
}

/** A HyLiMo structural view in the reader's color scheme, from `vp run generate:diagrams`. */
export default function SystemDiagram({
	name,
	title,
	alt,
	children,
}: SystemDiagramProps): ReactNode {
	const connections = useId();
	const light = useBaseUrl(`/img/diagrams/${name}.light.svg`);
	const dark = useBaseUrl(`/img/diagrams/${name}.dark.svg`);
	return (
		<figure className={styles.figure}>
			<ThemedImage
				alt={alt}
				aria-describedby={connections}
				loading="lazy"
				sources={{ light, dark }}
			/>
			<figcaption className={styles.caption}>
				{title} ·{" "}
				<ThemedComponent>
					{({ theme, className }) => (
						<a className={className} href={theme === "dark" ? dark : light}>
							Open full size
						</a>
					)}
				</ThemedComponent>
			</figcaption>
			<details className={styles.connections}>
				<summary>Connections as text</summary>
				<div id={connections}>{children}</div>
			</details>
		</figure>
	);
}
