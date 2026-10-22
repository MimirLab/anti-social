#!/usr/bin/env python3
"""Build AntiSocial's data-only source discovery inventory from the 21 research records.

GPL-3.0-or-later. This does not activate endpoints or change Darwin's donor data.
The evidence directory lives on LiberBuild storage and contains no app settings.
Only curated source metadata is packaged; raw HTTP payloads are never copied.
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_EVIDENCE = pathlib.Path(
    "/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/evidence/source-cartography-2026-10-17"
)
PLATFORMS = [
    ("twitter", "X", "protocols", "X has several public data providers. FxEmbed's typed JSON, Sotwe's public account JSON and syndication formats complement Darwin's Nitter routes. Provider aliases can share one backend; they do not each add an independent copy of X."),
    ("bluesky", "Bluesky", "protocols", "Bluesky exposes public AppView, profile RSS and AT Protocol repository data. These serve different purposes: a PDS owns account records, an AppView assembles threads and search, and a relay or live stream is not a ready-made post reader. FxEmbed adds a separate public delivery path."),
    ("reddit", "Reddit", "protocols", "Redlib, Teddit, original JSON and native feeds are live-reading possibilities with different access limits. Arctic Shift and PullPush are historical databases. Their records can recover a missing post but cannot establish that a subscription is current."),
    ("tumblr", "Tumblr", "protocols", "Tumblr's public blog RSS and legacy JSON remain useful alternatives to its main page. Priviblur and embed services offer additional paths. Official v2 API keys and dashboard-only content are separate access constraints; finding an API does not make it accountless."),
    ("tiktok", "TikTok", "large", "Public creator and post embeds, alternate frontends and selected-post JSON providers expose different subsets of TikTok data. A playable preview can be useful without proving that a full profile, comments or chronological subscription feed is available."),
    ("instagram", "Instagram", "large", "Instagram has frontend pools, public page and embed representations, and experiments that publish selected content through another protocol. Identity, gallery ownership and real media must be checked for each path; a login page or branded shell is not content."),
    ("pinterest", "Pinterest", "large", "Pinterest's public widget endpoints provide profile pins, board pins and selected-pin data. They are fast on-demand sources, but the observed records lack post dates. AntiSocial must not invent dates or use those records as proof of a chronological subscription refresh."),
    ("ifunny", "iFunny", "other", "Public pages, feeds and frontend ideas are recorded alongside unofficial client protocols. Application credentials or unverified wrapper code do not automatically become accountless public routes. Availability and complete media remain source dependent."),
    ("baiduTieba", "Tieba", "other", "Tieba has public browser pages, forum RSS generators and a distinct client data protocol. Native readers must verify the selected forum or thread, keep real reply relationships, and reject unrelated cards or empty successful responses."),
    ("threads", "Threads", "large", "Threads exposes public page, embed and alternate frontend representations. Some are thin wrappers over the same Meta upstream. Official developer interfaces and public page data have different authentication and completeness limits."),
    ("facebook", "Facebook", "large", "Facebook's public pages, mobile variants, plugins and alternative readers cover different public content. Tokenless oEmbed URLs that return only JavaScript remain unusable candidates. A source must supply the selected post's actual text or media before it earns health evidence."),
    ("truthsocial", "Truth Social", "other", "Truth Social's public API resembles Mastodon but has its own availability and discussion endpoints. Independent archives may cover only a particular public figure. Their narrow actor scope and historical provenance must remain visible."),
    ("mastodon", "Mastodon", "protocols", "Mastodon offers public REST, RSS, ActivityPub and identity discovery. Authorized Fetch can close a public federation endpoint to unsigned clients. Another federated server holds only the content it knows; numeric status IDs cannot be swapped between instances."),
    ("lemmy", "Lemmy", "protocols", "Lemmy has versioned APIs, community and user feeds, federation and several web clients. API v3 and v4 differ. A new user interface is often another client of the same instance rather than a new copy of its data."),
    ("pixelfed", "Pixelfed", "other", "Pixelfed exposes public profile, media and feed representations with server-specific policy. A candidate API can return real public media while a similarly named endpoint serves only an app shell. Exact account and post identity still control acceptance."),
    ("misskey", "Misskey", "protocols", "Misskey provides native public JSON, direct replies, RSS, Atom, JSON Feed and ActivityPub representations. Visitor restrictions and account settings can disable them. Forks must be tested separately; compatible names do not make their IDs interchangeable."),
    ("gettr", "GETTR", "other", "GETTR's public API and feed generators use distinct references and compact data structures. Only posts selected by the requested account or exact post ID belong in the native result. Auxiliary cached objects are not additional feed entries."),
    ("gab", "Gab", "other", "Gab has Mastodon-shaped and compact public data paths, plus reader and bridge ideas. An accessible profile does not prove its timeline or discussion endpoint works. Authentication-dependent and rejected approaches remain research entries."),
    ("telegram", "Telegram", "other", "Telegram offers public channel pages and embeds plus independent feed generators. Bot API and MTProto access have separate credential requirements. A public channel reader does not gain access to private chats or a complete comment discussion."),
    ("linkedin", "LinkedIn", "large", "LinkedIn's public organization and activity pages, embed formats and feed projects offer limited public reading paths. Official APIs are permissioned. Public profile branding or a search preview cannot stand in for a complete selected post."),
    ("snapchat", "Snapchat", "large", "Snapchat has public profile and Spotlight embed formats alongside developer interfaces. Verified story identity and actual media distinguish a useful native card from a promotional shell; public access does not imply private account content is available."),
]
UPSTREAMS = {
    "RSS-Bridge": {"revision": "9001d87b797f6605b111f622f2e0d11a63dde744", "repository": "https://github.com/RSS-Bridge/rss-bridge"},
    "RSSHub": {"revision": "5d47d3a1e801075c47c45e492d98ec93b0d9eb99", "repository": "https://github.com/DIYgod/RSSHub"},
}


def clean_text(value: object) -> str:
    """Keep source descriptions readable without turning them into HTML."""
    if value is None:
        return ""
    return re.sub(r"\s+", " ", str(value)).strip()


def strings(value: object) -> list[str]:
    if value is None:
        return []
    if not isinstance(value, list):
        value = [value]
    return list(dict.fromkeys(text for item in value if (text := clean_text(item))))


def record(row: dict, platform: str) -> dict:
    references = strings(row.get("references", row.get("sourceReferences", row.get("primarySources"))))
    if not references:
        raise ValueError(f"{platform}:{row.get('id')} has no source references")
    requests = strings(row.get("requestTemplates", row.get("requestTemplate")))
    original_status = clean_text(row.get("status", "candidate"))
    activation = row.get("activation")
    if activation is None:
        if row.get("activateCandidate") is True:
            activation = "implemented"
        elif original_status in {"gated", "obsolete", "rejected"}:
            activation = original_status
        elif original_status == "existing":
            activation = "existing"
        elif row.get("runtimeEligible") is True:
            activation = "implemented or existing; exact runtime registration is separate"
        else:
            activation = "research candidate"
    proof_level = row.get("proofLevel")
    if not proof_level:
        proof_level = "sample content verified as described" if original_status == "proven" else "source contract or candidate observation; no universal health claim"
    result = {
        "id": clean_text(row["id"]),
        "platform": platform,
        "frontend": clean_text(row.get("frontend")),
        "root": clean_text(row.get("root")),
        "kind": clean_text(row.get("kind")),
        "role": clean_text(row.get("role", "UNSPECIFIED")),
        "scope": clean_text(row.get("scope", "Only the target and capability described by this pathway; placeholders need selected public input.")),
        "capabilities": strings(row.get("capabilities")),
        "requestTemplates": requests,
        "status": original_status,
        "proofLevel": clean_text(proof_level),
        "proof": clean_text(row.get("proof")),
        "authentication": clean_text(row.get("authentication", row.get("auth"))) or "Not established by this record; do not assume credential-free access.",
        "fees": clean_text(row.get("fees", row.get("fee", row.get("cost")))) or "Not established by this record; no paid service is enabled automatically.",
        "limitations": clean_text(row.get("limitations", row.get("limits"))) or "Availability and completeness remain source dependent.",
        "activation": clean_text(activation),
        "references": references,
    }
    if "observedState" in row:
        result["observedState"] = clean_text(row["observedState"])
    if "runtimeEligible" in row:
        result["researchRuntimeEligible"] = bool(row["runtimeEligible"])
    return result


def build(evidence: pathlib.Path) -> dict:
    platforms, inputs = [], []
    ids = set()
    for platform, label, folder, summary in PLATFORMS:
        path = evidence / folder / f"{platform}.json"
        raw = path.read_bytes()
        document = json.loads(raw)
        if document.get("platform") != platform:
            raise ValueError(f"Unexpected platform in {path}")
        rows = document.get("paths", document.get("sources", document.get("candidates")))
        if not isinstance(rows, list) or not rows:
            raise ValueError(f"No source records in {path}")
        entries = [record(row, platform) for row in rows]
        for entry in entries:
            key = (platform, entry["id"])
            if key in ids:
                raise ValueError(f"Duplicate pathway ID {key}")
            ids.add(key)
        leads = strings(document.get("researchLeads", document.get("searchLeads", document.get("distinctPrimaryLeads"))))
        if len(leads) < 3:
            raise ValueError(f"Expected at least three independent research leads for {platform}")
        platforms.append({
            "id": platform,
            "label": label,
            "reviewedOn": clean_text(document.get("researchDate", document.get("observedOn", document.get("reviewedDate")))),
            "summary": summary,
            "researchLeads": leads,
            "pathwayCount": len(entries),
            "statusCounts": dict(sorted(collections.Counter(row["status"] for row in entries).items())),
            "pathways": entries,
        })
        inputs.append({"path": str(path.relative_to(evidence)), "sha256": hashlib.sha256(raw).hexdigest()})
    entries = [entry for platform in platforms for entry in platform["pathways"]]
    refs = sorted({ref for entry in entries for ref in entry["references"]})
    return {
        "schemaVersion": 1,
        "title": "AntiSocial public source atlas",
        "reviewedOn": "2026-10-17",
        "donor": "Darwin",
        "scope": "A deep, individually researched inventory of public source families for all 21 supported social platforms. This is a dated research snapshot, not a claim that the whole web has been exhaustively enumerated.",
        "readingGuide": [
            "A pathway is a documented representation or delivery route. Several hostnames and formats can share one upstream service.",
            "A proven entry records a concrete successful sample as described. It does not certify every target, transport, media type or future request.",
            "Candidate, source-confirmed and existing entries retain their specific proof level. HTTP 200, a project name and a live homepage are not content health.",
            "Capabilities describe the discovered source contract. Runtime adapters advertise only the operations they implement and accept for the selected target.",
            "The runtime pool is maintained separately in public-pathways.json. This atlas never schedules probes, scans hosts or activates a gated service.",
            "Keys, paid services, private content and authenticated endpoints stay outside default accountless access. Nothing here grants a new permission or supplies a credential.",
            "Historical and federation-cache records have limited scope. They cannot establish a current subscription refresh or move instance-local IDs between servers.",
        ],
        "counts": {
            "platforms": len(platforms),
            "pathways": len(entries),
            "uniqueReferences": len(refs),
            "status": dict(sorted(collections.Counter(row["status"] for row in entries).items())),
            "kinds": dict(sorted(collections.Counter(row["kind"] for row in entries).items())),
        },
        "upstreamSnapshots": UPSTREAMS,
        "evidenceInputs": inputs,
        "platforms": platforms,
        "references": refs,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-root", type=pathlib.Path, default=DEFAULT_EVIDENCE)
    parser.add_argument("--output", type=pathlib.Path, default=ROOT / "app/src/main/assets/source-discovery.json")
    args = parser.parse_args()
    inventory = build(args.evidence_root)
    encoded = json.dumps(inventory, ensure_ascii=False, indent=2) + "\n"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(encoded)
    print(json.dumps({"output": str(args.output), "bytes": len(encoded.encode()), **inventory["counts"]}, indent=2))


if __name__ == "__main__":
    main()
