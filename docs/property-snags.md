# Property snag issues — tracking

Backlog for the **[MAPB-709](https://dsdmoj.atlassian.net/browse/MAPB-709) — Property snag issues**
epic: bugs, an establishment-vs-prisoner view consistency gap, and a few enhancements found in testing
and review. All ten items from the original batch are implemented; a second round (MAPB-725 to MAPB-727, then
MAPB-730, MAPB-732, MAPB-733 and MAPB-734) came out of testing those. The notes below record the decisions taken along
the way, so the reasoning survives the tickets. Update **Status** and add PR links as the remaining items land.

Repos: **API** = hmpps-prisoner-property-api · **UI** = hmpps-prisoner-property-ui ·
**LIP** = hmpps-locations-inside-prison-api.

**Status** tracks the PR, not the ticket. "Merged" means the code is on `main`; the Jira ticket stays in
*Dev review* until it has been tested, so merged is deliberately not the same as Done.

| Ticket | Type | Size | Repo | Summary | Status |
|--------|------|------|------|---------|--------|
| [MAPB-711](https://dsdmoj.atlassian.net/browse/MAPB-711) | Story | L | API | Establishment list + summary tiles must reflect release-date-driven "Due for return" | Merged (api #69) |
| [MAPB-721](https://dsdmoj.atlassian.net/browse/MAPB-721) | Story | M | API | Model container transfer-out as a removal, not a prison reassignment (two-record model) — enabler for 712/713 | Merged (api #68) |
| [MAPB-712](https://dsdmoj.atlassian.net/browse/MAPB-712) | Bug | M | UI (+API model) | Received-but-not-stored transferred property shows as "Stored" and editable | Merged (ui #56) |
| [MAPB-713](https://dsdmoj.atlassian.net/browse/MAPB-713) | Bug | M | API (+UI) | "Property returned or transferred" tab omits property transferred out | Merged (ui #56) |
| [MAPB-714](https://dsdmoj.atlassian.net/browse/MAPB-714) | Bug | S | UI | Make "Due for transfer out" tag grey on the establishment list | Merged (ui #55) |
| [MAPB-715](https://dsdmoj.atlassian.net/browse/MAPB-715) | Bug | S | API | Make establishment seal-number search case-insensitive | Merged (api #67) |
| [MAPB-716](https://dsdmoj.atlassian.net/browse/MAPB-716) | Bug | S | UI | Show "Transferring" not "Not known" for an in-transit prisoner on the prisoner view | Merged (ui #54) |
| [MAPB-717](https://dsdmoj.atlassian.net/browse/MAPB-717) | Bug | S | UI | Remove-container page shows the container's prison, not the prisoner's current establishment | Merged (ui #54) |
| [MAPB-718](https://dsdmoj.atlassian.net/browse/MAPB-718) | Bug | S | UI | Move the "Combine containers" button above the property table | Merged (ui #54) |
| [MAPB-719](https://dsdmoj.atlassian.net/browse/MAPB-719) | Story | S | UI | Reinstate a role-gated "Manage property locations" button on the establishment page | Merged (ui #54) |
| [MAPB-720](https://dsdmoj.atlassian.net/browse/MAPB-720) | Story | M | LIP | Reactivate a removed property location when re-created with the same name | Dev review (lip #731) |
| [MAPB-675](https://dsdmoj.atlassian.net/browse/MAPB-675) | Story | — | API + UI | Staff reactivate journey for Removed property containers (added to the epic later, not part of the original batch) | To do — investigated, see notes |
| [MAPB-725](https://dsdmoj.atlassian.net/browse/MAPB-725) | Bug | L | API + UI | Show one property status on both the establishment and person views | Merged (api #71, ui #57) |
| [MAPB-726](https://dsdmoj.atlassian.net/browse/MAPB-726) | Bug | M | API | Make the property status filters and summary tiles agree with the status shown | Merged (api #71) |
| [MAPB-727](https://dsdmoj.atlassian.net/browse/MAPB-727) | Bug | M | API + UI | Match old and new seal numbers when logging property that arrived on transfer | Merged (api #71, #72; ui #57, #58) |
| [MAPB-730](https://dsdmoj.atlassian.net/browse/MAPB-730) | Story | M | UI | Remember the establishment list filters when navigating away and back | Merged (ui #59) |
| [MAPB-732](https://dsdmoj.atlassian.net/browse/MAPB-732) | Bug | M | API | Show property left at another establishment in the receiving prison's incoming list | Merged (api #74) |
| [MAPB-733](https://dsdmoj.atlassian.net/browse/MAPB-733) | Bug | M | API + UI | Show a transferred box at the prison the person actually went to, not the one it was addressed to | To do |
| [MAPB-734](https://dsdmoj.atlassian.net/browse/MAPB-734) | Story | S | UI | Show the applied filters as removable tags on the establishment property list | Merged (ui #60) |

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

## Second round (found in testing after the first batch shipped)

Three more defects, all traced to the same structural fault the first batch only partly addressed: a
container's status was being derived independently in four places, so they drifted.

- **MAPB-725 and MAPB-726** are one mechanism and shipped as one API change, with the UI following.
  `ContainerStatusResolver` is now the single rule — removal outcome, then a disposal date that has arisen,
  then where the owner now is, then the container's own status. That last step is the fix: the persisted
  status is only as good as the movement events that reached us, and NOMIS-migrated property often has none,
  which is why a released prisoner's property read "Stored" on the establishment list and "Due for transfer
  out" on the person page (G0003GT). `OwnerLocation` holds the owner-to-status mapping **and its inverse**,
  so the establishment list's status filter matches on the same rule the rows display; `StatusOverlay`
  classifies a prison's owners once and binds the result as prisoner-number sets, keeping paging in the
  database. The summary tiles now count by the status shown, from one grouped query that also supplies the
  owners to classify — which fixed a double count on the way (a container past its disposal date was counted
  both in "due to be disposed" and in one of the other two tiles).
  - **Decided:** an owner showing as present clears a stale "follow the person" flag but does **not** clear a
    due-for-return recorded by a real release or death event. prisoner-search is fed by the same NOMIS
    movements and can lag; wrongly showing "stored" for someone released is the miss the rule exists to
    prevent, whereas showing "due for return" for someone still here is merely something staff check.
  - **Decided:** in transit (`TRN`) counts as elsewhere, and release beats transfer-out — property at LEI for
    someone now at MDI releasing tomorrow reads "due for return". Easy to invert if product prefers, but it
    does hide "needs sending to MDI" from LEI staff.
  - **Warn before release:** on migrated prisons the "due to transfer out" and "due to be returned" counts
    will *jump*. That is the bug being fixed, not a new one — worth before/after counts on preprod.
- **MAPB-727** — the seal match on arrival only reconciled the sending prison's record if that prison had
  already marked the container due for transfer out. Many never do, so the commonest case matched nothing:
  a second record was created and the sending prison's container kept showing as due to be transferred in
  (G0442GA, one box recorded twice). Any container the person still has in storage at another prison now
  matches, ignoring case and whitespace. An unmatched previous seal is **rejected with 400** carrying an
  `errorCode` rather than silently ignored — ignoring it is how the duplicate was made. Both histories now
  name the seal they were matched to. Dropping the status check also fixed an unrelated trap: a source
  container past its disposal date read as `DISPOSAL_REQUIRED` and so could never be matched.
  - **Follow-up found in testing (api #72, ui #58).** Matching then worked for property tagged *Due for
    transfer out* but failed for *In transit*. Two separate causes. In the UI, the previous-seal pre-check
    filtered on "not removed" — and *in transit* **is** a removal outcome of `TRANSFERRED` — so it rejected the
    property most obviously on its way here, before the API was ever called; staff could see the container
    listed and be told it did not exist. The membership rule for "Property due to be transferred in" now lives
    in one place shared by the list and the check, so anything visible there can be quoted. In the API,
    reconciling an already-transferred container required the sending prison's *stated destination* to equal
    the prison logging the arrival, so a box sent to one prison and arriving at another could not be logged at
    all. It now needs the transfer to be **unreconciled** rather than correctly addressed — staff holding the
    box are better evidence than the sender's expectation — and prison ids are compared trimmed and
    case-insensitively, so badly-cased local property is not mistaken for property elsewhere. The
    already-transferred path had no test coverage at all, which is why this survived the first fix.
- **MAPB-730** (remember the establishment list filters) came out of the same testing but is not started.
  Recommended shape is on the ticket: store the filters, search term and page in session, falling back to them
  only when the URL has no query string, following `hmpps-incident-reporting`'s `dashboardFilters`. Two traps
  are recorded there — "Clear search"/"Clear filters" are plain links to `/` and would silently re-apply the
  remembered state unless given an explicit clear parameter, and remembered filters must be scoped to the
  active caseload so switching establishment does not inherit them.

## Deploys: the `node-fetch` replica conflict

Preprod deploys failed for about ten days from ~20 July with
`conflict with "node-fetch" with subresource "scale" using apps/v1: .spec.replicas`, and the automatic
rollback failed on the same conflict. Nothing to do with the application code — the failing run happened to be
a property release, which is only how it was noticed.

A Node.js Kubernetes client had taken ownership of `.spec.replicas` via the `/scale` subresource and left
preprod on 4 replicas while the chart says 2. Helm 4 uses server-side apply, which reports a conflict when
another manager owns a field **and the values differ** — so dev, carrying the identical claim but sitting on
the value helm wanted, kept deploying happily and hid the problem.

Fixed by removing that manager's entry so helm reclaims the field (dev and preprod both cleared; prod was
never affected):

```
kubectl get deploy <name> -n <ns> --show-managed-fields -o json   # find the node-fetch/scale entry index
kubectl patch deployment <name> -n <ns> --type=json -p='[{"op":"remove","path":"/metadata/managedFields/<i>"}]'
```

The same manager also set `kubectl.kubernetes.io/restartedAt` (a rollout restart) on 14 July, and touched
**only** the property API — not the property UI, not the locations apps, which carry different managers
(`Go-http-client`, `kubectl/scale`, `HashiCorp`). That pattern reads as a one-off human action through a
Node-based Kubernetes GUI or script rather than scheduled platform tooling; `node-fetch` is just the default
field-manager name taken from the HTTP user-agent. **Not conclusively attributed** — worth asking Cloud
Platform if it recurs. Do *not* "fix" it by setting `replicaCount` to whatever the cluster holds: that makes
the cluster the source of truth instead of the chart, and it will drift again.

## Outstanding

- **Performance measurement (MAPB-711, now also MAPB-726).** Both shipped on the agreed basis of "make the
  views consistent first, then measure". The counts can no longer be a pure SQL aggregate (the owner's
  location lives in prisoner-search), so they group by prisoner and resolve them in one chunked bulk lookup —
  scaling with prisoners holding property rather than container count. The summary endpoint got *cheaper*
  (two queries became one); the new cost is on the establishment list **only when a status filter is
  applied**. Worth timing that page on dev for a prison with a lot of stored property. Escape hatches in
  order: parallelise the chunk fan-out, then a short-TTL cache on `PrisonStatusOverlayFactory` (the single
  seam), then prisoner-search's `/attribute-search` — noting the last is scoped to the prisoner's *current*
  prison so it misses property left behind elsewhere.
- **Incoming-property filter gap — fixed by [MAPB-732](https://dsdmoj.atlassian.net/browse/MAPB-732).**
  `?dueForTransferIn=true` keyed on the persisted `receivingPrisonId` alone, so property left at LEI for
  someone now at MDI never appeared in MDI's incoming list — the last place the establishment and person views
  still disagreed about the same container. The column is not merely absent on migrated data, it *decays*:
  sync writes moves and reseals, `baseEventStatus()` follows the latest event back to `STORED`, and
  `refreshDerivedState()` clears the destination. The owner-classification trick could not help, because
  `StatusOverlay` resolves the opposite direction — it starts from the people holding property *here*,
  whereas this needs the people *here* who hold property elsewhere. That comes from prisoner-search's prison
  roll (`PrisonRollFactory`), fetched once and only when incoming property is actually requested, and the
  filter falls back to the recorded destinations alone if the roll is unavailable. **No auth change was
  needed** — the roll endpoint shares a role gate with the bulk lookup already in use, so the prerequisite
  flagged on the ticket turned out to be already satisfied.
- **A transferred box addressed to the wrong prison — [MAPB-733](https://dsdmoj.atlassian.net/browse/MAPB-733).**
  Found while doing MAPB-732 and deliberately left out of it. Leeds sends a box to Moorland, the person is
  moved on to Berwyn instead: Leeds has closed its record, Moorland lists it as incoming forever, and Berwyn —
  where the box and the person actually are — sees nothing on either view. MAPB-732's new clause cannot help,
  because it requires `removal_outcome IS NULL` and a transferred container has one. The fix is to define
  "in transit to here" as *an unreconciled transfer whose owner is now here*, which changes the person page as
  well as the list, so the two must move together rather than shipping the establishment view ahead of the
  person view. `incomingScope` was written to make that one more OR'd clause.
- **`getById` / `PropertyContainerDto` has no owner context**, so the remove and change journeys tag from the
  container's own status. Fixing it means a prisoner-search call on a DTO that is also the write-endpoint
  response. Deliberately deferred.
- **Profile tile semantics.** `PrisonerPropertySummaryDto.overdueForReturn` now uses the shared rule, but
  `dueForTransferOut`/`dueForTransferIn` were left alone — that DTO feeds the prisoner-profile front end, not
  this UI, so redefining them needs that team.
