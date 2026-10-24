#!/usr/bin/env python3
"""Rebuild the separately reviewed runtime pool. Does not read Darwin state or fetch URLs.

Primary contracts and rejected/gated leads live in source-discovery.json. A bundled
candidate starts UNKNOWN; only a matching content read can establish health.
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = []


def add(platform, frontend, root, role="ALTERNATE", scope="static", priority=0,
        fallback=True, references=(), note=""):
    SOURCES.append(dict(platform=platform, frontend=frontend, root=root, role=role,
                        scope=scope, priority=priority, fallbackOnly=fallback,
                        enabled=True, initialHealth="UNKNOWN",
                        sourceReferences=list(references), limitations=note))


FX = ("https://docs.fxembed.com/api/", "https://github.com/FxEmbed/FxEmbed")
for platform, root in (("twitter", "https://api.fxtwitter.com"), ("bluesky", "https://api.fxbsky.app")):
    add(platform, "fxembed-api", root, priority=-8, fallback=False, references=FX)
add("twitter", "fxembed-api", "https://api.fixupx.com", priority=8, references=FX,
    note="Documented FxEmbed alias; shares its backend and is not an independent copy. Separate connection health remains unknown.")
for platform, root in (("twitter", "https://fxtwitter.com"), ("twitter", "https://fixupx.com"),
                       ("bluesky", "https://fxbsky.app")):
    add(platform, "fxembed-rss", root, references=FX, note="Public profile feeds; shared FxEmbed upstream, not independent data.")
add("twitter", "vxembed-api", "https://api.vxtwitter.com", references=("https://github.com/dylanpdx/BetterTwitFix",), note="Individual posts only.")
add("instagram", "anonimogram-api", "https://api.anonimogram.com", priority=7,
    references=("https://anonimogram.com/", "https://anonimogram.com/about"),
    note="Public profile metadata and on-demand caption/preview snapshots. Current posts omit dates; video rows can contain only JPEG posters. No feed freshness, global search, exact-post or full-video claim. Anonymous lookup quotas apply; no login token, cookie or rotating quota identity.")
add("twitter", "sotwe-api", "https://www.sotwe.com", priority=-4, fallback=False,
    references=("https://www.sotwe.com/NASA", "https://github.com/DIYgod/RSSHub/tree/master/lib/routes/sotwe"), note="Public user JSON; undocumented and source dependent.")
add("tumblr", "tumblr-legacy", "https://www.tumblr.com", "ORIGINAL", priority=-6,
    references=("https://www.tumblr.com/docs/api/v1",), note="No-key public blog read API; actual selected blog subdomain.")
for platform, frontend, root, refs in (
    ("lemmy", "lemmy-rss", "https://lemmy.world", ("https://github.com/LemmyNet/lemmy",)),
    ("misskey", "misskey-rss", "https://misskey.io", ("https://github.com/misskey-dev/misskey",)),
    ("misskey", "misskey-atom", "https://misskey.io", ("https://github.com/misskey-dev/misskey",)),
    ("misskey", "misskey-discussion", "https://misskey.io", ("https://github.com/misskey-dev/misskey",)),
    ("pixelfed", "pixelfed-public-api", "https://pixelfed.social", ("https://github.com/pixelfed/pixelfed",)),
    ("pixelfed", "pixelfed-activitypub", "https://pixelfed.social", ("https://github.com/pixelfed/pixelfed",)),
    ("pixelfed", "pixelfed-public-comments", "https://pixelfed.social", ("https://github.com/pixelfed/pixelfed",)),
):
    add(platform, frontend, root, "ORIGINAL", "selected-origin", priority=-4,
        references=refs, note="Uses only the selected federated server. Instance IDs are never interchangeable mirrors.")
add("pinterest", "pinterest-widgets", "https://widgets.pinterest.com", "ORIGINAL", priority=-7,
    references=("https://gist.github.com/kentbrew/8744482", "https://help.pinterest.com/en/business/article/build-a-website-widget"),
    note="Profiles, boards and selected pins; no publication dates, so no chronological subscription refresh.")
add("threads", "fxthreads-json", "https://fx.akitsuki.me", references=("https://github.com/AkitsukiNagi/FxThreads",), note="Individual posts only; instance-dependent, example returned 404.")
add("instagram", "oginstagram-activity", "https://oginstagram.com", priority=-6, fallback=False, references=("https://github.com/seirenkr/OGInstagram",), note="Selected HTML identity plus advertised same-origin ActivityStreams Note; two requests at most.")
add("tiktok", "tiktok-creator-embed", "https://www.tiktok.com", "ORIGINAL", priority=-5,
    references=("https://developers.tiktok.com/doc/embed-creator-profiles/",), note="One public creator document; undated cards are on-demand only, never feed refresh proof.")
add("tiktok", "tikwm", "https://www.tikwm.com", priority=-6, fallback=False, references=("https://www.tikwm.com/",), note="Individual public posts only; profile API returned 403. Honors quota backoff.")
for frontend in ("tikwm-search", "tikwm-photo-search"):
    add("tiktok", frontend, "https://www.tikwm.com", priority=-6, fallback=False, references=("https://www.tikwm.com/",), note="Public keyword search with query-bound cursors; separate photo/video route health. Source-selected results, locally chronological. Challenges and quotas remain failures.")
add("tiktok", "tikwm-comments", "https://www.tikwm.com", priority=-6, fallback=False,
    references=("https://github.com/AndreyQq/Tiktok-Video-No-Watermark",), note="On-demand public comments for an exact post; separate from the post reader. No unverified nested-reply or popularity-order claim.")
add("snapchat", "snapchat-embed", "https://www.snapchat.com", "ORIGINAL", priority=-4,
    references=("https://developers.snap.com/embed",), note="Exact public Spotlight post embed; not private snaps or accounts.")
add("telegram", "telegram-rss", "https://tg.i-c-a.su", references=("https://github.com/xtrime-ru/TelegramRSS",), note="Independent public channel relay; observed 429, normal Retry-After/backoff applies.")
add("baiduTieba", "tieba-client", "https://tieba.baidu.com", "ORIGINAL", priority=-4,
    references=("https://github.com/n0099/tbclient.protobuf", "https://github.com/Starry-OvO/aiotieba"), note="Public client endpoints only; no user session, BDUSS or persistent device identifier.")
add("reddit", "arctic-shift", "https://arctic-shift.photon-reddit.com", "CACHE",
    references=("https://github.com/ArthurHeitmann/arctic_shift",), note="Historical recovery only, after live routes. Never confirms subscription freshness or trains heartbeat winners.")
add("reddit", "arctic-shift-comments", "https://arctic-shift.photon-reddit.com", "CACHE",
    references=("https://github.com/ArthurHeitmann/arctic_shift",), note="On-demand historical thread recovery, separate from the one-request post reader. No original popularity-order claim.")
add("truthsocial", "truth-archive-rss", "https://www.trumpstruth.org", "CACHE",
    references=("https://www.trumpstruth.org/",), note="Only realDonaldTrump, the archive's public recent 100-item RSS snapshot. No invented capture date or feed freshness.")

# Shitter preserves the Nitter reader format. These two maintainer-listed roots
# supplied two dated, disjoint NASA HTML windows through Gateway on October 24.
# RSS redirected to HTML: this is not a public Min-Id or independent-upstream claim.
for root in ("https://nitter.meowing.monster", "https://nitter.mie.foundation"):
    add("twitter", "nitter", root, priority=-1, fallback=False,
        references=("https://codeberg.org/mv12star/shitter/raw/branch/master/instances.json",),
        note="Shitter/Nitter-compatible public deployment. October 24 direct Gateway check read two dated 20-post NASA HTML windows without overlap. RSS redirected to HTML; header pagination, other targets and live Tor are not established. Starts UNKNOWN; normal content checks and backoff apply.")

# Additional maintainer-listed deployments of existing Darwin reader formats.
# A failed sample is retained as an UNKNOWN candidate, never seeded as healthy.
for platform, frontend, root in (
    ("instagram", "kittygram", "https://gram.utilibre.org"),
    ("pinterest", "painterest", "https://pain.thirtysix.pw"),
    ("pinterest", "painterest", "https://pin.dc09.ru"),
    ("pinterest", "painterest", "https://painterest.franklyflawless.org"),
    ("reddit", "redlib", "https://redlib.cow.rip"),
    ("reddit", "redlib", "https://redlib.reallyaweso.me"),
):
    add(platform, frontend, root, priority=9,
        references=("https://raw.githubusercontent.com/libredirect/instances/main/data.json",),
        note="Maintainer-listed public deployment of an existing Darwin reader format. Sample was unavailable, blocked, timed out or target-missing on the audit connection. Candidate only; normal failure backoff and TLS checks apply.")

# Only hosts which served valid TLS and advertised these bridge forms in this
# audit. A listed bridge still has to return correctly scoped dated content.
BRIDGE_PLATFORMS = ("twitter", "bluesky", "reddit", "instagram", "facebook", "pinterest", "telegram", "tiktok", "gettr", "gab", "mastodon")
BRIDGE_HOSTS = {
    "https://rss-bridge.org/bridge01": BRIDGE_PLATFORMS,
    "https://rss-bridge.lewd.tech": BRIDGE_PLATFORMS,
    "https://wtf.roflcopter.fr/rss-bridge": BRIDGE_PLATFORMS,
    "https://rss-bridge.ggc-project.de": BRIDGE_PLATFORMS,
    "https://rss.bloat.cat": BRIDGE_PLATFORMS,
    "https://ololbu.ru/rss-bridge": ("twitter", "reddit", "telegram"),
    "https://feed.eugene2molotov.ru": ("instagram", "telegram"),
    "https://rb.ash.fail": ("mastodon", "telegram"),
}
PROVEN_FEEDS = {
    ("telegram", "https://rss-bridge.org/bridge01"),
    ("bluesky", "https://wtf.roflcopter.fr/rss-bridge"),
    ("instagram", "https://rss.bloat.cat"),
    ("gettr", "https://rss.bloat.cat"),
    ("gab", "https://rss-bridge.lewd.tech"),
    ("mastodon", "https://wtf.roflcopter.fr/rss-bridge"),
}
for host, platforms in BRIDGE_HOSTS.items():
    for platform in platforms:
        tested=(platform,host) in PROVEN_FEEDS
        add(platform, "rss-bridge", host, priority=-3 if tested else 5, fallback=not tested,
            references=("https://rss-bridge.github.io/rss-bridge/General/Public_Hosts.html", "https://github.com/RSS-Bridge/rss-bridge/tree/master/bridges"),
            note="Public feed renderer; operator may require upstream credentials or disable a bridge. No user credential or fee. Host availability does not prove this platform works.")
for host in ("https://rsshub.rssforever.com", "https://rsshub.woodland.cafe", "https://rsshub.ktachibana.party", "https://rss.noleron.com", "https://rsshub.app"):
    for platform in ("twitter", "bluesky", "tumblr", "instagram", "pinterest", "threads", "linkedin", "tiktok", "gettr", "gab", "telegram", "mastodon", "lemmy", "misskey", "baiduTieba"):
        tested=(platform,host) in {("threads","https://rsshub.ktachibana.party"),("lemmy","https://rsshub.rssforever.com")}
        add(platform, "rsshub", host, priority=-3 if tested else 6, fallback=not tested,
            references=("https://docs.rsshub.app/guide/instances", "https://github.com/DIYgod/RSSHub/tree/master/lib/routes"),
            note="Public renderer, target/route scoped, no pagination claim. Operator configuration, rate limits and upstream authentication determine availability. No client secrets or account.")

keys = [(s["platform"], s["frontend"], s["root"]) for s in SOURCES]
assert len(keys) == len(set(keys))
catalog = dict(schemaVersion=1, reviewedOn="2026-10-24", donor="Darwin",
               policy="Alternate live paths, optional accountless originals, then independent historical recovery. All networking through NetworkGateway; health is learned from content, never bundled.",
               sourceInventory="source-discovery.json", sources=SOURCES)
path = ROOT / "app/src/main/assets/public-pathways.json"
path.write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + "\n")
print(f"Wrote {len(SOURCES)} supplemental scoped source registrations to {path}")
