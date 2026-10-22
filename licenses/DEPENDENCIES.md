# Dependency notices

anti social’s source is GPL-3.0. Third-party components retain their own licenses.
This file identifies primary source locations; dependency source and license files
are also distributed by their upstream projects and Maven/Cargo artifacts.

| Component | License | Source |
| --- | --- | --- |
| Darwin rebuilt and expanded routing system | GPL-3.0 | Imported Darwin routing snapshot; assets/provenance.json |
| CommentAnywhere.app (protocol reference) | Independent anti social implementation GPL-3.0-or-later; upstream extension license not identified | https://commentanywhere.app/ ; public Chrome package 0.14.0, ID giekeccmneacnddaemhafmnlcmjioihm. No extension code/runtime is distributed. Protocol provenance in commentanywhere-provenance.json. |
| Search by Image (public website form reference) | GPL-3.0 | https://github.com/dessant/search-by-image ; native image-search form handling references this project's public upload flows; no extension or browser runtime is bundled |
| ClearURLs rules | LGPL-3.0 | https://github.com/ClearURLs/Rules |
| adblock-rust | MPL-2.0 | https://github.com/brave/adblock-rust |
| Tor / tor-android | BSD-3-Clause | https://gitlab.torproject.org/tpo/core/tor and https://github.com/guardianproject/tor-android |
| jtorctl | BSD-3-Clause | https://github.com/guardianproject/jtorctl |
| AndroidX Compose, Room, WorkManager, Lifecycle, Media3 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin and kotlinx.coroutines | Apache-2.0 | https://github.com/JetBrains/kotlin and https://github.com/Kotlin/kotlinx.coroutines |
| OkHttp | Apache-2.0 | https://github.com/square/okhttp |
| Gson | Apache-2.0 | https://github.com/google/gson |
| PdfBox-Android 2.0.27.0 (passive embedded menu text) | Apache-2.0 plus included font/encoding notices | https://github.com/TomRoush/PdfBox-Android/tree/v2.0.27.0 ; full upstream LICENSE/NOTICE in assets/restaurant-menu-notices/. Transitive encryption/signing libraries are excluded. No PDF JavaScript, actions or attachments are executed. |
| Content Authenticity Android / Rust C2PA reader | Apache-2.0 / MIT | https://github.com/contentauth/c2pa-android ; pinned artifact and notices in c2pa-provenance.md |
| kotlinx.serialization | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization |
| jsoup | MIT | https://github.com/jhy/jsoup |
| biweekly 0.6.8 / vinnie 2.0.2 | BSD-2-Clause; bundled Apache-2.0 recurrence/codec notices | https://github.com/mangstadt/biweekly ; https://github.com/mangstadt/vinnie ; full texts in assets/local-discovery-notices/ |
| Jackson Core 2.16.1 | Apache-2.0 | https://github.com/FasterXML/jackson-core ; bundled local-discovery-notices/ |
| Rust JNI | Apache-2.0 / MIT | https://github.com/jni-rs/jni-rs |
| serde / serde_json | Apache-2.0 / MIT | https://github.com/serde-rs/serde and https://github.com/serde-rs/json |

The donor filter collection combines separately licensed source lists. Original
headers and source URLs are preserved in filter-notices.txt; assets/filter-sources.json
and filter-seed-status.json record the exact downloaded collection. Network rule
deduplication and hosts-file normalization are the local modifications.

Platform vector marks are bundled locally for all 21 services, with theme-aware monochrome tint and optical sizing. Sixteen are adapted from Simple Icons (CC0-1.0), three from Arcticons (CC-BY-SA-4.0), and two are anti social’s own unofficial vector interpretations (GPL-3.0-or-later). Exact sources, revisions, changes, attribution and licenses are in [platform-icons.md](platform-icons.md). Brand trademarks remain with their owners. No icon requests are made at runtime.

YouTube support adds NewPipe Extractor v0.26.5 (GPL-3.0-or-later), NewPipe
nanojson (Apache-2.0), protobuf-javalite (BSD-3-Clause), JSR305 annotations
(BSD-3-Clause), and Mozilla Rhino/rhino-engine 1.8.1 (MPL-2.0). Jsoup retains
its MIT license. Source/artifact coordinates are fixed in core/build.gradle.kts.
SponsorBlock annotations are separate service data under CC BY-NC-SA 4.0;
see sponsorblock-data-NOTICE.txt. No SponsorBlock extension code is embedded.

Offline Context language tools add pinned Mozilla/Bergamot inference (MPL-2.0),
Marian and its bundled dependencies, fastText (MIT), PCRE2 (BSD-3-Clause),
SentencePiece (Apache-2.0), RUY (Apache-2.0), intgemm (MIT), and associated pinned
CPU/matrix libraries. Exact source revisions, archive hashes and dependency
roles are in `native/translation/sources.json`; complete upstream license files
are retained in `app/src/main/assets/translation-notices/`. The source package
includes those original archives and anti social’s reproducible build adaptations.
The unchanged compressed `lid.176.ftz` detector has a separate CC BY-SA 3.0
license, attribution, training-data credits and full legal text in that notices
directory. Mozilla language weights use MPL-2.0 and are downloaded separately
from the exact source URLs and hashes in the signed translation catalog.
