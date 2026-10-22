#!/usr/bin/env python3
"""Reproduce AntiSocial's offline native Bonsai runtime; all build state stays external.

Host: python3 scripts/build_local_ai.py --host
Android reference: python3 scripts/build_local_ai.py --android x86_64
Android GPU: python3 scripts/build_local_ai.py --android arm64-v8a --vulkan
No model is downloaded unless --reference-model is explicitly supplied.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
import venv

ROOT = Path(__file__).resolve().parents[1]
BUILD_ROOT = Path(os.environ.get("ANTISOCIAL_BUILD_ROOT", "/run/media/red/RedtopStorage/DarwinBuild/LiberBuild"))
WORK = BUILD_ROOT / "ai-foundation"
REVISION = "bc47470138d892a45fedb037c2615cc12c54cded"
ARCHIVE_HASH = "c538dc1a88395e5a3bd623ec4acdc68485e78b12bc15fd3b2f955809093764ec"
SOURCE = WORK / f"qvac-fabric-llm.cpp-{REVISION}-antisocial-kv-v3"
NDK = BUILD_ROOT / "android-sdk/ndk/27.3.13750724"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def acquire(url, target, expected):
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and digest(target) == expected:
        return
    temporary = target.with_suffix(target.suffix + ".partial")
    with urllib.request.urlopen(url, timeout=180) as response, temporary.open("wb") as stream:
        shutil.copyfileobj(response, stream, 1024 * 1024)
    actual = digest(temporary)
    if actual != expected:
        temporary.unlink(missing_ok=True)
        raise RuntimeError(f"Source integrity mismatch for {target.name}: {actual}")
    temporary.replace(target)


def tools():
    env = os.environ.copy()
    tool_root = WORK / "tools-venv"
    cmake = tool_root / "bin/cmake"
    ninja = tool_root / "bin/ninja"
    if not cmake.exists() or not ninja.exists():
        if not (tool_root / "bin/python").exists():
            venv.create(tool_root, with_pip=True)
        subprocess.run([str(tool_root / "bin/python"), "-m", "pip", "install", "--cache-dir", str(WORK / "pip-cache"), "cmake==3.31.6", "ninja==1.11.1.4"], check=True)
    env["PATH"] = f"{tool_root / 'bin'}:{env.get('PATH', '')}"
    env["TMPDIR"] = str(BUILD_ROOT / "java-tmp")
    return str(cmake), str(ninja), env


def shader_compiler(env):
    supplied = os.environ.get("ANTISOCIAL_GLSLC")
    if supplied:
        subprocess.run([supplied, "--version"], env=env, check=True)
        return supplied
    # NDK r27's shaderc is from 2022 and omits the packed integer-dot and
    # cooperative-matrix paths. This host tool is never shipped in the app.
    # On other Linux distributions supply a matching Vulkan SDK glslc instead.
    package = WORK / "shaderc-arch.pkg.tar.zst"
    acquire("https://archive.archlinux.org/packages/s/shaderc/shaderc-2026.4-1-x86_64.pkg.tar.zst", package, "4d3f5e14b23f596959135a657a024278a3cdd403eeaa60d2e7bc61093a2ffd6b")
    extracted = WORK / "shaderc-arch"
    compiler = extracted / "usr/bin/glslc"
    if not compiler.exists():
        extracted.mkdir(parents=True, exist_ok=True)
        subprocess.run(["tar", "--zstd", "-xf", str(package), "-C", str(extracted)], check=True)
    try:
        subprocess.run([str(compiler), "--version"], env=env, check=True)
    except subprocess.CalledProcessError as error:
        raise RuntimeError("Provide ANTISOCIAL_GLSLC from a current Vulkan SDK matching this build host") from error
    return str(compiler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", action="store_true")
    parser.add_argument("--android", choices=["arm64-v8a", "x86_64"])
    parser.add_argument("--vulkan", action="store_true")
    parser.add_argument("--reference-model", action="store_true")
    parser.add_argument("--no-package", action="store_true", help="Build an Android candidate without replacing staged application libraries")
    parser.add_argument("--kv-probes", action="store_true", help="Build host cache qualification tools (no model acquisition)")
    parser.add_argument("--jobs", type=int, default=4)
    args = parser.parse_args()
    if args.kv_probes and not args.host:
        parser.error("--kv-probes requires --host")
    if args.no_package and not args.android:
        parser.error("--no-package requires --android")
    if not args.host and not args.android and not args.reference_model:
        parser.error("choose --host, --android, or --reference-model")
    WORK.mkdir(parents=True, exist_ok=True)
    archive = WORK / "qvac-bc474701.tar.gz"
    bundled = ROOT / "third_party/qvac-bc474701.tar.gz"
    if not archive.exists() and bundled.exists():
        if digest(bundled) != ARCHIVE_HASH:
            raise RuntimeError("The bundled QVAC source archive failed its pinned integrity check")
        shutil.copy2(bundled, archive)
    acquire(f"https://codeload.github.com/tetherto/qvac-fabric-llm.cpp/tar.gz/{REVISION}", archive, ARCHIVE_HASH)
    patch = ROOT / "native/local_ai/qvac-bonsai-v2.patch"
    shader_patch = ROOT / "native/local_ai/qvac-bounded-shader-build.patch"
    android_vulkan_patch = ROOT / "native/local_ai/qvac-android-vulkan-dispatch.patch"
    ssse3_patch = ROOT / "native/local_ai/qvac-pq2-ssse3.patch"
    kv_patch = ROOT / "native/local_ai/qvac-online-kv-v2.patch"
    state_patch = ROOT / "native/local_ai/qvac-state-recipe-v3.patch"
    # The packet's QVAC pin rejects the packet's real GGUF (Hadamard v2).
    # Preserve QVAC and port only Prism's tied-embedding/output contract.
    patches = (patch, shader_patch, android_vulkan_patch, ssse3_patch, kv_patch, state_patch)
    expected_recipe = {"archive_sha256": ARCHIVE_HASH, "patches": {p.name: digest(p) for p in patches}}
    recipe_path = SOURCE / "antisocial-source-recipe.json"
    if SOURCE.exists():
        # Later patches can change earlier postimages, so reverse-applying each
        # patch is not a valid check of an already composed source tree.
        recipe = json.loads(recipe_path.read_text())
        for key, expected in expected_recipe.items():
            if recipe.get(key) != expected:
                raise RuntimeError("Native source recipe changed; preserve this tree and use a fresh source directory")
        for name, expected in recipe["postimages"].items():
            if digest(SOURCE / name) != expected:
                raise RuntimeError(f"Native source postimage changed: {name}")
    else:
        with tempfile.TemporaryDirectory(prefix="qvac-kv-source-",dir=WORK) as temporary:
            with tarfile.open(archive) as source:
                source.extractall(temporary, filter="data")
            prepared = Path(temporary, f"qvac-fabric-llm.cpp-{REVISION}")
            names = set()
            for source_patch in patches:
                subprocess.run(["git", "apply", "--check", str(source_patch)], cwd=prepared, check=True)
                subprocess.run(["git", "apply", str(source_patch)], cwd=prepared, check=True)
                names.update(line[6:] for line in source_patch.read_text().splitlines() if line.startswith("+++ b/"))
            for name in names:
                if not (prepared / name).resolve().is_relative_to(prepared.resolve()):
                    raise RuntimeError("Native patch postimage escapes the source tree")
            recipe = {**expected_recipe, "postimages": {n: digest(prepared / n) for n in sorted(names)}}
            (prepared / recipe_path.name).write_text(json.dumps(recipe, indent=2) + "\n")
            prepared.rename(SOURCE)
    if args.reference_model:
        acquire("https://huggingface.co/prism-ml/Ternary-Bonsai-2-4B-gguf/resolve/8d6557168168f91ea6697a4a49ed2ef622b53adb/Ternary-Bonsai-2-4B-PQ2_0.gguf", WORK / "models/Ternary-Bonsai-2-4B-PQ2_0.gguf", "d406b1e912c0d2a4c124ad10bf38a76ae662fdc4d24b176451403dc7b7f30409")
    if not args.host and not args.android:
        return
    cmake, ninja, env = tools()
    name = args.android or "host"
    name += "-vulkan" if args.vulkan else "-cpu"
    name += "-antisocial-kv-v3"
    output = WORK / "native-build" / name
    command = [cmake, "-S", str(ROOT / "native/local_ai"), "-B", str(output), "-G", "Ninja", f"-DCMAKE_MAKE_PROGRAM={ninja}", "-DCMAKE_BUILD_TYPE=Release", f"-DANTISOCIAL_QVAC_SOURCE={SOURCE}", f"-DGGML_VULKAN={'ON' if args.vulkan else 'OFF'}", "-DGGML_CCACHE=OFF"]
    if args.host and os.uname().machine == "x86_64":
        command += ["-DGGML_SSE42=ON", "-DGGML_AVX=OFF", "-DGGML_AVX2=OFF", "-DGGML_FMA=OFF", "-DGGML_F16C=OFF"]
    if args.android:
        command += [f"-DCMAKE_TOOLCHAIN_FILE={NDK / 'build/cmake/android.toolchain.cmake'}", f"-DANDROID_ABI={args.android}", "-DANDROID_PLATFORM=android-26", "-DANDROID_STL=c++_static"]
        if args.android == "arm64-v8a":
            # Never infer the developer workstation's ISA for an ordinary phone.
            command += ["-DGGML_CPU_ARM_ARCH=armv8-a"]
        elif args.android == "x86_64":
            command += ["-DGGML_AVX=OFF", "-DGGML_AVX2=OFF", "-DGGML_FMA=OFF", "-DGGML_F16C=OFF"]
    if args.vulkan:
        command += [f"-DVulkan_GLSLC_EXECUTABLE={shader_compiler(env)}", "-DGGML_VULKAN_BUILD_ADRENO_SHADERS=ON"]
    subprocess.run(command, env=env, check=True)
    target = "antisocial_local_ai" if args.android else "antisocial-text-reference"
    targets = [target] if args.android else [target, "antisocial-text-kernel-test"]
    if args.kv_probes:
        targets += [f"antisocial-kv-{probe}-reference" for probe in ("attention","basis","logits","calibrate","state","position")]
        targets += ["antisocial-kv-quality-reference","antisocial-kv-speculative-reference"]
    subprocess.run([cmake, "--build", str(output), "--parallel", str(max(1, min(args.jobs, 4))), "--target", *targets], env=env, check=True)
    if args.host:
        subprocess.run([str(output / "antisocial-text-kernel-test")], env=env, check=True)
    artifact = output / ("libantisocial_local_ai.so" if args.android else "antisocial-text-reference")
    if args.android and not args.no_package:
        subprocess.run(["python3", str(ROOT / "scripts/prepare_jni.py")], env=env, check=True)
        native_root = BUILD_ROOT / "native-android"
        destination = native_root / args.android / artifact.name
        destination.parent.mkdir(parents=True, exist_ok=True)
        strip = NDK / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
        subprocess.run([str(strip), "--strip-unneeded", "-o", str(destination), str(artifact)], check=True)
    # Host probes are research artifacts. Keep their notices with their build,
    # instead of rewriting the Android asset tree during a host-only build.
    notice_root = output / "runtime-notices" if args.no_package or args.host else ROOT / "app/src/main/assets/ai-runtime-notices"
    notice_root.mkdir(parents=True, exist_ok=True)
    shutil.copy2(SOURCE / "LICENSE", notice_root / "QVAC-MIT.txt")
    for base in (SOURCE / "vendor", SOURCE / "ggml"):
        for license_file in base.rglob("*"):
            if license_file.is_file() and license_file.name.upper().startswith(("LICENSE", "COPYING", "NOTICE")):
                relative = license_file.relative_to(SOURCE)
                destination_notice = notice_root / "runtime" / relative
                destination_notice.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(license_file, destination_notice)
    if args.vulkan:
        for dependency in ("vulkan_headers", "spirv_headers"):
            headers = output / "_deps" / f"{dependency}-src"
            for license_file in headers.rglob("*"):
                if license_file.is_file() and (license_file.name.upper().startswith(("LICENSE", "NOTICE")) or "LICENSES" in license_file.parts):
                    relative = license_file.relative_to(headers)
                    destination_notice = notice_root / dependency / relative
                    destination_notice.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(license_file, destination_notice)
    manifest = {
        "runtime": "QVAC Fabric LLM",
        "source": "https://github.com/tetherto/qvac-fabric-llm.cpp",
        "revision": REVISION,
        "source_archive_sha256": ARCHIVE_HASH,
        "donor": "https://github.com/PrismML-Eng/llama.cpp/tree/11809184898113482c2f510fd13da669aaba1052",
        "donor_patch_sha256": digest(patch),
        "bounded_shader_build_patch_sha256": digest(shader_patch),
        "android_vulkan_dispatch_patch_sha256": digest(android_vulkan_patch),
        "pq2_ssse3_patch_sha256": digest(ssse3_patch),
        "online_kv_patch_sha256": digest(kv_patch),
        "state_recipe_patch_sha256": digest(state_patch),
        "abi": args.android or "host",
        "vulkan_built": args.vulkan,
        "graph": "Bonsai 2 / Qwen3.5 with Prism Hadamard v1/v2",
        "cache_profile": "f16_reference",
        "cache_candidates": ["pq4_protected_v2","pq3_protected_v2","pq4_pq3_protected_v2","signed4_protected_v2","planar4_protected_v2","iso4_protected_v2"],
        "cache_promotion": "F16 remains default until profile-specific qualification; candidate availability is not admission",
        "phone_gpu_qualification": "required separately",
        "artifact": str(artifact),
        "artifact_sha256": digest(artifact),
        "packaged_sha256": digest(destination) if args.android and not args.no_package else None,
        "configure": command,
    }
    if args.vulkan:
        manifest["host_shader_compiler"] = command[-2].removeprefix("-DVulkan_GLSLC_EXECUTABLE=")
        manifest["header_revisions"] = {
            "Vulkan-Headers": "8864cdc896bbc2a9b6eb36b3218fc9ef57908d77",
            "SPIRV-Headers": "ad9184e76a66b1001c29db9b0a3e87f646c64de0",
        }
    (output / "build-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(artifact)


if __name__ == "__main__":
    main()
