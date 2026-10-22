/* AntiSocial, GPL-3.0-or-later. Offline CPU worker; no sockets, tools or downloads. */
#include <algorithm>
#include <iostream>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>
#include "fasttext.h"
#include "common/logging.h"
#include "translator/service.h"
#include "translator/parser.h"
#include "translator/response_options.h"
#if ANTISOCIAL_ANDROID
#include <jni.h>
#endif

namespace {
std::unique_ptr<fasttext::FastText> detector;
std::string detectorPath;
std::unique_ptr<marian::bergamot::BlockingService> service;
std::shared_ptr<marian::bergamot::TranslationModel> model;
std::string modelPath;
void release() { model.reset(); service.reset(); modelPath.clear(); }

std::string jsonString(const std::string &value) {
  std::ostringstream out; out << '"';
  for (unsigned char c : value) {
    if (c == '"' || c == '\\') { out << '\\' << c; }
    else if (c < 32) { const char *hex="0123456789abcdef"; out << "\\u00" << hex[c >> 4] << hex[c & 15]; }
    else out << c;
  }
  out << '"'; return out.str();
}
std::string detect(const std::string &path, std::string text) {
  if (text.size() > 32768) throw std::invalid_argument("Language sample exceeds its limit");
  if (!detector || detectorPath != path) {
    auto ready = std::make_unique<fasttext::FastText>(); ready->loadModel(path);
    detector = std::move(ready); detectorPath = path;
  }
  std::replace(text.begin(), text.end(), '\n', ' ');
  std::istringstream input(text); std::vector<std::pair<fasttext::real,std::string>> predictions;
  detector->predictLine(input,predictions,3,0);
  std::ostringstream out; out << '[';
  for (size_t i=0;i<predictions.size();++i) {
    if(i)out<<',';
    std::string language=predictions[i].second;
    if(language.compare(0,9,"__label__")==0)language.erase(0,9);
    out << "{\"language\":" << jsonString(language) << ",\"confidence\":" << std::clamp<double>(predictions[i].first,0,1) << '}';
  }
  out << ']'; return out.str();
}
std::string translate(const std::string &path, std::string text) {
  // This service uses BlockingService and a single replica. Marian documents
  // exception mode for this serial path; failed state is discarded below.
  marian::setThrowExceptionOnAbort(true);
  if(text.empty() || text.size()>16384)throw std::invalid_argument("Translation passage exceeds its limit");
  try {
  if(!model || modelPath!=path) {
    model.reset(); service.reset(); modelPath.clear();
    marian::bergamot::BlockingService::Config config; config.cacheSize=0;
    auto ready=std::make_unique<marian::bergamot::BlockingService>(config);
    auto options=marian::bergamot::parseOptionsFromFilePath(path);
    auto prepared=std::make_shared<marian::bergamot::TranslationModel>(options,1);
    model=std::move(prepared); service=std::move(ready); modelPath=path;
  }
  marian::bergamot::ResponseOptions options; options.HTML=true; options.alignment=true;
  std::vector<std::string> sources; sources.emplace_back(std::move(text));
  std::vector<marian::bergamot::Response> responses;
  responses=service->translateMultiple(model,std::move(sources),{options});
  if(responses.size()!=1 || responses[0].target.text.empty() || responses[0].target.text.size()>65536)
    throw std::runtime_error("The offline translator returned an incomplete passage");
  return responses[0].target.text;
  } catch(...) {release();throw;}
}
}

#if ANTISOCIAL_ANDROID
namespace {
std::string bytes(JNIEnv *env,jbyteArray value,size_t maximum) {
  if(!value)throw std::invalid_argument("Missing local translation input");
  const auto n=env->GetArrayLength(value);
  if(n<0 || static_cast<size_t>(n)>maximum)throw std::invalid_argument("Translation input exceeds its limit");
  std::string result(n,'\0'); env->GetByteArrayRegion(value,0,n,reinterpret_cast<jbyte*>(result.data())); return result;
}
jbyteArray result(JNIEnv *env,const std::string &value) {
  auto out=env->NewByteArray(value.size()); if(out)env->SetByteArrayRegion(out,0,value.size(),reinterpret_cast<const jbyte*>(value.data())); return out;
}
void fail(JNIEnv *env,const std::exception &error) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),error.what()); }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_app_antisocial_android_LanguageNative_detect(JNIEnv *env,jclass,jbyteArray path,jbyteArray text) {
  try {return result(env,detect(bytes(env,path,4096),bytes(env,text,32768)));}catch(const std::exception &e){fail(env,e);return nullptr;}
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_app_antisocial_android_LanguageNative_translate(JNIEnv *env,jclass,jbyteArray path,jbyteArray text) {
  try {return result(env,translate(bytes(env,path,4096),bytes(env,text,16384)));}catch(const std::exception &e){fail(env,e);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_app_antisocial_android_LanguageNative_release(JNIEnv *,jclass) { release(); }
#else
int main(int argc,char **argv) {
  if(argc!=3)return 2;
  try {std::string text;while(std::getline(std::cin,text))std::cout<<(std::string(argv[1])=="detect"?detect(argv[2],text):translate(argv[2],text))<<'\n';}
  catch(const std::exception &error){std::cerr<<error.what()<<'\n';return 1;}
}
#endif
