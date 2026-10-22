#!/usr/bin/env python3
"""Reproduce AntiSocial's offline language worker. No model/network SDK is shipped.

All sources, tools, object files and evidence stay in ANTISOCIAL_BUILD_ROOT. Source
ZIPs contain these exact upstream archives alongside AntiSocial's build adaptations.
"""
import argparse, hashlib, json, os, pathlib, shutil, subprocess, tarfile, urllib.request

REPO=pathlib.Path(__file__).resolve().parents[1]
ROOT=pathlib.Path(os.environ.get('ANTISOCIAL_BUILD_ROOT','/run/media/red/RedtopStorage/DarwinBuild/LiberBuild'))
WORK=ROOT/'languages'
PINS=json.loads((REPO/'native/translation/sources.json').read_text())

def sha(path):
    with path.open('rb') as source:return hashlib.file_digest(source,'sha256').hexdigest()

def acquire(url,file,expected):
    file.parent.mkdir(parents=True,exist_ok=True)
    if file.is_file() and sha(file)==expected:return
    bundled=REPO/'third_party'/('translation-'+file.name)
    partial=file.with_suffix(file.suffix+'.partial')
    if bundled.is_file():shutil.copy2(bundled,partial)
    else:
        with urllib.request.urlopen(url,timeout=180) as source,partial.open('wb') as out:shutil.copyfileobj(source,out,1024*1024)
    if sha(partial)!=expected:partial.unlink();raise RuntimeError(f'Source integrity check failed: {file.name}')
    partial.replace(file)

def sources():
    for name,pin in PINS.items():
        file=WORK/f'{name}-source.tar.gz'
        acquire(f'https://codeload.github.com/{pin["repository"]}/tar.gz/{pin["revision"]}',file,pin['sha256'])
        destination=WORK/'source'/pin['directory']
        marker=destination/'.antisocial-source-pin'
        if marker.is_file() and marker.read_text()==pin['sha256']:continue
        stage=WORK/'extract'/name
        if stage.exists():shutil.rmtree(stage)
        stage.mkdir(parents=True)
        with tarfile.open(file) as archive:archive.extractall(stage,filter='data')
        top=next(x for x in stage.iterdir() if x.is_dir())
        destination.parent.mkdir(parents=True,exist_ok=True)
        if destination.exists():shutil.rmtree(destination)
        top.rename(destination);marker.write_text(pin['sha256'])
    # Marian's historical CMake unconditionally updates all training Git
    # submodules. A pinned source build must never fetch implicitly.
    marian=WORK/'source/mozilla/inference/marian-fork/CMakeLists.txt'
    text=marian.read_text()
    update='execute_process(COMMAND git submodule update --init --recursive --no-fetch\n                WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR})'
    if update in text:marian.write_text(text.replace(update,'# AntiSocial: all required sources are acquired by scripts/build_translation.py.'))
    # Source archives have no .git metadata. Record the actual immutable pin,
    # rather than reading the developer checkout or inventing a Git history.
    pin=PINS['mozilla']['revision']
    src=marian.parent/'src/CMakeLists.txt';text=src.read_text()
    start=text.find('set(MARIAN_GIT_DIR ');end=text.find('# make sure all local dependencies',start)
    if start>=0 and end>start:src.write_text(text[:start]+f'file(WRITE ${{CMAKE_CURRENT_SOURCE_DIR}}/common/git_revision.h "#define GIT_REVISION \\\"Mozilla {pin}\\\"\\n")\n'+text[end:])
    for file in [marian.parent/'cmake/GetVersionFromFile.cmake',marian.parent.parent/'cmake/GetVersionFromFile.cmake']:
        text=file.read_text();start=text.find('execute_process(COMMAND ${GIT_EXECUTABLE}');end=text.find('OUTPUT_STRIP_TRAILING_WHITESPACE)',start)
        if start>=0 and end>start:file.write_text(text[:start]+f'set(PROJECT_VERSION_GIT_SHA "{pin[:12]}")'+text[end+len('OUTPUT_STRIP_TRAILING_WHITESPACE)'):])
    # Android 26/27 has no iconv/glob symbols. AntiSocial passes only explicit,
    # private UTF-8 filenames; unsupported legacy conversion/globs fail clearly.
    pathie=marian.parent/'src/3rd_party/pathie-cpp/src'
    file=pathie/'pathie.cpp';text=file.read_text()
    signature='std::string Pathie::convert_encodings(const char* from_encoding, const char* to_encoding, const std::string& string)\n{'
    guard='\n#if defined(__ANDROID__) && __ANDROID_API__ < 28\n  if (std::all_of(string.begin(),string.end(),[](unsigned char c){return c<128;}) || (std::string(from_encoding)=="UTF-8" && std::string(to_encoding)=="UTF-8")) return string;\n  throw std::runtime_error("Legacy filename encoding is unavailable on this Android version");\n#else\n'
    if signature+guard not in text:
        start=text.index(signature)+len(signature);end=text.index('\n}\n',start)
        text=text[:start]+guard+text[start:end]+'\n#endif'+text[end:]
        text='#include <algorithm>\n#include <stdexcept>\n'+text;file.write_text(text)
    file=pathie/'path.cpp';text=file.read_text()
    signature='std::vector<Path> Path::glob(const std::string& pattern, int flags /* = 0 */)\n{\n#if defined(_PATHIE_UNIX)'
    replacement='std::vector<Path> Path::glob(const std::string& pattern, int flags /* = 0 */)\n{\n#if defined(__ANDROID__) && __ANDROID_API__ < 28\n  if(flags || pattern.find_first_of("*?[")!=std::string::npos) throw std::runtime_error("Only explicit private filenames are supported");\n  Path path(pattern); return path.exists()?std::vector<Path>{path}:std::vector<Path>{};\n#elif defined(_PATHIE_UNIX)'
    if signature in text:file.write_text(text.replace(signature,replacement))

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host',action='store_true');parser.add_argument('--android',choices=['arm64-v8a','x86_64'])
    parser.add_argument('--jobs',type=int,default=2);args=parser.parse_args()
    if not args.host and not args.android:parser.error('Choose --host or --android')
    WORK.mkdir(parents=True,exist_ok=True);sources()
    tools=ROOT/'ai-foundation/tools-venv/bin';cmake=str(tools/'cmake');ninja=str(tools/'ninja')
    env=os.environ.copy();env.update(PATH=str(tools)+':'+env.get('PATH',''),TMPDIR=str(ROOT/'java-tmp'))
    abi=args.android or 'host';build=WORK/'build'/abi;prefix=build/'prefix';ndk=ROOT/'android-sdk/ndk/27.3.13750724'
    common=['-G','Ninja',f'-DCMAKE_MAKE_PROGRAM={ninja}','-DCMAKE_BUILD_TYPE=Release','-DCMAKE_POSITION_INDEPENDENT_CODE=ON','-DBUILD_SHARED_LIBS=OFF',f'-DCMAKE_INSTALL_PREFIX={prefix}']
    if args.android:common += [f'-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake',f'-DANDROID_ABI={abi}','-DANDROID_PLATFORM=android-26','-DANDROID_STL=c++_static','-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384']
    jobs=str(max(1,min(args.jobs,4)))
    pcre=build/'pcre2'
    subprocess.run([cmake,'-S',str(WORK/'source/pcre2'),'-B',str(pcre),*common,'-DPCRE2_BUILD_PCRE2_16=OFF','-DPCRE2_BUILD_PCRE2_32=OFF','-DPCRE2_BUILD_PCRE2GREP=OFF','-DPCRE2_BUILD_TESTS=OFF','-DPCRE2_SUPPORT_JIT=OFF'],env=env,check=True)
    subprocess.run([cmake,'--build',str(pcre),'--parallel',jobs,'--target','install'],env=env,check=True)
    native=build/'native'
    command=[cmake,'-S',str(REPO/'native/translation'),'-B',str(native),*common,f'-DANTISOCIAL_TRANSLATIONS_SOURCE={WORK}/source/mozilla',f'-DANTISOCIAL_FASTTEXT_SOURCE={WORK}/source/fasttext',f'-DPCRE2_INCLUDE_DIRS={prefix}/include',f'-DPCRE2_LIBRARIES={prefix}/lib/libpcre2-8.a']
    subprocess.run(command,env=env,check=True)
    target='antisocial_translation' if args.android else 'antisocial-translation-reference'
    subprocess.run([cmake,'--build',str(native),'--parallel',jobs,'--target',target],env=env,check=True)
    artifact=native/('libantisocial_translation.so' if args.android else target);destination=artifact
    if args.android:
        subprocess.run(['python3',str(REPO/'scripts/prepare_jni.py')],env=env,check=True)
        destination=ROOT/'native-android'/abi/artifact.name;destination.parent.mkdir(parents=True,exist_ok=True)
        subprocess.run([str(ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip'),'--strip-unneeded','-o',str(destination),str(artifact)],check=True)
    notices=REPO/'app/src/main/assets/translation-notices';notices.mkdir(parents=True,exist_ok=True)
    for name,pin in PINS.items():
        source=WORK/'source'/pin['directory']
        for file in source.rglob('*'):
            if file.is_file() and (file.name.upper().startswith(('LICENSE','COPYING','NOTICE'))):
                output=notices/name/file.relative_to(source);output.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(file,output)
    asset=REPO/'app/src/main/assets/translation/lid.176.ftz'
    acquire('https://dl.fbaipublicfiles.com/fasttext/supervised-models/lid.176.ftz',WORK/'lid.176.ftz','8f3472cfe8738a7b6099e8e999c3cbfae0dcd15696aac7d7738a8039db603e83')
    asset.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(WORK/'lid.176.ftz',asset)
    prefixes=WORK/'source/mozilla/inference/3rd_party/ssplit-cpp/nonbreaking_prefixes'
    shutil.copytree(prefixes,asset.parent/'prefixes',dirs_exist_ok=True)
    manifest={'sources':PINS,'abi':abi,'backend':'CPU; one resident translation direction','artifact':str(destination),'sha256':sha(destination),'bytes':destination.stat().st_size,'configure':command,'networking':False,'source_adaptations':['disable implicit training submodule fetch','use immutable source pin in archive builds','Android 26/27 explicit private filenames; reject legacy filename conversion and globs','vendor warnings remain warnings; portable CPU baseline; single replica; bundled JNI']} 
    (build/'build-manifest.json').write_text(json.dumps(manifest,indent=2));print(json.dumps(manifest,indent=2))

if __name__=='__main__':main()
