#!/usr/bin/env python3
"""Install isolated official Android/JDK/Gradle tooling on RedtopStorage."""
import argparse, concurrent.futures, hashlib, json, os, pathlib, subprocess, tarfile, urllib.request, xml.etree.ElementTree as ET, zipfile

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--no-emulator',action='store_true',help='Prepare build tools without the optional laptop preview')
args=parser.parse_args()
ROOT = pathlib.Path(os.environ.get('ANTISOCIAL_BUILD_ROOT', '/run/media/red/RedtopStorage/DarwinBuild/LiberBuild'))
ROOT.mkdir(parents=True, exist_ok=True)
LOCK=json.loads((pathlib.Path(__file__).with_name("toolchain-lock.json")).read_text())
def locked(name):
    item=LOCK[name]
    return download(item["url"],item["filename"],item["sha256"])
def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent':'AntiSocial development bootstrap'}), timeout=90) as r: return r.read()
def download(url, name, digest=None, algorithm='sha256'):
    path=ROOT/'downloads'/name; path.parent.mkdir(exist_ok=True)
    def verified(file):
        if not file.is_file():return False
        with file.open('rb') as stream:return not digest or hashlib.file_digest(stream,algorithm).hexdigest()==digest
    if verified(path):return path
    path.unlink(missing_ok=True)
    tmp=path.with_suffix(path.suffix+'.part')
    try:
        subprocess.run(['curl','--fail','--location','--retry','3','--silent','--show-error','--output',str(tmp),url],check=True)
        if not verified(tmp):raise RuntimeError('Checksum mismatch: '+name)
        tmp.rename(path)
    finally:tmp.unlink(missing_ok=True)
    return path
def java():
    if (ROOT/'jdk/bin/java').exists():return
    p=locked('jdk')
    temp=ROOT/'jdk-unpack'; temp.mkdir(exist_ok=True)
    with tarfile.open(p) as t:t.extractall(temp,filter='data')
    next(temp.iterdir()).rename(ROOT/'jdk')
def android():
    if (ROOT/'android-sdk/cmdline-tools/latest/bin/sdkmanager').exists():return
    p=locked('android-commandline')
    target=ROOT/'android-sdk/cmdline-tools'; target.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(p) as z:z.extractall(target)
    (target/'cmdline-tools').rename(target/'latest')
    for binary in (target/'latest/bin').iterdir():binary.chmod(0o755)
def gradle():
    if (ROOT/'gradle/lib/gradle-launcher-9.3.1.jar').exists():return
    url='https://services.gradle.org/distributions/gradle-9.3.1-bin.zip'
    p=locked('gradle')
    with zipfile.ZipFile(p) as z:z.extractall(ROOT)
    if (ROOT/'gradle').exists(): (ROOT/'gradle').rename(ROOT/'gradle-previous-8.13')
    (ROOT/'gradle-9.3.1').rename(ROOT/'gradle');(ROOT/'gradle/bin/gradle').chmod(0o755)
def rust():
    if (ROOT/'cargo/bin/cargo').exists():return
    url='https://static.rust-lang.org/rustup/dist/x86_64-unknown-linux-gnu/rustup-init'
    p=locked('rustup');p.chmod(0o755)
    env=os.environ.copy();env.update(CARGO_HOME=str(ROOT/'cargo'),RUSTUP_HOME=str(ROOT/'rustup'))
    subprocess.run([str(p),'-y','--no-modify-path','--profile','minimal','--default-toolchain','1.99.0'],env=env,check=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as ex:
    for f in [ex.submit(java),ex.submit(android),ex.submit(gradle),ex.submit(rust)]:f.result()
env=os.environ.copy();env.update(JAVA_HOME=str(ROOT/'jdk'),ANDROID_HOME=str(ROOT/'android-sdk'),GRADLE_USER_HOME=str(ROOT/'gradle-cache'),ANDROID_USER_HOME=str(ROOT/'android-user'))
sdk=str(ROOT/'android-sdk/cmdline-tools/latest/bin/sdkmanager')
subprocess.run([sdk,'--sdk_root='+str(ROOT/'android-sdk'),'--licenses'],input='y\n'*100,text=True,env=env,stdout=subprocess.DEVNULL,check=True)
subprocess.run([sdk,'--sdk_root='+str(ROOT/'android-sdk'),'platform-tools','platforms;android-37.0','build-tools;36.0.0','ndk;27.3.13750724',*([ ] if args.no_emulator else ['emulator','system-images;android-35;google_apis;x86_64','system-images;android-29;default;x86_64'])],env=env,check=True)
print('AntiSocial Android tools ready:',ROOT)
