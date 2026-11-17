// The retired short name of Hephaestus (docs/contributor/practice-feedback-language.mdx § Retired
// words), as a case-sensitive word: `Hephaestus` and lower-case identifiers such as `heph_*` stay
// allowed. Commit subjects, pull request titles and `gate:no-legacy` read it from here. It is
// joined from two parts to keep the name out of this file, which that gate scans.
// Keep this module dependency-free: PR validation imports it without a workspace install.
export const RETIRED_NAME = ["H", "eph"].join("");
export const RETIRED_NAME_PATTERN = new RegExp(String.raw`\b${RETIRED_NAME}\b`, "u");
export const RETIRED_NAME_MESSAGE =
	"Write Hephaestus, a conversation with Hephaestus or Ask Hephaestus: the short name is retired.";
