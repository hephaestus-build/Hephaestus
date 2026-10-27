import PageActions from "@site/src/components/PageActions";
import DocBreadcrumbs from "@theme-original/DocBreadcrumbs";
import type { ReactNode } from "react";

import styles from "./styles.module.css";

/* The breadcrumb row is the page's toolbar: the trail on the left, the page actions on the right. */
export default function DocBreadcrumbsWrapper(): ReactNode {
	return (
		<div className={styles.row}>
			<DocBreadcrumbs />
			<PageActions />
		</div>
	);
}
