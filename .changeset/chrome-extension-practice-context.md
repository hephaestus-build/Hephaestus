---
"hephaestus": minor
---

A Chrome extension shows the practice review of the pull request, merge request or issue you are viewing on GitHub.com or GitLab, including self-hosted GitLab, in the page itself: the comments Hephaestus posted there for you, each a link to the comment, when the work was reviewed, and what the review concluded about your own work. On a repository's list, a row's Hephaestus button previews that work in one line. Asking for a review is confirmed in the extension's own window. On sites you allow, it sends your Hephaestus only the address of the work you open, or of the row you press. It is not yet published in the Chrome Web Store.

Operators who distribute the extension allow it by listing its extension id in the new optional `HEPHAESTUS_AUTH_BROWSER_EXTENSION_IDS`. Hephaestus now records the address of each comment it posts on a pull request, merge request or issue; comments posted before this release may have none.

A review requested for work whose repository several workspaces monitor now follows the requesting workspace's own settings. Installed-client sign-in and development account switching also work before an account has accepted the transparency notice; workspace data still requires it.
