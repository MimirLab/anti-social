#!/usr/bin/env python3
"""Sign the declarative AI catalog with the APK's actual release signer.

The current preview uses Android's existing local debug certificate. Production
builders supply ANTISOCIAL_SIGNING_STORE/ALIAS/PASSWORD (also used for the APK). No key
or password is written into the catalog/source archive. All generated files live
on RedtopStorage. Model catalogs never deliver executable code. The separate
APK-pinned native runtime installer has its own exact postimage contract.
"""
import os, pathlib, subprocess, argparse

repo=pathlib.Path(__file__).resolve().parents[1]
root=pathlib.Path(os.environ.get('ANTISOCIAL_BUILD_ROOT','/run/media/red/RedtopStorage/DarwinBuild/LiberBuild'))
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output',type=pathlib.Path,default=root/'outputs/app/generated/ai-assets')
args=parser.parse_args()
work=root/'ai-foundation/catalog-signer';work.mkdir(parents=True,exist_ok=True)
source=work/'SignCatalog.java'
source.write_text('''import java.nio.file.*;import java.security.*;import java.security.cert.Certificate;
public class SignCatalog { public static void main(String[] args) throws Exception {
  String password=System.getenv().getOrDefault("ANTISOCIAL_SIGNING_PASSWORD","android");
  String alias=System.getenv().getOrDefault("ANTISOCIAL_SIGNING_ALIAS","androiddebugkey");
  KeyStore store=KeyStore.getInstance("JKS");
  try(var in=Files.newInputStream(Path.of(args[0]))){store.load(in,password.toCharArray());}
  PrivateKey key=(PrivateKey)store.getKey(alias,System.getenv().getOrDefault("ANTISOCIAL_SIGNING_KEY_PASSWORD",password).toCharArray());
  Certificate cert=store.getCertificate(alias);if(!cert.getPublicKey().getAlgorithm().equals("RSA"))throw new Exception("AI catalog requires an RSA APK signer");
  byte[] bytes=Files.readAllBytes(Path.of(args[1]));
  Signature signature=Signature.getInstance("SHA256withRSA");signature.initSign(key);signature.update(bytes);
  Path out=Path.of(args[2]);Files.createDirectories(out);Files.write(out.resolve("catalog.json"),bytes);Files.write(out.resolve("catalog.sig"),signature.sign());
  Signature verify=Signature.getInstance("SHA256withRSA");verify.initVerify(cert.getPublicKey());verify.update(bytes);
  if(!verify.verify(Files.readAllBytes(out.resolve("catalog.sig"))))throw new Exception("Catalog signature did not verify");
} }
''')
store=pathlib.Path(os.environ.get('ANTISOCIAL_SIGNING_STORE',str(root/'android-user/debug.keystore')))
if not store.is_file():raise SystemExit('The APK signing keystore is required before building the AI catalog.')
for catalog,destination in [('ai-catalog','ai'),('context-media-catalog','context-media'),('translation-catalog','translation')]:
    subprocess.run([str(root/'jdk/bin/java'),str(source),str(store),str(repo/f'app/src/main/{catalog}/catalog.json'),str(args.output/destination)],check=True)
print('Signed model catalogs with the configured APK certificate.')
