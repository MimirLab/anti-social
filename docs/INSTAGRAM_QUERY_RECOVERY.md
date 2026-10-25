# Instagram query recovery and reuse, 0.38.25

The public reader can recover a changed readonly query when the selected public
page declares its replacement. It now recognizes the observed persisted-document
failure as well as the earlier error forms. Every error in this new form must
name the exact document ID just requested. A missing post, a different document,
mixed errors, authentication, a challenge or a rate limit cannot authorize this
new recovery path.

Recovery remains one selected-page read and one retry. The page must declare
actor zero, the exact operation and selected username/media ID. The request stays
inside NetworkGateway's current connection, origin, permission and aggregate
budgets. Continuations keep their issuing query; a protocol change cannot move
an old provider cursor into a replacement query.

## Remember the proven request, not the response

After usable selected content passes its identity and extraction checks, the
adapter can remember the replacement's query metadata. Another fresh head read
then uses that query directly and still obtains a new protected response. This
is separate from post, comment and cursor storage; it never supplies old content
as evidence of a successful refresh.

The bank is memory-only, bounded to 16 entries per adapter and a ten-minute
absolute lifetime. Reusing the same template does not extend that lifetime.
Its keys include the full source, target, default operation/input shape and
actual Gateway session object. A direct/Tor/direct round trip cannot revive an
old entry. Obsolete work cannot admit or remove metadata in the new session,
and an old failure cannot remove a different replacement learned meanwhile.
Clock rollback and expiry discard old entries.

Only the document identifier and bounded boolean Relay-provider declarations
can differ from the default request. The target, order, window and other inputs
stay fixed; a logged-in flag cannot become true. Callers receive deep copies.
Malformed, foreign or empty content cannot seed the bank, and declarations alone
never qualify an entry. Ordinary successful default requests do not add entries
or make an extra request. Source pauses and original-site/Tor policy still apply
to every actual read.

## Checks

A new Gateway regression first failed on the previous implementation's handling
of the literal persisted-document error. A second showed that two successful
fresh reads repeated recovery and cost six requests. With reuse they cost four:
the first read uses three, the next uses one and returns a different comment ID.
Tests also cover isolation, absolute expiry, bounds, copies, connection changes,
stale failures, unsafe variables, payload rejection and the single-retry limit.

The owned Android fixture failed against the retained 0.38.24 app with the same
query error. The candidate renders the recovered comment in All, then presses
the Instagram tab's actual Refresh control and renders a newly supplied comment.
The recorded requests are POST, selected-page GET, POST, POST, with no cookie or
authorization header. The native screen was inspected. This is protocol and UI
qualification using an owned server, not a live provider availability guarantee.

A separate live API 35 native journey returned 14 public comments in All and 26
distinct comments after pressing More. The actual native screen was inspected.
The full regression completed 2,171 JVM cases with no failures/errors and one
optional model-weight skip, and 77 Android methods with 76 passes, one opt-in
live-Tor skip and no failures. Live provider results are dated observations;
the replacement-query behavior itself is proved by the owned drift fixture.

## Child-reply research remains separate

The zero-star [instapick source](https://github.com/andresayac/instapick/blob/main/server.js)
provided a separate child-query lead. Its document identifier was rejected as
missing in a direct Android probe using parent IDs actually returned for the
selected post. The parent response supplied null child counts, not evidence that
those comments had no replies.

Passive inspection of four static bundles declared by the public page found
Instagram's own `PolarisAPIGetChildComments` read-only REST path. The bounded
Android probe of that path received HTTP 200 HTML navigation content with no
child connection; it was rejected as unusable. No site script executed, no login
state was used and no child-reply capability was registered. Neither a repository
claim nor a responding endpoint establishes access to the missing replies.
The current public reader's [coverage limits](INSTAGRAM_PUBLIC_COMMENTS.md#coverage)
remain in force. These experiments, raw responses and intermediate failures are
retained externally under `evidence/instagram-child-comments-20261025`.

### Later child-query lead, October 25

Exact code search found a different child document identifier in
[reels](https://github.com/njyeung/reels/blob/1f4be97921070c46a00827b3fc26e53c37551e71/backend/graphql.go)
and [dumpsta-gram](https://github.com/stafawashere/dumpsta-gram/blob/6dffc13bbd7d98c4075df25548fb941aa14d1fd7/engine/dumpstagram/_private/web/documents/media.py).
Their inspected collectors use captured logged-in sessions; public repository
access and public method names do not establish anonymous source access. A
bounded anonymous Android Gateway probe used a parent ID from its own current
selected-post response, then received an explicit rate-limit error on the first
child read and stopped. No additional child request, login or identity rotation
followed. That response does not prove the document exists or that the selected
parent has no replies. No child capability was registered. The first-page and
continuation operation leads, raw source hashes and negative live result remain
external under `evidence/instagram-child-query-leads-20261025`.
