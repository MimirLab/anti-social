# Reader portability and source packs

anti social keeps subscriptions, personal channels, collections and reader preferences locally. There are two deliberately separate exports:

* A **reader export** moves the reader’s subscription configuration, folders, explicit channel filters, saved collections and eligible reader settings. Reading progress is optional. It contains personal information; share a source pack when recommending sources to somebody else.
* A **source pack** recommends only the selected public sources. Import opens a preview, and the recipient chooses which sources to add. It carries no channel filter, reading position, read marks, history, saved material, commenter identity, transport setting or custom social retrieval route.

An export is a plain UTF-8 document, not an account, a synchronization service or a remote subscription. Importing or previewing a file makes no network requests. Refreshing explicitly accepted subscriptions subsequently uses `NetworkGateway`, including the receiving device’s Tor policy. Source availability and the contents supplied by a feed remain source dependent.

## Choosing a format

| Format | What it carries | Intended use |
| --- | --- | --- |
| Native reader JSON (`kind: "state"`) | Social and RSS/Atom subscriptions, folders, explicit creator bundles, personal-channel rules, portable settings, saved collections/copies, optional reading progress | Moving an anti social reader to another installation |
| Native source-pack JSON (`kind: "sources"`) | Public source URLs and labels only | Recommending selected social accounts, communities or ordinary feeds |
| OPML 2.0 | Ordinary RSS/Atom feed URLs, labels and folder labels | Exchanging feed lists with FreshRSS and other feed readers |

OPML cannot faithfully describe Darwin’s social routing, explicit cross-platform creator bundles, personal-channel rules, saved collections or reading position. Those use the documented native format below. OPML export omits social sources rather than disguising social profile URLs as RSS feeds. OPML does not carry paused/enabled state: imported feed sources are enabled after the recipient accepts them.

See [source pack](examples/software-sources.antisocial.json), [reader configuration](examples/reader-state.antisocial.json), and [ordinary feed list](examples/blogs.opml). Example domains are illustrative and are not live-source availability claims.

## What we learned from NewPipe and FreshRSS

NewPipe separates a lightweight subscription JSON export from a complete local database backup. Its subscription model contains an app-version envelope and a list of `{service_id, url, name}` records; the complete backup also covers history, playlists and settings. anti social uses the same useful separation between source recommendations and personal state, with a versioned application-specific schema rather than NewPipe service IDs. NewPipe video/audio subscription files and database archives are **not** compatible anti social imports, and this work does not add video-platform subscriptions. Sources: [NewPipe’s import/export guide](https://newpipe.net/FAQ/tutorials/import-export-data/), [subscription model](https://github.com/TeamNewPipe/NewPipe/blob/dev/app/src/main/java/org/schabi/newpipe/local/subscription/workers/SubscriptionData.kt), and [JSON implementation](https://github.com/TeamNewPipe/NewPipe/blob/dev/app/src/main/java/org/schabi/newpipe/local/subscription/workers/ImportExportJsonHelper.kt), examined 2026-10-17. No NewPipe implementation code was copied.

FreshRSS imports/exports RSS and Atom feed lists using OPML, and documents a separate extension namespace for features beyond ordinary feed subscriptions. anti social accepts standard feed URLs and folders; it does not execute imported scraping expressions, cURL options, cookies, proxies, remote dynamic OPML lists or FreshRSS read-action rules. Unsupported source types and ignored extension settings appear as preview warnings. Sources: [FreshRSS subscription guide](https://freshrss.github.io/FreshRSS/en/users/04_Subscriptions.html) and [its OPML extension specification](https://github.com/FreshRSS/FreshRSS/blob/edge/docs/en/developers/OPML.md), examined 2026-10-17.

## Native JSON version 1

The envelope is identified by `format: "app.antisocial.reader"` and integer `version: 1`. Field names and enum values are case sensitive. Unknown fields, unsupported versions, duplicate object fields, duplicate source IDs/URLs, malformed types and missing required fields are errors. Imports are validated before any mutations; a damaged rule must not quietly become a broader rule.

| Envelope field | Type | Meaning |
| --- | --- | --- |
| `format` | string | Exactly `app.antisocial.reader` |
| `version` | integer | Exactly `1` |
| `kind` | string | `state` or `sources` |
| `createdAt` | integer | Nonnegative Unix time in milliseconds; OPML’s lack of an export timestamp is represented internally by `0` |
| `title` | string or null | Optional human title, at most 160 characters; field is present even when null |
| `sources` | array | Source records, at most 4,096 |
| `channels` | array | Required for `state`, forbidden for `sources`; at most 32 personal channels |
| `statePayload` | object or null | Required for `state`, forbidden for `sources`; Android-owned portable settings and library schema described below |
| `progress` | object | Optional for `state`, forbidden for `sources`; omitted entirely unless reading progress was selected |

`warnings` and import origin are preview results, not trusted fields in an exported file. Native import never treats file-supplied display text as executable behavior.

### Source records

All source records have these required fields:

| Field | Type | Meaning |
| --- | --- | --- |
| `platform` | string | A supported stable platform ID, such as `twitter`, `reddit`, `bluesky`, `mastodon`, or `rss`; not the display label |
| `kind` | string | Lowercase target kind: `profile`, `community`, `tag`, `post`, `thread`, `search`, `feed`, or `media` |
| `identity` | string | Public target identity understood by the platform adapter; ordinary feeds use their feed URL |
| `url` | string | Authentic canonical HTTPS source URL, never a presentation frontend alias |
| `name` | string | Source label, at most 512 characters |
| `feedUrl` | string or null | State: optional explicit HTTPS retrieval feed; source pack: null for social sources, canonical public feed URL for RSS/Atom sources |

Reader-state records also require these fields; source-pack records must not contain them:

| Field | Type | Meaning |
| --- | --- | --- |
| `id` | string | Opaque exporter account ID used as a foreign key; the importer remaps references to existing or newly generated local account IDs |
| `folder` | string | Folder label, empty for unfiled, at most 1,024 characters |
| `enabled` | boolean | Whether this source participates in refreshes |
| `creatorId` | string or null | Explicit locally linked creator-bundle ID; null means the account stands alone |
| `creatorName` | string or null | Bundle display label; requires a nonnull `creatorId` |

A source pack generates a public account ID from `platform + ":" + url` on import (ordinary feeds use `rss:` followed by the lowercase SHA-256 of the canonical feed URL, keeping long feed URLs usable by channel rules), starts sources enabled and unfiled, and does not assert that two accounts belong to the same creator. It strips the exporter’s local bundle IDs/names, paused state, folder labels and alternate social `feedUrl` overrides. The recipient decides what to subscribe to; matching handles do not establish identity. RSS/Atom feed URLs remain necessary source data.

Source IDs, target identities and URLs are bounded at 8,192 characters. Credentials embedded in URL authority fields are rejected. Ordinary feeds additionally require a public HTTPS feed URL without fragments or private literal/local addresses. Imports do not upgrade HTTP to HTTPS silently. A private or otherwise unsupported OPML entry is skipped with a warning; an invalid native-state source rejects that state import.

### Personal-channel rules

Native `state.channels` preserve the existing explicit-rule semantics. Each object requires:

```
id, name, revision,
subscriptionIds, keywords, hashtags, contentTypes,
excludeKeywords, excludeHashtags, excludeSubscriptionIds,
minVideoSeconds, excludeContentTypes
```

`subscriptionIds` and `excludeSubscriptionIds` are arrays of creator-bundle IDs, or account IDs for unbundled sources. Empty positive selection means all enabled subscriptions. References to missing subscriptions are preserved and warned about, not dropped: a channel selecting a removed creator must not become an unrestricted feed. References are at most 512 characters each, reflecting the existing channel-rule limit even though standalone source IDs may be longer.

The Android reader derives a social source's retrieval identity and kind from the original URL shown in the preview, rather than trusting an imported identity hint. A declared platform that disagrees with that URL is rejected before changes. Private reader-state files can retain an existing explicit social feed override; sources-only packs cannot carry alternate retrieval URLs. Missing and unselected positive rules use stable nonmatching placeholders. Exclusions still map to an already subscribed matching public source even when that source is unchecked, so a known cross-post edition cannot bypass the imported exclusion.

Keywords and hashtags are literal string arrays. Content types are arrays drawn from `TEXT`, `IMAGES`, `VIDEO`, `AUDIO`, and `LINKS`. `minVideoSeconds` is null or a positive integer no greater than 31,536,000; source-supplied duration is required to match it. `revision` is a positive integer. Other existing limits and validation in `PersonalChannels` apply. Alternatives within a field are OR; populated positive fields are AND and must match one complete source edition. An exclusion matching any known edition excludes its fused group. Channels stay chronological.

### Portable settings, collections and progress

`statePayload` is owned and validated by the Android reader service in addition to the generic JSON checks. Its optional sections are `settings`, `filters`, `library` and, since 0.35.37, `localDiscovery`. An absent section changes nothing. Unknown section keys are rejected.

`settings` accepts only these existing reader preference keys, with their normal `Preferences.validate` types and ranges:

```
archiveChecks, archiveRecovery,
interval, wifiOnly, cacheMb, videoCacheMb, videoCacheDays,
videoDownloadLimitMb, videoDownloadWifiOnly,
sponsorBlockEnabled, sponsorBlockCategories,
historyFullText, historyTextMb, unseenDays, viewedDays, historyDays,
theme, platforms, localAreas, localArea
```

Booleans stay booleans; duration/storage preferences are bounded integers; `theme` is `system`, `light`, or `dark`; `platforms` is the existing comma-separated list of supported stable platform IDs. Settings import is a separate opt-in choice in the preview. Tor, original-site fallback, search provider, external browser, commenter identity, server credentials, transport/session state, current page and device-specific values are not portable settings and cannot be supplied through this section. The receiving device keeps its connection/privacy policy.

`filters` requires both `disabledLists` (up to 4,096 unique list-ID strings) and `customLists` (up to 256 unique HTTPS list-URL strings without credentials or fragments). Strings are at most 8,192 characters. This transfers choices, not compiled filters, downloaded list data or executable configuration. Import validates URL syntax without fetching or resolving destinations; NetworkGateway applies destination policy when lists are explicitly updated. Accepting the filter section does not download or rebuild lists; the current compiled protection remains active until that update.

`library` requires both arrays:

| Field | Record | Limits and meaning |
| --- | --- | --- |
| `collections` | `{id, name, createdAt}` | Up to 1,024 collections. Unique IDs (1–128 characters) and names (1–80 characters, ASCII case-insensitive uniqueness); nonnegative Unix-millisecond timestamp |
| `saved` | `{post, savedAt, details?, collectionIds}` | Up to 4,096 saved posts. Unique platform/content identity; nonnegative save timestamp; up to 1,024 unique collection IDs, all defined in `collections` |

`post` is the structured `Post` record defined in [`Models.kt`](../core/src/main/kotlin/app/antisocial/core/Models.kt), including its text, attribution, original URL, selected media metadata and available archive pair. Optional `details` is the `SavedCopyDetails` record defined in [`LocalLibraryModels.kt`](../app/src/main/kotlin/app/antisocial/android/LocalLibraryModels.kt): `{token, capturedAt, threads, selectedAssets, fusionRoots?}`. Captured threads contain their actual captured replies and sort order (`CHRONOLOGICAL` or `SLOP`), not an implied complete online discussion. There are at most 4,096 captured replies/root records per saved copy and 4,096 selected-asset records. A saved post or captured reply has at most 1 MiB combined title/body text. Fusion and asset selections must refer to validated captured editions and their actual available attachments. Import generates fresh local mutation tokens.

#### Saved source editions in anti social 0.25 and later

A saved copy's optional `details.fusionRoots` retains up to **64 complete original
`Post` records**. Several records may belong to the same platform. Their original
URLs and platform/content identities remain distinct; a platform label is not a
source-edition ID. The exact saved primary edition must be included. Paired archive
snapshots remain attached to their corresponding original, and captured threads
keep their own original root, replies, capture time and sort order. A common media
URL may use one local file while each original retains its media attribution.

Every edition must be a readable, non-preview root with its authentic original
identity. Supplied dates must be positive and not in the future beyond the normal
clock tolerance. The source-repeat and linked-account paths retain their 48-hour
window and require known dates. Content-copy validation also admits different
publishers: substantial exact captions can match within 30 days, while actual
shared media or an exact original-post reference can corroborate an older or
undated exact copy. Contextual variants need dominant ordered shared prose,
media/reference evidence and known dates within 48 hours. Every pair in a collected
group must qualify. Display names and folders never establish ownership. A short
quoted-video repeat still needs its supported source/curator evidence, supplied
clip identity and distinctive contained quotation.

Duplicate original identities, unrelated content, replies, previews, unsupported
unknown-date matches, invalid archive pairs and oversized groups are rejected
before import changes. Each contributing author and original remains intact.
These checks are local; import does not fetch posts or certify their authorship.

anti social **0.35.3 / code 51** adds optional `Post.conversationId`, `relationId` and
`sourceParentId`, plus optional `ExtractionCoverage.replyParent`. These retain
source-supplied conversation/native reply references and known missing-parent
coverage; they are evidence identifiers, not permission to fetch a URL.
`PostSnapshot.relationship` optionally captures those references, the displayed
`parentId` and `parentCoverage` for an archived edition. Missing/null fields in
older captures mean unrecorded relationships. Supplied references must remain
strings and coverage values must use the supported extraction-status enum.
Earlier readers may reject exports containing these new fields.

anti social **0.31.0 / code 42** adds optional `Media.visualFingerprint` to captured
source editions. This is a local observation of an image already decoded by the
reader, not source-supplied metadata, AI evidence or authentication. It contains
no image bytes. Ordinary source text, media URLs and post identities are retained.
The bounded string format is
`f1|aspect10000|contrast100|horizontalHex|verticalHex|rgbHex`: aspect ratio and
luminance contrast use the indicated integer scales; the two hashes are unsigned
64-bit hexadecimal values; `rgbHex` is exactly 48 RGB channel averages encoded
as 96 hexadecimal characters. Unknown, malformed and oversized observations are
rejected. Missing/null observations remain valid for older captures.

Picture-corroborated variants can align shorter shared passages, including a
passage surrounded by different wording within a sentence. A separate related
coverage path supports quoted X posts with a common original publisher or proven
RSS curator, the same quoted account, shared subject wording, nearby interview
frames and dates no more than six hours apart. Every pair must qualify; proximity
cannot chain unrelated older and newer posts together. Composition preserves each
different passage and all discussion roots. Captured observations travel with
protected saved editions, so reopening does not depend on the expiring comparison
cache or require a source request. They remain reports of a prior local comparison;
import does not validate the remote pixels or certify semantic equivalence.
Earlier readers may reject captures containing this optional field.

When every caption aligns, the reader presents shared passages once with additional
context. A short quoted repeat can be valid while failing full passage alignment.
In that case, composition can use the most complete caption for each platform,
provided those captions still pass the cross-platform composition checks. This is
a presentation projection: every validated original remains in `fusionRoots`, the
saved source chooser, captured threads and subsequent exports. A one-platform
repeat can instead display its most complete original without claiming that its
other captions were rewritten into a fusion.

The envelope remains native JSON **version 1**; this extension adds no new required
field. Reading/importing same-platform multi-original captures requires anti social
**0.25.0 (Android version code 34) or later**. Earlier readers may reject these
captures because they enforced one source edition per platform. Ordinary saved
copies and earlier distinct-platform captures remain supported. Source packs and
OPML never contain saved source editions.

Media bytes and offline download files are not embedded in reader JSON. An imported saved text copy and captured thread remain locally useful; its media references are marked missing unless the receiving device already has the same validated local bytes. No download starts during import. Video download/storage budgets remain separate from this text/metadata transfer.

Saved posts and `PostSnapshot` records may include an optional nullable
`contextNotes` array (anti social 0.35.41). Each annotation contains required string
`text`, string `sourceUrl`, string-array `links`, and optional nullable string
`status`. Missing arrays in earlier exports remain valid. Current bounds are four
annotations per X post, 12,000 characters per annotation, 16 HTTPS links per
annotation, and 80 characters for a recorded status. Credentials and executable
URL schemes are rejected. The source must identify the same original X post;
an annotation on another post cannot be imported as its evidence. Status is a
recorded source claim, not a current rating verified by the receiving device.
An earlier snapshot does not inherit annotations from a later live edition.
Strict field/type validation still rejects unknown nested fields and scalar
coercions. Import does not fetch annotation sources. Older app versions that do
not recognize the field can reject such a newer export rather than discard it.

Copied AI metadata declarations remain readable, but an exported credential-verification report does not become this installation’s verification. Imported media reports reset signature status to unchecked and signer trust to not evaluated until available local bytes can be checked again.

Since 0.36.0-rc2, `AiMetadata.recipes` can retain structured generation records.
Each record contains `generator`, `use`, nullable `prompt`, `negativePrompt` and
`model`, a `parameters` object of at most 12 string-to-string entries, `signature`
and `carrier`. Prompt strings are bounded during capture. Imported provider
signatures become `UNCHECKED`; a copied `NOVELAI_SIGNATURE` observation becomes a
file-declared recipe. The ordinary acquisition/header cache is never exported.

`progress`, when present, has exactly `{version: 1, feeds: {...}, readKeys: [...], readAt: ...}`. `readAt` is a nonnegative Unix-millisecond timestamp (positive when shared read marks are present). `feeds` maps up to 32 local feed-context IDs to `FeedMemory` records. Context IDs and post keys are at most 512 characters; the whole progress section is at most 4 MiB. Each memory contains:

```
initialized: boolean
acceptedKeys: [post key]       # up to 1,000, no duplicates
stagedKeys: [post key]         # up to 1,000, disjoint from acceptedKeys
readKeys: [post key]           # up to 4,000, no duplicates
freshKeys: [post key]          # up to 1,000, accepted and not read
anchorKey: post key or null    # optional
anchorOffset: integer         # -1,000,000 through 1,000,000
anchorNeighbors: [post key]    # up to 8
resumeReturn: {key, offset, neighbors} or null   # optional
updatedAt: nonnegative Unix-millisecond integer
```

The top-level `progress.readKeys` holds up to 4,000 shared read marks. The schema comes from [`FeedReading.kt`](../core/src/main/kotlin/app/antisocial/core/FeedReading.kt) and [`FeedMemoryStore.kt`](../app/src/main/kotlin/app/antisocial/android/FeedMemoryStore.kt). A successful-refresh ledger, request tokens and transport status are deliberately absent. A receiving device therefore checks subscriptions itself; an unavailable source remains pending rather than inheriting a false “caught up” status. Reading progress can be omitted on export and declined on import.

## Import and merge semantics

The recipient first sees the source list and any warnings, selects source accounts individually, and separately chooses eligible state sections. Nothing is subscribed merely by opening or previewing a file. Accepting no sources can still import selected collection/rule/progress sections from a reader export.

Existing subscriptions are matched by their public platform/source identity. Existing local accounts, creator associations, names, enabled state and retrieval configuration win; importing a recommendation does not overwrite a recipient’s configuration. New source accounts receive local stable IDs. Explicit imported bundles can link newly accepted accounts, but never merge two already distinct local creator bundles without permission.

Channel rules are mapped to accepted/existing local creator IDs. Unselected or absent referenced creators receive explicit unavailable placeholders; these references are never removed into an empty positive selection. An identical existing channel (name plus rule fingerprint) is reused; conflicting local IDs receive new IDs. Selected channel rules can therefore remain visibly pending until the user supplies or edits the missing sources.

Collection names match ASCII case-insensitively, and existing collections keep their IDs. A saved post already present locally keeps its local captured content; accepted imported collection memberships are added. Existing saved copies are not overwritten. A newly imported saved copy gets its own validated local record and search index.

When optional reading progress is accepted, existing local context/anchor memories win. Only absent contexts are imported; shared read marks are combined within normal retention and storage limits. Creator/channel context IDs are remapped to accepted local identities; a context that cannot be mapped is not fabricated. Imported progress does not transfer cached feed posts, so an exact old anchor can only resolve when the corresponding content is locally available again.

## OPML behavior

Output is standard `<opml version="2.0">`, with a head/title, body, folder outlines and `type="rss"` feed outlines using `text`, `title` and `xmlUrl`. It contains no anti social extension attributes. Atom feed URLs use the conventional RSS subscription-list outline type; the actual document determines whether the source is RSS or Atom.

Input accepts OPML 1.0, 1.1 or 2.0, with `rss`, `atom`, or missing source type. It reads `xmlUrl` and `text` (falling back to `title`); it never follows `htmlUrl` to discover feeds. Nested folder labels become one deterministic label joined with ` / `, because the current reader has a single folder string rather than a folder tree. Re-export writes that complete label as one folder; it does not guess whether literal slashes were originally hierarchy. Repeated canonical feed URLs are shown once with a warning. Empty folders are not retained because they do not represent subscriptions.

FreshRSS `HTML+XPath`, `XML+XPath`, `JSON+DotNotation`, `JSONFeed` and similar custom types are not standard RSS/Atom imports. Such entries are skipped, even when their `xmlUrl` looks like an HTTPS URL. Extension attributes on an otherwise standard feed are ignored with a warning. Dynamic OPML links are not fetched.

## Bounds and safety

All files are UTF-8, at most 16 MiB. Malformed byte sequences and unpaired Unicode surrogates are rejected. JSON is strict, has at most 32 nesting levels and a bounded node count. OPML has bounded depth, nodes, attribute size and source count. DTDs and entity declarations are rejected; the XML resolver refuses external resources, and no imported network or parser extension is executed.

A format error does not partially subscribe to sources or change settings. Source preview and later application are separate steps. Files are not encrypted: use ordinary secure storage or an encrypted container when moving a personal reader export. A shareable source pack avoids personal reader state by construction.

Native reader preferences also include boolean `readableMentions` and
`mentionLookups` (both default true when absent). These control mention presentation
and optional deferred public-name retrieval. The disposable name bank is not
exported; original post text and account URLs remain the portable source records.

### YouTube media scope since 0.30

The `youtube` platform identifier remains valid for saved posts, media, attribution
and video comments. It is not a selectable subscription source. Older local channel
records are retained in full state exports and encrypted backups, but never refresh
or populate feeds. Source-pack export rejects those channel entries. Import preview
omits them from selectable sources with an explicit warning; saved YouTube text and
media metadata remain importable. Revalidation rejects manually selected channel IDs
before any writes. Social-source mapping keeps missing channel rules explicit.

Legacy `platforms` and `active` values containing `youtube` remain accepted as older
settings data. Effective tabs exclude YouTube, preserve the other selected sites'
order, and use social defaults if no supported site remains. YouTube's native video
link handler is independent of this tab selection.


### Saved discovery records and chosen areas (0.35.37)

`settings.localAreas` is a UTF-8 JSON **string** containing at most twelve area
objects. Each contains `id`, `name`, `label`, `point: {latitude, longitude}`,
`radiusKm` (integer 1–50), optional IANA `zoneId`, string-array `aliases` and
optional two-letter `countryCode`. Coordinates must be finite and in geographic
range. `settings.localArea` is the selected area's ID, or the empty string. These
are personal reader settings and require settings import to be selected; they
never enter source packs or OPML. No location lookup runs during import.

`statePayload.localDiscovery` contains exactly:

```json
{"version":1,"businesses":[],"events":[]}
```

Both arrays carry protected saved snapshots, not ordinary directory caches or
search history. There may be at most 2,000 combined records, with distinct opaque
IDs and a 256 KiB per-record allowance. An absent section imports no discovery
copies. The collections/saved-material import choice controls these records;
existing saved IDs are retained. Older readers that do not recognize the section
will reject it rather than silently drop saved material.

Business records contain `id`, `name`, `category`, `description`, `address`,
`locality`, optional `point`, arrays `websites`, `socials`, `phones`, `sourceIds`,
`evidence`, `keywords`, strings `hours` and `status`, and `checkedAt` milliseconds.
Social URLs identify the original account; retrieval frontend roots are not
business identities. A GERS identity is retained in `sourceIds` as
`overture:<GERS ID>`. An exported record's opaque local `id` can predate a later
GERS/OSM enrichment, so import must preserve both local ID and source aliases.

Event records contain `id`, `title`, `start`, optional `end`, `description`,
`venue`, `address`, `locality`, optional `point`, `organizer`, optional
`organizerUrl`, `urls`, optional `registrationUrl`, `price`, `age`, `status`,
`online`, `sourceIds`, `evidence`, `checkedAt`, optional `modifiedAt`, integer
`sequence`, optional `occurrence` and optional `image`. `status` is `scheduled`,
`cancelled`, `tentative`, `rescheduled` or `postponed`. `start`/`end` contain
`value` (ISO date or datetime), optional IANA `zoneId` and boolean `allDay`.
Date-only values remain date-only; an unknown timezone does not invent an offset.
An image URL is source metadata, not an exported image download.

Field evidence contains `field`, `value`, `sourceUrl`, retrieval-time `checkedAt`,
`kind`, `excerpt`, and optional `license`. `kind` is `PUBLISHER`, `CALENDAR`,
`DIRECTORY`, `PUBLIC_POST` or `POSTER`. Imported evidence preserves what a source
said and when it was retrieved; it does not independently verify it. Strings,
counts, coordinates, timestamps, enum values, URL authorities and required fields
are validated before any restore writes. The `POSTER` enum reserves provenance
for future OCR; it does not claim that poster extraction ships in this version.


Since 0.35.38, optional `settings.localDistanceUnit` is `"km"` or `"mi"`.
Stored `radiusKm` remains an integer; switching display units preserves it.
Business snapshots additionally accept optional nullable `emails` (up to eight
validated email addresses) and `related` (up to sixteen objects). Each related
object contains `name`, `role`, `accounts` and `sourceUrl`; roles are `owner`,
`operator`, `founder` or `source publisher`. These are attributed relationships,
not independent ownership verification. Older snapshots omitting both fields
remain importable; malformed field types are rejected. Availability caches and
provider backoffs are ordinary local cache and do not enter shared packs.

Since 0.35.43, a saved business may also contain nullable/absent `menus`, with
at most four source editions and 160 combined dishes. Each menu contains
`sourceUrl`, `title`, `channel`, `location`, `checkedAt`, `items`, `text`,
`partial`, `modifiedAt`, `fingerprint` and `sourceHash`. Channels are
`Published menu`, `Dine-in`, `Pickup`, `Delivery`, `Catering` or `Specials`.
`fingerprint` is a SHA-256 of the extracted edition; `sourceHash` is empty or
the downloaded PDF's SHA-256. These are content identities, not authenticity
certificates. `text` retains at most 20,000 characters from a menu document.
Each item contains `name`, `section`, `description`, `prices`, `dietary`,
`allergens`, `availability` and `options`; each price contains decimal-string
`amount`, supplied `currency` and optional-in-meaning string `label` (sizes).
Empty fields mean unknown. Import validates every nested type, source HTTPS URL,
field bound and combined item count. Saved menu text is independent of ordinary
route/cache expiry; original PDF binaries are not embedded in this JSON section.

The next release adds optional boolean `settings.localFlyerReading` (default true).
Local field evidence may contain a nullable/absent `region`: `imageUrl`, `sha256`,
`left`, `top`, `right`, `bottom`, `width`, `height`. Coordinates refer to the OCR
result image and must be bounded by its dimensions. The hash identifies the exact
processed source, not the image’s authenticity. Existing exports without regions
remain importable. Older app versions may reject snapshots using these new fields.
Unconfirmed announcements and OCR/route caches are ordinary expiring data and are
excluded from reader exports and shared source packs.
