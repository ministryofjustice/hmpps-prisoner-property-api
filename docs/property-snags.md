# Property snag issues — tracking

Backlog for the **[MAPB-709](https://dsdmoj.atlassian.net/browse/MAPB-709) — Property snag issues**
epic: bugs, an establishment-vs-prisoner view consistency gap, and a few enhancements found in testing
and review. All ten items from the original batch are now implemented — the notes below record the decisions
taken along the way, so the reasoning survives the tickets. Update **Status** and add PR links as the
remaining items land.

Repos: **API** = hmpps-prisoner-property-api · **UI** = hmpps-prisoner-property-ui ·
**LIP** = hmpps-locations-inside-prison-api.

| Ticket | Type | Size | Repo | Summary | Status |
|--------|------|------|------|---------|--------|
| [MAPB-711](https://dsdmoj.atlassian.net/browse/MAPB-711) | Story | L | API | Establishment list + summary tiles must reflect release-date-driven "Due for return" | Done (api #69) |
| [MAPB-721](https://dsdmoj.atlassian.net/browse/MAPB-721) | Story | M | API | Model container transfer-out as a removal, not a prison reassignment (two-record model) — enabler for 712/713 | Done (api #68) |
| [MAPB-712](https://dsdmoj.atlassian.net/browse/MAPB-712) | Bug | M | UI (+API model) | Received-but-not-stored transferred property shows as "Stored" and editable | Done (ui #56) |
| [MAPB-713](https://dsdmoj.atlassian.net/browse/MAPB-713) | Bug | M | API (+UI) | "Property returned or transferred" tab omits property transferred out | Done (ui #56) |
| [MAPB-714](https://dsdmoj.atlassian.net/browse/MAPB-714) | Bug | S | UI | Make "Due for transfer out" tag grey on the establishment list | Merged (ui #55) |
| [MAPB-715](https://dsdmoj.atlassian.net/browse/MAPB-715) | Bug | S | API | Make establishment seal-number search case-insensitive | Merged (api #67) |
| [MAPB-716](https://dsdmoj.atlassian.net/browse/MAPB-716) | Bug | S | UI | Show "Transferring" not "Not known" for an in-transit prisoner on the prisoner view | Merged (ui #54) |
| [MAPB-717](https://dsdmoj.atlassian.net/browse/MAPB-717) | Bug | S | UI | Remove-container page shows the container's prison, not the prisoner's current establishment | Merged (ui #54) |
| [MAPB-718](https://dsdmoj.atlassian.net/browse/MAPB-718) | Bug | S | UI | Move the "Combine containers" button above the property table | Merged (ui #54) |
| [MAPB-719](https://dsdmoj.atlassian.net/browse/MAPB-719) | Story | S | UI | Reinstate a role-gated "Manage property locations" button on the establishment page | Merged (ui #54) |
| [MAPB-720](https://dsdmoj.atlassian.net/browse/MAPB-720) | Story | M | LIP | Reactivate a removed property location when re-created with the same name | Dev review (lip #731) |
| [MAPB-675](https://dsdmoj.atlassian.net/browse/MAPB-675) | Story | — | API + UI | Staff reactivate journey for Removed property containers (added to the epic later, not part of the original batch) | To do — investigated, see notes |

## Notes

- **MAPB-711** is the "upward" resolution of the G3881VF discrepancy that started this batch (the
  prisoner page relabelled stored property as due for return before release; the establishment view and
  its counts didn't). **Decided: the confirmed release date only** — the conditional (sentence-calculated)
  date can move, so it is deliberately not used. The rule now lives in one place
  (`PropertyContainerService.isDueForReturnSoon`) and is applied by the person view, the establishment list
  rows and the summary tile, so the three cannot drift. The summary count cannot be a pure SQL aggregate
  (the release date lives in prisoner-search), so it groups eligible stored containers by prisoner and
  resolves them in one bulk lookup. **Performance is to be measured after release**; if it degrades the
  establishment page badly, the agreed fallback is prisoner-search's `/attribute-search`
  (`prisonId` + `confirmedReleaseDate` range in a single call — cheaper, but scoped to the prisoner's
  current prison so it misses property left behind at another establishment).
- **MAPB-712 and MAPB-713** shared one root cause, now resolved. The API had two conflicting transfer
  models; a sending-prison "transfer out" (`transferTo`) reassigned the container's prison so it read
  Stored+editable at the destination (712) with no removal record (713). **Adopted the two-record model**
  (enabler **MAPB-721**, merged): transfer-out marks the source removed as `TRANSFERRED` (no prison
  reassignment) and exposes `receivingPrisonId`; the destination record is created when receiving staff log
  the arrival (add → seal-match → combine), matching sync/NOMIS. Both sub-decisions were settled as
  **link + surface**: seal-match reconciles in either ordering by linking, and until it is reconciled the
  destination shows the container as **"In transit"** (a distinct tag from "Due for transfer in", which
  means still held at the sending prison). 713 needed no UI code change — transferred property now carries
  a removal outcome, so the returned/transferred tab already lists it.
- **MAPB-715** turned out narrower than first described: storage-location search was already
  case-insensitive; only seal-number search was case-sensitive. Note the seal **uniqueness** check
  (`existsByCurrentSealNumberAndRemovalOutcomeIsNull`) is still case-sensitive — making it match the new
  case-insensitive search is a separate product call, not yet taken.
- **MAPB-720** is implemented **downstream in LIP only** — "removing" a location only strips its PROPERTY usage
  (it isn't archived), so the still-active location blocked re-creation by name. Adding it back now reinstates
  the original, keeping its id, code and history (which also avoids the code drift a duplicate row would get,
  since the code generator lengthens a taken code). Guarded narrowly to a BOX with no usages and no services
  and not archived, so an unrelated same-named location — a visit room, or a store a service owns — can't be
  repurposed into property storage; those still conflict as before, and archived locations are left to the
  existing unarchive route. A reinstate raises `LOCATION_AMENDED`, **not** `LOCATION_CREATED`, or NOMIS would
  try to add a location it already holds; the endpoint returns 200 for a reinstate and 201 only for a genuine
  create. **No property-api change and no deploy ordering** — the original cross-repo concern came to nothing.

- **MAPB-675** (staff reactivate journey) has been investigated but not started — the findings are recorded as
  a comment on the ticket so it can be picked up cold. The one to design for: while a container is `REMOVED`
  its **seal number is freed for re-use**, so another active container may already hold it — reactivating
  without a seal check could leave two active containers sharing a seal. No existing path covers this, because
  nothing else un-removes a container.

## Outstanding

- **MAPB-711 performance measurement.** The ticket shipped on the agreed basis of "make the views consistent
  first, then measure". The summary count can no longer be a pure SQL aggregate (the release date lives in
  prisoner-search), so it groups eligible stored containers by prisoner and resolves them in one chunked bulk
  lookup — scaling with prisoners holding stored property rather than container count. Worth timing the
  establishment page on dev for a prison with a lot of stored property. If it degrades badly, the agreed
  fallback is prisoner-search's `/attribute-search` (`prisonId` + `confirmedReleaseDate` in a single call),
  noting it is scoped to the prisoner's *current* prison so it misses property left behind elsewhere.
