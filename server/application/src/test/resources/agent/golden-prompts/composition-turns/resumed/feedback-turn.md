## This turn
The review just finished. The criteria its NOT_MET practices were assessed against come first, as staged; its 4 admitted result(s) follow them, one per practice. The MET and NOT_MET ones carry their rationale and their citations by coordinates; each NOT_MET one also carries its problems, the located problems it found, each with its own citations. The abstentions carry the limit they recorded, an open question or why the practice did not apply, and their citations by coordinates. The history follows them.

### Criteria of `describes-changes` — not staged for this review; nothing is known about them here

### Criteria of `handles-errors`
````markdown
# Handles errors

A change handles each failure it introduces: it records, rethrows or reports it.

## Not met
- A catch block returns a default value and records nothing.

```java
catch (IOException e) { return null; }
```
````

```json
{
 "results": [
  {
   "resultId": "30000000-0000-4000-8000-000000000001",
   "practiceSlug": "handles-errors",
   "outcome": "NOT_MET",
   "itemId": "7",
   "summary": "The new catch swallows the failure",
   "explanation": "The catch block returns null and records nothing.",
   "coverage": {
    "source": "claimed",
    "complete": true,
    "selected": 1,
    "checked": 1,
    "total": 1
   },
   "summaryOnly": false,
   "publicEligible": true,
   "evidence": {
    "citations": [
     {
      "sourceKind": "scm.pull-request.diff",
      "sourcePath": "context/change.json",
      "path": "src/App.java",
      "side": "NEW",
      "commit": "2222222222222222222222222222222222222222",
      "startLine": 15,
      "endLine": 16,
      "index": 0,
      "anchorable": true
     }
    ]
   },
   "example": {
    "citation": {
     "sourceKind": "scm.pull-request.diff",
     "sourcePath": "context/change.json",
     "path": "src/App.java",
     "side": "NEW",
     "commit": "2222222222222222222222222222222222222222",
     "startLine": 13,
     "endLine": 13,
     "index": 0,
     "anchorable": false
    },
    "note": "The try wraps the whole call."
   },
   "citations": [
    {
     "sourceKind": "scm.pull-request.diff",
     "sourcePath": "context/change.json",
     "path": "src/App.java",
     "side": "NEW",
     "commit": "2222222222222222222222222222222222222222",
     "startLine": 15,
     "endLine": 16,
     "index": 0,
     "anchorable": true
    }
   ],
   "problems": [
    {
     "citations": [
      {
       "sourceKind": "scm.pull-request.diff",
       "sourcePath": "context/change.json",
       "path": "src/App.java",
       "side": "NEW",
       "commit": "2222222222222222222222222222222222222222",
       "startLine": 15,
       "endLine": 16,
       "index": 0,
       "anchorable": true
      },
      {
       "sourceKind": "scm.pull-request.diff",
       "sourcePath": "context/change.json",
       "path": "context/change.json#/diff",
       "side": "NEW",
       "commit": null,
       "startLine": 6,
       "endLine": 11,
       "index": 1,
       "anchorable": false
      }
     ],
     "severity": "MAJOR",
     "summary": "The failure is dropped",
     "resultId": "30000000-0000-4000-8000-000000000001",
     "position": 0,
     "key": "swallowed-io",
     "explanation": "The catch returns null without recording the IOException.",
     "basis": {
      "type": "introduced",
      "head": 0,
      "explanation": "The change adds the catch that drops the failure."
     },
     "nextStep": "Record or rethrow the failure.",
     "publicEligible": true,
     "problemId": "40000000-0000-4000-8000-000000000001"
    }
   ]
  },
  {
   "resultId": "30000000-0000-4000-8000-000000000002",
   "practiceSlug": "describes-changes",
   "outcome": "NOT_MET",
   "itemId": "7",
   "summary": "The description omits the reason",
   "explanation": "It names the change but not why it is needed.",
   "coverage": {
    "source": "claimed",
    "complete": true,
    "selected": 1,
    "checked": 1,
    "total": 1
   },
   "summaryOnly": true,
   "publicEligible": true,
   "evidence": {
    "citations": [
     {
      "sourceKind": "scm.pull-request.core",
      "sourcePath": "context/description.md",
      "path": "context/description.md",
      "side": null,
      "commit": null,
      "startLine": 1,
      "endLine": 1,
      "index": 0,
      "anchorable": false
     }
    ]
   },
   "citations": [
    {
     "sourceKind": "scm.pull-request.core",
     "sourcePath": "context/description.md",
     "path": "context/description.md",
     "side": null,
     "commit": null,
     "startLine": 1,
     "endLine": 1,
     "index": 0,
     "anchorable": false
    }
   ],
   "problems": [
    {
     "citations": [],
     "severity": "MINOR",
     "summary": "No reason is given",
     "resultId": "30000000-0000-4000-8000-000000000002",
     "position": 0,
     "key": null,
     "explanation": "The description lists the change without its purpose.",
     "basis": null,
     "nextStep": null,
     "publicEligible": true,
     "problemId": "40000000-0000-4000-8000-000000000002"
    }
   ]
  },
  {
   "resultId": "30000000-0000-4000-8000-000000000003",
   "practiceSlug": "tests-behavior",
   "outcome": "MET",
   "itemId": "7",
   "summary": "A test covers the fetch",
   "explanation": "AppTest calls fetch with a failing client.",
   "coverage": {
    "source": "claimed",
    "complete": true,
    "selected": 1,
    "checked": 1,
    "total": 1
   },
   "summaryOnly": false,
   "publicEligible": true,
   "evidence": {
    "citations": []
   },
   "citations": [],
   "problems": []
  },
  {
   "resultId": "30000000-0000-4000-8000-000000000004",
   "practiceSlug": "checks-inputs",
   "outcome": "COULD_NOT_DETERMINE",
   "evidence": {
    "openQuestions": [
     "Where does the id come from?"
    ]
   },
   "citations": []
  }
 ]
}
```

### The composition request (destinations, limits, copy forms)
```json
{"contractVersion":3,"destinations":{"ON_WORK":{"maxPieces":1},"PRACTICE_PAGE":{"maxPieces":1},"CONVERSATION":{"maxPieces":1}},"minDistinctItems":2,"onWorkCopyForms":["FILE_NOTE","LINE_NOTE"]}
```

### What earlier reviews recorded about this person — not staged for this review; what it would record is unknown here, not empty

### PRACTICE_PAGE support: occurrences a new card may cite at this read
```json
{
  "state": "COMPLETE",
  "readAt": "2026-10-20T10:05:00Z",
  "practices": [
    {
      "practiceSlug": "handles-errors",
      "decision": "ADMIT",
      "stopReason": null,
      "occurrences": [
        {
          "resultId": "50000000-0000-4000-8000-000000000001",
          "itemKind": "scm.pull_request",
          "itemId": "5",
          "outcome": "NOT_MET",
          "origin": "EVENT",
          "summary": "A catch swallowed a failure",
          "explanation": "The handler returned an empty list.",
          "evidence": {
            "citations": []
          },
          "practiceVersionId": "11",
          "recordedAt": "2026-09-01T10:00:00Z"
        },
        {
          "resultId": "30000000-0000-4000-8000-000000000001",
          "itemKind": "scm.pull_request",
          "itemId": "7",
          "outcome": "NOT_MET",
          "origin": "EVENT",
          "summary": "The new catch swallows the failure",
          "explanation": null,
          "evidence": null,
          "practiceVersionId": null,
          "recordedAt": "2026-10-20T10:01:00Z"
        }
      ]
    },
    {
      "practiceSlug": "describes-changes",
      "decision": "GATE_REFUSED",
      "stopReason": "FEEDBACK_OFF",
      "occurrences": []
    }
  ]
}
```

### Recorded delivered feedback — not staged for this review; what it would record is unknown here, not empty

### Prepared feedback (not delivered; supersession targets) — not staged for this review; what it would record is unknown here, not empty


Destinations open this turn: PRACTICE_PAGE (at most 1), CONVERSATION (at most 0). Private feedback that an earlier attempt of this review prepared already decides describes-changes; it stands, and the limits above are the room it left.
The practices each open destination may carry: PRACTICE_PAGE: handles-errors; CONVERSATION: handles-errors. Write no piece, not even a WITHHOLD, for a practice on a destination that does not list it.
A pattern claim needs at least 2 distinct pieces of work.


This review did not settle one of its practices: names-things. Say nothing about them, for or against, and do not describe this review as complete.

Persist the feedback with report_feedback — every piece you have in one call. Writing nothing on a destination is a correct and common outcome; say in one line why, and stop.