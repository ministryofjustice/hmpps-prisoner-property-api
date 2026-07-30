# Property snag issues — tracking

Backlog for the **[MAPB-709](https://dsdmoj.atlassian.net/browse/MAPB-709) — Property snag issues**
epic: bugs, an establishment-vs-prisoner view consistency gap, and a few enhancements found in testing
and review. Worked through one at a time; update **Status** and add PR links as each is picked up.

Repos: **API** = hmpps-prisoner-property-api · **UI** = hmpps-prisoner-property-ui ·
**LIP** = hmpps-locations-inside-prison-api.

| Ticket | Type | Size | Repo | Summary | Status |
|--------|------|------|------|---------|--------|
| [MAPB-711](https://dsdmoj.atlassian.net/browse/MAPB-711) | Story | L | API | Establishment list + summary tiles must reflect release-date-driven "Due for return" | In progress (api #69) |
| [MAPB-721](https://dsdmoj.atlassian.net/browse/MAPB-721) | Story | M | API | Model container transfer-out as a removal, not a prison reassignment (two-record model) — enabler for 712/713 | Done (api #68) |
| [MAPB-712](https://dsdmoj.atlassian.net/browse/MAPB-712) | Bug | M | UI (+API model) | Received-but-not-stored transferred property shows as "Stored" and editable | Dev review (ui #56) |
| [MAPB-713](https://dsdmoj.atlassian.net/browse/MAPB-713) | Bug | M | API (+UI) | "Property returned or transferred" tab omits property transferred out | Dev review (ui #56) |
| [MAPB-714](https://dsdmoj.atlassian.net/browse/MAPB-714) | Bug | S | UI | Make "Due for transfer out" tag grey on the establishment list | Merged (ui #55) |
| [MAPB-715](https://dsdmoj.atlassian.net/browse/MAPB-715) | Bug | S | API | Make establishment seal-number search case-insensitive | Merged (api #67) |
| [MAPB-716](https://dsdmoj.atlassian.net/browse/MAPB-716) | Bug | S | UI | Show "Transferring" not "Not known" for an in-transit prisoner on the prisoner view | Merged (ui #54) |
| [MAPB-675](https://dsdmoj.atlassian.net/browse/MAPB-675) | Story | — | — | Staff reactivate journey for Removed property containers (pre-existing epic member, not part of this snag batch) | To do |
| [MAPB-717](https://dsdmoj.atlassian.net/browse/MAPB-717) | Bug | S | UI | Remove-container page shows the container's prison, not the prisoner's current establishment | Merged (ui #54) |
| [MAPB-718](https://dsdmoj.atlassian.net/browse/MAPB-718) | Bug | S | UI | Move the "Combine containers" button above the property table | Merged (ui #54) |
| [MAPB-719](https://dsdmoj.atlassian.net/browse/MAPB-719) | Story | S | UI | Reinstate a role-gated "Manage property locations" button on the establishment page | Merged (ui #54) |
| [MAPB-720](https://dsdmoj.atlassian.net/browse/MAPB-720) | Story | M | LIP | Reactivate a removed property location when re-created with the same name | To do |

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
- **MAPB-720** lives downstream in LIP: "removing" a location only strips its PROPERTY usage (it isn't
  archived), so the name-uniqueness check still blocks re-creation. Needs cross-repo deploy coordination.
