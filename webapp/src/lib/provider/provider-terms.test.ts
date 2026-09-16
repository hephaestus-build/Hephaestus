import { describe, expect, it } from "vitest";

import {
	getProviderSlug,
	getProviderTerms,
	type ProviderType,
} from "@/lib/provider/provider-terms";

describe("getProviderTerms", () => {
	it("returns GitHub terminology", () => {
		const terms = getProviderTerms("GITHUB");
		expect(terms.displayName).toBe("GitHub");
		expect(terms.pullRequest).toBe("Pull Request");
		expect(terms.pullRequests).toBe("Pull Requests");
		expect(terms.pullRequestShort).toBe("PR");
		expect(terms.pullRequestsShort).toBe("PRs");
		expect(terms.repository).toBe("Repository");
		expect(terms.repositories).toBe("Repositories");
		expect(terms.organization).toBe("Organization");
	});

	it("returns GitLab terminology", () => {
		const terms = getProviderTerms("GITLAB");
		expect(terms.displayName).toBe("GitLab");
		expect(terms.pullRequest).toBe("Merge Request");
		expect(terms.pullRequests).toBe("Merge Requests");
		expect(terms.pullRequestShort).toBe("MR");
		expect(terms.pullRequestsShort).toBe("MRs");
		expect(terms.repository).toBe("Project");
		expect(terms.repositories).toBe("Projects");
		expect(terms.organization).toBe("Group");
	});

	it("uses different GitHub and GitLab terms for every key", () => {
		const github = getProviderTerms("GITHUB");
		const gitlab = new Map(Object.entries(getProviderTerms("GITLAB")));
		const shared = Object.entries(github).filter(([term, wording]) => gitlab.get(term) === wording);
		expect(shared).toStrictEqual([]);
	});

	it("covers all provider types", () => {
		const providers: ProviderType[] = ["GITHUB", "GITLAB"];
		for (const provider of providers) {
			expect(getProviderTerms(provider)).toBeDefined();
		}
	});
});

describe("getProviderSlug", () => {
	it("converts GITHUB to github", () => {
		expect(getProviderSlug("GITHUB")).toBe("github");
	});

	it("converts GITLAB to gitlab", () => {
		expect(getProviderSlug("GITLAB")).toBe("gitlab");
	});
});
