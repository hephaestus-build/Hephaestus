---
"hephaestus": patch
---

Your Practice profile no longer shows lapses your work does not contain for five practices:

- **State how to verify the change** no longer treats the `Closes #12` example in a merge request
  template as the issue your change adopts. It accepts a sentence that says where to look and what
  is now there, a declared prerequisite such as your own API key, and a generate-and-build route for
  a project rename. It still flags a check that needs a secret nobody says how to provide.
- **Keep each commit to one logical change** judges what each commit holds, not how its subject is
  punctuated. "Add cast fetching, and adjust movie fetching" is one change. A README commit that also
  renames the source tree or adds views is two.
- **Validate inputs and edge cases at the boundary** stops flagging values Swift already makes
  optional, slashes inside strings, framework callbacks and guards that sit in another file. It still
  flags a slice such as `cast[0..<2]` behind only an `isEmpty` check.
- **Avoid crashing on recoverable problems** judges only the crash operators you write, such as
  `!`, `try!` and `fatalError`. A `try!` or `fatalError` inside a preview, and data you ship with
  the app, are not lapses. An unchecked subscript is judged once, under the input practice.
- **Respond to each review comment** no longer counts Hephaestus's own notes, praise, or advice a
  reviewer posted with their approval for a later iteration as open threads. It also accepts the
  reviewer's own "thank you for incorporating the changes" as a closed loop.

Workspaces that adopted these practices receive the change as an offered practice update.
