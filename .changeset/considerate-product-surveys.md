---
"hephaestus": minor
---

Product surveys no longer open on their own. Open surveys wait under **Feedback** in the header with how many questions they have and how long they take; the first time one is available you get a single short notice that is never repeated. You can leave and resume a draft, skip optional questions, clear an optional choice, and undo an accidental decline. Product feedback and bug reports live in the same menu, and page and browser details are attached only when you tick the box — ticked by default for a bug report, unticked for feedback. A failed send keeps your draft and says why.

Instance administrators get two pages under **Product feedback**. **Surveys** publishes multi-question surveys — free text, single or multiple choice, a labelled 1–5 rating, or a 0–10 recommendation question — with a live preview, an audience, a schedule, and Pause, Resume, End now and Delete. Each survey has a results page with invited, responded and declined counts, a completion rate, a summary per question (distributions, averages and a Net Promoter Score), every response with the sender's name, and a CSV export. **Inbox** shows feedback and bug reports with the sender, workspace, page, browser and release, and lets you mark them resolved or reopen them.

**Operators:** the upgrade creates `product_survey_participation` and copies every response and dismissal into it; `product_survey_submission` stays untouched for one release and is dropped in the next. No configuration changes.
