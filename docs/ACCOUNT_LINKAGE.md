# Cross-platform subscription discovery

“Subscribe locally” starts with the profile already on screen. Its account can be
saved immediately while more suggestions arrive. A failed profile refresh does
not terminate the other discovery lanes. Names edited by the reader and checkbox
choices survive later results. Additional accounts remain unselected until the
reader chooses them; a shared username never automatically establishes ownership.

## Retrieval

The native engine uses these sources:

- The visible/cached profile, its explicit account links, and existing local
  subscription bundles.
- Up to three linked publisher/link-in-bio pages. Identity attributes are read
  before discarding the page: `sameAs`, `rel=me`, relevant header/footer links,
  and explicit links on recognized link hubs. Article mentions, share controls,
  and another article's publisher/author accounts do not become the subject's
  accounts. Only one additional link-hub hop is followed.
- [Wikidata's public API](https://www.wikidata.org/w/api.php): an indexed exact
  account-property search followed by claims for at most three entities.
  The returned seed account must agree. Deprecated statements and associations
  with an end-time qualifier are excluded. An explicitly preferred seed statement
  can resolve competing entities; equally ranked matches remain ambiguous.
  These are directory assertions, not independently verified common control.
- [Keybase's public lookup](https://keybase.io/docs/api/1.0/call/user/lookup): X and
  Reddit seeds can obtain active associations from one exact matching result.
  Labels say “Associated by Keybase”; the app does not claim it independently
  verified Keybase's cryptographic proof chain.
- “Find more accounts” checks at most four matching usernames on supported social
  sites, ordered using the reader's platform selection. Darwin's existing source
  pool checks for an actual named profile and handles alternate routes, source
  health, original fallback and login walls. A supplied link back to the seed is
  labeled separately from a matching username.

[Maigret](https://github.com/soxoj/maigret),
[WhatsMyName](https://github.com/WebBreacher/WhatsMyName), and
[Sherlock](https://github.com/sherlock-project/sherlock) informed the bounded
username/linked-alias discovery approach. Their executables and global enumeration
lists are not bundled: the app already has native supported-platform readers and
canonical profile routes. No upstream detector code or dataset was copied.
Keyoxide cryptographic verification, paid enrichment services and Gravatar's
key-based API are not dependencies of this implementation. Other publisher and link-in-bio pages explicitly linked by a profile can supply
supported identity links where available.

All requests use NetworkGateway, its protection filters and current transport.
Selected Tor fails closed. HTML parsing is inert: no JavaScript, widgets, embedded
assets or browser cookies execute. Only typed identity observations are retained;
no HTML or Markdown scratch file is created. No AI model or root access is needed.

## Scheduling and storage

On-demand work uses two acquisition lanes, a 20-second overall limit (30 with
Tor), six-second individual lanes (ten with Tor), and an aggregate budget of eight
requests including redirects and 4 MiB of response bodies. Individual documents
are capped at 512 KiB. Fast suggestions publish as soon as they arrive. Closing or
saving the dialog cancels unfinished discovery rather than letting it overwrite
the saved selection.

A deliberate profile open also permits a small head start after 900 ms: if no
foreground read/refresh is active, one linked identity page may be prefetched.
This lower-priority request yields to active reading, allows two requests including
redirects and 256 KiB, and lasts at most three seconds (five with Tor). Leaving the
profile cancels it. There is a ten-second global admission gap and a fifteen-minute
per-page retry gap. This does not scan accounts or archives in the feed. The result
uses the same typed website cache as explicit discovery, avoiding a second read.

The persisted ordinary association cache is limited to 128 records and 1 MiB.
Positive discoveries expire after six hours; seed-only discoveries after fifteen
minutes. Files untouched for two days expire. Current local subscription membership
is recomputed when cached suggestions are used. The shared in-memory website cache
holds at most 64 typed observations / 256 KiB for thirty minutes; page HTML is not
kept. Cache clearing invalidates late writers. Transport policy and visible-profile
identity changes separate cache keys. Subscription state remains local and protected
from ordinary cache eviction.

## Shared readable names

Discovery's exact public profile reads also seed the existing mention-name bank.
Named website `sameAs` statements and the matching Wikidata entity's English
label supply lower-priority fallbacks, including when a profile-name read failed.
Reader-confirmed subscription bundles can share already observed public names;
the subscription's editable title never becomes a public account name. Resolving
a name later also fills missing names in that confirmed bundle. A bio link or
matching username alone cannot transfer one person's name to another account.

An account's own observed name always wins, including its platform-specific label.
Fallbacks carry their public source and relationship kind in the same bank. They
do not count as successful profile checks, reset miss backoff, or start new network
requests. Normal visible-demand name lookup can replace them when available.
Only fresh, valid identity observations are admitted. Existing 1,024-entry / 1 MiB
limits, thirty-day unused expiry and cache clearing apply to these names too.
The database migration retains previously observed profile names. Raw captions
and authentic hyperlink targets remain unchanged.

## Qualification boundaries

A captured public Wikidata response for the White House yielded eleven supported
account suggestions across seven platforms, including differently named accounts.
The captured Keybase response yielded matching X and Reddit associations. Some
Wikidata statements still describe older or language-specific accounts without
explicit end dates; the directory label does not promise current common control.
A live SPARQL check was rate-limited during qualification; the implementation uses
the working regular API instead. These point-in-time acquisitions do not establish
an availability guarantee for either service or every returned social account.

The 0.36.0-rc4 candidate passed 1,773 JVM tests (one optional model-weight test
skipped), 52 focused Android checks, 35 subscription/feed/import/transport
regressions, and one explicit live gateway acquisition test. The live test also
confirmed that the matched Wikidata entity returned reusable name hints; Keybase
returned its X/Reddit associations. Android 15 emulator checks covered name-bank
migration, persistence, direct-name precedence, failed-name backoff, offline
presentation and cancellation. Native dialog/name-reader screens were inspected.
This is laptop qualification, not a physical-phone performance measurement.
