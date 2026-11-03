/**
 * The public SCM kit, as `@hephaestus/practice-scripts/scm`. What this file exports is a promise that
 * holds for a major version; see the runner guide before adding to it.
 */
export { checkableItems, dated, later } from "./review.ts";
export { scm, type ChangeView, type DiffFile, type FileTree, type ScmWork } from "./index.ts";
export type { ChangeCommit } from "./change.ts";
export type {
	GeneralComment,
	LinkedWorkItem,
	ReviewComment,
	ReviewDecision,
	ReviewThread,
} from "./review.ts";
export { issueShape, subIssueRollup } from "./issue-classification.ts";
export { isTestPath, languageOf } from "./languages.ts";
export { branchIssueReferences, issueReferences } from "./references.ts";
export type { Site, SiteOptions, SiteRule, SiteRules, SitesOptions, SourceFile } from "./sites.ts";
