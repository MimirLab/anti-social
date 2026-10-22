#!/usr/bin/env python3
"""Install isolated official Android/JDK/Gradle tooling on RedtopStorage."""
import concurrent.futures, hashlib, json, os, pathlib, subprocess, tarfile, urllib.request, xml.etree.ElementTree as ET, zipfile

ROOT = pathlib.Path(os.environ.get('ANTISOCIAL_BUILD_ROOT', '/run/media/red/RedtopStorage/DarwinBuild/LiberBuild'))
ROOT.mkdir(parents=True, exist_ok=True)
def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent':'AntiSocial development bootstrap'}), timeout=90) as r: return r.read()
def download(url, name, digest=None, algorithm='sha256'):
    path=ROOT/'downloads'/name; path.parent.mkdir(exist_ok=True)
    if not path.exists():
        tmp=path.with_suffix(path.suffix+'.part')
        subprocess.run(['curl','--fail','--location','--retry','3','--silent','--show-error','--output',str(tmp),url],check=True)
        tmp.rename(path)
    if digest and hashlib.new(algorithm,path.read_bytes()).hexdigest()!=digest: raise RuntimeError('Checksum mismatch: '+name)
    return path
def java():
    if (ROOT/'jdk/bin/java').exists():return
    item=json.loads(get('https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=linux'))[0]['binary']['package']
    p=download(item['link'],item['name'],item['checksum'])
    temp=ROOT/'jdk-unpack'; temp.mkdir(exist_ok=True)
    with tarfile.open(p) as t:t.extractall(temp,filter='data')
    next(temp.iterdir()).rename(ROOT/'jdk')
def android():
    if (ROOT/'android-sdk/cmdline-tools/latest/bin/sdkmanager').exists():return
    tree=ET.fromstring(get('https://dl.google.com/android/repository/repository2-3.xml'))
    package=next(p for p in tree.findall('remotePackage') if p.attrib.get('path')=='cmdline-tools;latest')
    archive=next(a for a in package.findall('archives/archive') if a.findtext('host-os')=='linux').find('complete')
    p=download('https://dl.google.com/android/repository/'+archive.findtext('url'),'android-commandline.zip',archive.findtext('checksum'),'sha1')
    target=ROOT/'android-sdk/cmdline-tools'; target.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(p) as z:z.extractall(target)
    (target/'cmdline-tools').rename(target/'latest')
    for binary in (target/'latest/bin').iterdir():binary.chmod(0o755)
def gradle():
    if (ROOT/'gradle/lib/gradle-launcher-9.3.1.jar').exists():return
    url='https://services.gradle.org/distributions/gradle-9.3.1-bin.zip'
    p=download(url,'gradle-9.3.1-bin.zip',get(url+'.sha256').decode().strip())
    with zipfile.ZipFile(p) as z:z.extractall(ROOT)
    if (ROOT/'gradle').exists(): (ROOT/'gradle').rename(ROOT/'gradle-previous-8.13')
    (ROOT/'gradle-9.3.1').rename(ROOT/'gradle');(ROOT/'gradle/bin/gradle').chmod(0o755)
def rust():
    if (ROOT/'cargo/bin/cargo').exists():return
    url='https://static.rust-lang.org/rustup/dist/x86_64-unknown-linux-gnu/rustup-init'
    p=download(url,'rustup-init',get(url+'.sha256').decode().split()[0]);p.chmod(0o755)
    env=os.environ.copy();env.update(CARGO_HOME=str(ROOT/'cargo'),RUSTUP_HOME=str(ROOT/'rustup'))
    subprocess.run([str(p),'-y','--no-modify-path','--profile','minimal','--default-toolchain','1.99.0'],env=env,check=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as ex:
    for f in [ex.submit(java),ex.submit(android),ex.submit(gradle),ex.submit(rust)]:f.result()
env=os.environ.copy();env.update(JAVA_HOME=str(ROOT/'jdk'),ANDROID_HOME=str(ROOT/'android-sdk'),GRADLE_USER_HOME=str(ROOT/'gradle-cache'),ANDROID_USER_HOME=str(ROOT/'android-user'))
sdk=str(ROOT/'android-sdk/cmdline-tools/latest/bin/sdkmanager')
subprocess.run([sdk,'--sdk_root='+str(ROOT/'android-sdk'),'--licenses'],input='y\n'*100,text=True,env=env,stdout=subprocess.DEVNULL,check=True)
subprocess.run([sdk,'--sdk_root='+str(ROOT/'android-sdk'),'platform-tools','platforms;android-37.0','build-tools;36.0.0','emulator','system-images;android-35;google_apis;x86_64','ndk;27.3.13750724'],env=env,check=True)
print('AntiSocial Android tools ready:',ROOT)
