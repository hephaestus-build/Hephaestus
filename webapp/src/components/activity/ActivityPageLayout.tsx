import type { ReactNode } from "react";

import { PageLayout } from "@/components/layout/PageLayout";

/**
 * The measure of both activity pages. Every region on them is one column of rows, and a wider row
 * puts its count far from the words it counts.
 */
export function ActivityPageLayout({ children }: { children: ReactNode }) {
	return <PageLayout className="max-w-3xl">{children}</PageLayout>;
}
