# Search interpretation

anti social uses a custom Kotlin interpretation and query-compilation layer. No
AI, search server or additional dependency is required. Darwin remains the routing
donor: interpretation supplies bounded queries to its existing protected sources,
streaming collectors, capability checks and deduplication.

## Reader controls

Unquoted searches can use conservative plural forms, known category aliases and
explicit concept alternatives. The entered wording stays available. For example,
`indian food` can retrieve a place through its published cuisine classification,
even when its name omits “Indian”; `rocket launch` can find saved text saying
“spaceflight”. These are finite, inspectable vocabulary rules, not unrestricted
language understanding or generated business facts.

Put a phrase in double quotes to keep its wording and suppress concept, spelling
and morphology expansion for that phrase: `"rocket launch"`, `"Acorn Diner"`.
Names, handles, numerical identifiers and field-like literal text are protected
from generic rewriting. A leading minus excludes a word or phrase in the shared
local/scoped matcher. Business and event adapters also retain their existing
mode-specific exclusions and constraints. Exclusions remain attached to alternate
paths. Quoted event text is retained before the audience/date parser runs.

The existing event parser still handles its supported audience, free-admission,
setting and date facets. This increment does not claim a new general date parser,
opening-hours interpreter or inferred dietary/age suitability.

## Shared foundation and scoped knowledge

`SearchIntent` retains literal clauses, phrases, exclusions and protected terms.
`SearchText` supplies bounded Unicode analysis and conservative English morphology.
`SearchMeaning` builds at most six complete interpretation paths. Fixed compound
phrases such as “ice cream” remain phrases; synonym words are not flattened into
independent requirements. One bounded document tokenization can serve all paths.
The previous full literal scan remains available in scoped public-feed search.

`PlaceSearchVocabulary` compiles 518 public place concepts from iD's tagging schema,
including corresponding OSM tag predicates. Strong aliases and related indexing
terms are retained separately. Related terms are not silently promoted to
synonyms, confirmed offerings or attributes. Cuisine interpretation stays in the
business domain: “Indian Example Coffee” does not become an Indian restaurant, and
bare nationalities do not become restaurant queries in general social/Library
search. Unambiguous one-edit category suggestions supplement an original business
query; unknown general topics do not receive business-category spell correction.

The small general concept vocabulary adds useful remembered-wording alternatives
for areas such as transport, repairs, employment, research, weather and public
announcements. Public search can use at most one additional concept query. Queries
with quotes, handles or unsupported provider syntax retain the literal path.

## Source compilers and limits

| Surface | Behavior |
| --- | --- |
| Business discovery | Literal name searches plus native category/cuisine predicates and a bounded alternative public query; the same interpretation admits and ranks acquired records. Geographic admission remains unchanged. |
| Events | Shared lexical interpretation with the existing audience, admission, geographic and occurrence-window checks; quoted phrases survive extraction. |
| Menus | Shared vocabulary and morphology over actual retained dish names, sections and descriptions; menu variants and published prices remain separate. |
| Library | Original/morphological paths form the first result bank; related wording forms a second bank. Each keeps the existing 200-owner bound, collection/saved/history controls and local-only reads. Direct wording stays ahead of related results, with dates retained within each group. |
| Selected public social index | The concrete original provider queries remain first. One additional concept query can participate in the existing streaming lane scheduler, with four query lanes maximum. Native sites retain their own query capabilities. |
| Scoped public feed | Original literal matches plus bounded concept/morphology paths, within the existing page/post/byte limits; source ownership and opaque pagination remain intact. |
| Account directories and source health | Account identity queries stay literal. Calibration uses one literal probe rather than running reader-intent expansion. |

Android FTS4 syntax varies: implicit AND combined with OR did not preserve complete
query paths in native qualification. Library therefore binds each complete MATCH
expression separately and combines row IDs with ordinary SQL UNION. Exclusions
use bounded SQL set subtraction. The shared SQL template retains the same owner
fairness, collection gates, saved/history selection and result limits. User text
never becomes SQL syntax. No index migration or rewrite of saved records is needed.

Source continuations remain bound to the selected source, canonical target and
concrete frozen query list. Regional cache versions were advanced for the changed
query plan; existing source failures and account-validation backoffs stay intact.
Ranking is computed once per candidate projection instead of inside each sort
comparison. Immutable vocabulary is loaded once; private plan/correction memo
banks are small, local, bounded and expire after fifteen minutes. Ordinary cache
cleanup trims them; explicit cache clearing removes them. No query heartbeat or idle crawl is introduced.

## Sources and reproducibility

The place vocabulary comes from [iD's tagging schema](https://github.com/openstreetmap/id-tagging-schema/blob/1bd56321fc3d299ad7f0714d80eaea9ec485e112/SCHEMA.md)
at commit `1bd56321fc3d299ad7f0714d80eaea9ec485e112`, under ISC. Rebuild it with
`scripts/compile_place_search.py`; the pinned upstream archive lives in the external
build root and is SHA-256 checked. The committed JSON retains its source revision,
archive hash, category tags, aliases and related terms.

The morphology adapts [Lucene's EnglishMinimalStemmer](https://github.com/apache/lucene/blob/main/lucene/analysis/common/src/java/org/apache/lucene/analysis/en/EnglishMinimalStemmer.java)
(Harman's S-Stemmer, Apache-2.0), with explicit irregular-word rules. Phrase-aware
rewriting and separate source compilers follow useful ideas exemplified by
[Querqy Unplugged](https://github.com/querqy/querqy-unplugged). The runtime is native
project code; the large frameworks, neural rerankers and corpus-expansion stacks
from the research catalog are not bundled. Notices and the Apache license are
included in `licenses/search-NOTICE.txt` and packaged `search-notices.txt`.
