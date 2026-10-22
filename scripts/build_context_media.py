#!/usr/bin/env python3
"""Build offline OCR and speech runtimes. All tools and outputs remain external."""
import argparse, hashlib, json, os, pathlib, shutil, subprocess, tarfile, urllib.request

REPO=pathlib.Path(__file__).resolve().parents[1]
ROOT=pathlib.Path(os.environ.get('ANTISOCIAL_BUILD_ROOT','/run/media/red/RedtopStorage/DarwinBuild/LiberBuild'))
WORK=ROOT/'context-media'
SOURCES={
 'whisper':('ggml-org/whisper.cpp','0c7be696371069e0baa2515ed7aa08d5a5473aa6','20f6c4c2dd25225326d284b145998ffe48efabfee4e6c94c0437cb4f78dbab76'),
 'tesseract':('tesseract-ocr/tesseract','5.5.3','9218e62793116d42a9f6d14cd9348518b27f382096eea3d0f2d1a24616bb5884'),
 'leptonica':('DanBloomberg/leptonica','1.87.0','fa2b40c5caea96d1bb93a97486262aed8731b69ce25a84a6bf5d25323e33f631')}

def sha(path):
 with path.open('rb') as source:return hashlib.file_digest(source,'sha256').hexdigest()

def source(name):
 repo,revision,expected=SOURCES[name];archive=WORK/f'{name}-source.tar.gz'
 if not archive.exists():
  bundled=REPO/'third_party'/f'context-{name}-source.tar.gz'
  if bundled.exists():shutil.copy2(bundled,archive)
  else:
   with urllib.request.urlopen(f'https://codeload.github.com/{repo}/tar.gz/{revision}',timeout=90) as remote,archive.open('wb') as output:shutil.copyfileobj(remote,output)
 if sha(archive)!=expected:raise RuntimeError(f'{name} source integrity check failed')
 directory=WORK/(name+"-antisocial")
 if not directory.exists() or not any(directory.iterdir()):
  directory.mkdir(exist_ok=True)
  with tarfile.open(archive) as files:files.extractall(directory,filter='data')
 return next(path for path in directory.iterdir() if path.is_dir())

def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--android',choices=['arm64-v8a','x86_64']);parser.add_argument('--host',action='store_true');parser.add_argument('--jobs',type=int,default=2);args=parser.parse_args()
 if not args.android and not args.host:parser.error('Select --host or --android')
 WORK.mkdir(parents=True,exist_ok=True)
 sources={name:source(name) for name in SOURCES}
 patches={'whisper':REPO/'native/context_media/whisper-vad-cancellation.patch','tesseract':REPO/'native/context_media/tesseract-64bit-android.patch'}
 for name,patch in patches.items():
  check=subprocess.run(['git','apply','--reverse','--check',str(patch)],cwd=sources[name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  if check.returncode:subprocess.run(['git','apply',str(patch)],cwd=sources[name],check=True)
 tools=ROOT/'ai-foundation/tools-venv/bin';cmake=str(tools/'cmake');ninja=str(tools/'ninja')
 if not pathlib.Path(cmake).is_file():raise RuntimeError('Run scripts/build_local_ai.py --host once to prepare pinned CMake/Ninja tools')
 env=os.environ.copy();env['PATH']=str(tools)+':'+env.get('PATH','');env['TMPDIR']=str(ROOT/'java-tmp')
 abi=args.android or 'host';build=WORK/'build'/(abi+'-antisocial');prefix=build/'prefix';ndk=ROOT/'android-sdk/ndk/27.3.13750724'
 common=['-G','Ninja',f'-DCMAKE_MAKE_PROGRAM={ninja}','-DCMAKE_BUILD_TYPE=Release','-DCMAKE_POSITION_INDEPENDENT_CODE=ON','-DBUILD_SHARED_LIBS=OFF',f'-DCMAKE_INSTALL_PREFIX={prefix}']
 if args.android:common += [f'-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake',f'-DANDROID_ABI={abi}','-DANDROID_PLATFORM=android-26','-DANDROID_STL=c++_static','-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384']
 jobs=str(max(1,min(args.jobs,4)))
 leptonica=build/'leptonica'
 command=[cmake,'-S',str(sources['leptonica']),'-B',str(leptonica),*common,'-DBUILD_PROG=OFF','-DSW_BUILD=OFF',*['-DENABLE_'+name+'=OFF' for name in ['ZLIB','PNG','GIF','JPEG','TIFF','WEBP','OPENJPEG']]]
 subprocess.run(command,env=env,check=True);subprocess.run([cmake,'--build',str(leptonica),'--parallel',jobs,'--target','install'],env=env,check=True)
 native=build/'native';command=[cmake,'-S',str(REPO/'native/context_media'),'-B',str(native),*common,f'-DCMAKE_PREFIX_PATH={prefix}',f'-DLeptonica_DIR={prefix}/lib/cmake/leptonica',f'-DANTISOCIAL_WHISPER_SOURCE={sources["whisper"]}',f'-DANTISOCIAL_TESSERACT_SOURCE={sources["tesseract"]}','-DGGML_CCACHE=OFF','-DLEPT_TIFF_RESULT=1']
 if args.android=='arm64-v8a':command += ['-DGGML_CPU_ARM_ARCH=armv8-a']
 elif args.android=='x86_64' or os.uname().machine=='x86_64':command += ['-DGGML_SSE42=ON','-DGGML_AVX=OFF','-DGGML_AVX2=OFF','-DGGML_FMA=OFF','-DGGML_F16C=OFF','-DGGML_BMI2=OFF']
 subprocess.run(command,env=env,check=True)
 target='antisocial_context_media' if args.android else 'context-media-reference'
 subprocess.run([cmake,'--build',str(native),'--parallel',jobs,'--target',target],env=env,check=True)
 artifact=native/('libantisocial_context_media.so' if args.android else target)
 destination=artifact
 if args.android:
  subprocess.run(['python3',str(REPO/'scripts/prepare_jni.py')],env=env,check=True)
  destination=ROOT/'native-android'/abi/artifact.name;destination.parent.mkdir(parents=True,exist_ok=True)
  subprocess.run([str(ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip'),'--strip-unneeded','-o',str(destination),str(artifact)],check=True)
 notices=REPO/'app/src/main/assets/context-media-notices';notices.mkdir(parents=True,exist_ok=True)
 for name,directory in sources.items():
  for path in directory.rglob('*'):
   if path.is_file() and ('LICENSE' in path.name.upper() or path.name.upper().startswith(('COPYING','NOTICE'))):
    output=notices/name/path.relative_to(directory);output.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,output)
 manifest={'sources':SOURCES,'patches_sha256':{name:sha(patch) for name,patch in patches.items()},'abi':abi,'backend':'CPU','artifact':str(destination),'bytes':destination.stat().st_size,'sha256':sha(destination),'leptonica_codecs':'disabled; Android supplies decoded pixels','networking':False,'configure':command}
 (build/'build-manifest.json').write_text(json.dumps(manifest,indent=2));print(json.dumps(manifest,indent=2))

if __name__=='__main__':main()
