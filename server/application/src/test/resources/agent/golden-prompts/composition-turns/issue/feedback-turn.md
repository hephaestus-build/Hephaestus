## This turn
The review just finished. The criteria its NOT_MET practices were assessed against come first, as staged; its 4 admitted result(s) follow them, one per practice. The MET and NOT_MET ones carry their rationale and their citations by coordinates; each NOT_MET one also carries its problems, the located problems it found, each with its own citations. The abstentions carry the limit they recorded, an open question or why the practice did not apply, and their citations by coordinates. The history follows them.

### Criteria of `describes-changes` — staged empty; no standard is available here

### Criteria of `handles-errors` — not staged for this review; nothing is known about them here

```json
{
 "results": [
  {
   "resultId": "30000000-0000-4000-8000-000000000001",
   "practiceSlug": "handles-errors",
   "outcome": "NOT_MET",
   "itemId": "3",
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
    "citations": []
   },
   "citations": [],
   "problems": [
    {
     "citations": [],
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
   "itemId": "3",
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
    "citations": []
   },
   "citations": [],
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
   "itemId": "3",
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
{"contractVersion":3,"destinations":{"ON_WORK":{"maxPieces":1},"PRACTICE_PAGE":{"maxPieces":1},"CONVERSATION":{"maxPieces":1}},"minDistinctItems":2,"onWorkCopyForms":[]}
```

### What earlier reviews recorded about this person — not staged for this review; what it would record is unknown here, not empty

### Recorded delivered feedback
```json
{
 "readAt": "2026-10-20T10:05:00Z",
 "coverage": {
  "maxEntries": 30,
  "hasMore": false,
  "omittedWithoutCopyAuthority": 1
 },
 "recordRole": "RECORDED_DELIVERY",
 "feedback": [
  {
   "id": "60000000-0000-4000-8000-000000000001",
   "destination": "PRACTICE_PAGE",
   "deliveredAt": "2026-09-02T10:00:00Z",
   "recordedClaimCurrentness": "CURRENT",
   "withdrawn": false,
   "basedOn": [
    {
     "resultId": "50000000-0000-4000-8000-000000000001",
     "practiceSlug": "handles-errors",
     "practiceVersionId": 11,
     "outcome": "NOT_MET"
    }
   ],
   "bodyRole": "PRACTICE_PAGE_CARD",
   "body": "Earlier card: record each failure you catch."
  }
 ]
}
```

### Prepared feedback (not delivered; supersession targets)
```json
{
 "readAt": "2026-10-20T10:05:00Z",
 "coverage": {
  "maxEntries": 30,
  "hasMore": false,
  "omittedWithoutCopyAuthority": 1
 },
 "recordRole": "PREPARED_NOT_DELIVERED",
 "prepared": [
  {
   "threadKey": "60000000-0000-4000-8000-000000000002",
   "feedbackId": "60000000-0000-4000-8000-000000000002",
   "destination": "CONVERSATION",
   "practiceSlug": "describes-changes",
   "preparedAt": "2026-10-18T10:00:00Z",
   "recordedClaimCurrentness": "STALE",
   "basedOn": [
    {
     "resultId": "50000000-0000-4000-8000-000000000002",
     "practiceSlug": "describes-changes",
     "practiceVersionId": null,
     "outcome": "NOT_MET"
    }
   ],
   "bodyRole": "MENTOR_NOTES",
   "body": "Situation: descriptions name changes without reasons."
  }
 ]
}
```


Destinations open this turn: CONVERSATION (at most 1). Closed this turn, so write nothing for them: PRACTICE_PAGE.
The practices each open destination may carry: CONVERSATION: describes-changes. Write no piece, not even a WITHHOLD, for a practice on a destination that does not list it.
A pattern claim needs at least 2 distinct pieces of work.


This review did not settle one of its practices: names-things. Say nothing about them, for or against, and do not describe this review as complete.

Persist the feedback with report_feedback — every piece you have in one call. Writing nothing on a destination is a correct and common outcome; say in one line why, and stop.