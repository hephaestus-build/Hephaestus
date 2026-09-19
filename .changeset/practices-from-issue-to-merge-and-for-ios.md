---
"hephaestus": minor
---

The bundled catalog covers more of the work a developer does. Three practices follow a change from
the issue to the merge: whether the change names the issue it implements, whether the merge leaves
that issue's stated outcome confirmed, and whether a review ask the author deferred was turned into
tracked work or explicitly waived. A new group, Building iOS apps well, reviews SwiftUI code — I/O kept
out of views, state owned where it belongs, an interface a screen reader can use, structured
concurrency, a preview with each new view, permissions declared truthfully, and colors that hold in
both appearances — and runs only on changes that touch Swift files. "Log through the platform logger,
not print" joins the code-craftsmanship group for every language the review reads. The tracking group is now named "Tracking work from issue to merge", and "Point the issue at its context" sits with the other issue-writing practices in "Writing issues a maintainer can act on".

Two more lifecycle practices follow the work to its landing: "Merge only after someone else approved"
judges the merge against the approvals that stood at that moment and is reviewed when the person who
merged is the author the review is about, and "Plan the work in an issue before starting it" reads the
linked issue's opening against the change's first commit. "Classify the issue the way the project
classifies issues" now judges an issue against the labelling convention the project actually keeps
instead of waiting for the issue to ask to be routed. A lapse the same developer has already heard about
on three or more recent pieces of work is named in one line on the work and explained on the practice
page, and two security practices run only on changes that touch a security surface.

**Operators:** the pull request record a review reads now carries `merged_by`, linked issues carry their
opening and closing time, and the project inventory carries the label scheme; no configuration changes.
