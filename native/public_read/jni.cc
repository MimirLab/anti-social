/* anti social, GPL-3.0-or-later. Byte-only JNI bridge. No sockets or file IO.
 * Handles are bounded registry identities, not caller-supplied native pointers.
 * Every engine is used and released by its creating Java thread. */
#include "engine.h"
#include <jni.h>
#include <array>
#include <atomic>
#include <cstring>
#include <memory>
#include <limits>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace {
struct State {
    JNIEnv *env;std::thread::id thread;AsReadEngine *engine=nullptr;jobject callback=nullptr;jmethodID verify=nullptr,decoded=nullptr;
    ~State(){as_read_destroy(engine);if(callback&&thread==std::this_thread::get_id())env->DeleteGlobalRef(callback);}
};
std::mutex guard;std::unordered_map<jlong,std::unique_ptr<State>> states;jlong next_id=1;
void error(JNIEnv *e,const char *message) {
    if(!e->ExceptionCheck()){jclass c=e->FindClass("java/io/IOException");if(c){e->ThrowNew(c,message);e->DeleteLocalRef(c);}}
}
State *state(JNIEnv *e,jlong id) {
    auto it=states.find(id);
    if(it==states.end()||it->second->thread!=std::this_thread::get_id()){error(e,"Native read handle expired or changed owner");return nullptr;}
    return it->second.get();
}
jbyteArray bytes(JNIEnv *e,const uint8_t *p,size_t n) {
    if(n>262144||(!p&&n)){error(e,"Invalid native handshake field");return nullptr;}
    auto out=e->NewByteArray(static_cast<jsize>(n));if(out&&n)e->SetByteArrayRegion(out,0,n,reinterpret_cast<const jbyte *>(p));return out;
}
int verify(void *owner,const char *,const uint8_t *const *chain,const size_t *sizes,size_t count) {
    auto *s=static_cast<State *>(owner);JNIEnv *e=s->env;
    if(e->ExceptionCheck()||e->PushLocalFrame(24)<0)return 0;
    struct Frame {JNIEnv *env;~Frame(){env->PopLocalFrame(nullptr);}} frame{e};
    jclass byte_array=e->FindClass("[B");if(!byte_array)return 0;
    jobjectArray certs=e->NewObjectArray(count,byte_array,nullptr);e->DeleteLocalRef(byte_array);if(!certs)return 0;
    for(size_t i=0;i<count&&!e->ExceptionCheck();++i){auto b=bytes(e,chain[i],sizes[i]);if(b){e->SetObjectArrayElement(certs,i,b);e->DeleteLocalRef(b);}}
    if(e->ExceptionCheck())return 0;
    auto protocol=e->NewStringUTF(as_read_peer_protocol(s->engine));if(!protocol)return 0;
    auto cipher=e->NewStringUTF(as_read_peer_cipher(s->engine));if(!cipher)return 0;
    size_t n=0;const uint8_t *p=as_read_peer_ocsp(s->engine,&n);auto ocsp=bytes(e,p,n);if(!ocsp)return 0;
    p=as_read_peer_sct(s->engine,&n);auto sct=bytes(e,p,n);if(!sct)return 0;
    const jboolean ok=!e->ExceptionCheck()&&protocol&&cipher&&ocsp&&sct?e->CallBooleanMethod(s->callback,s->verify,certs,protocol,cipher,ocsp,sct):JNI_FALSE;
    e->DeleteLocalRef(certs);if(protocol)e->DeleteLocalRef(protocol);if(cipher)e->DeleteLocalRef(cipher);if(ocsp)e->DeleteLocalRef(ocsp);if(sct)e->DeleteLocalRef(sct);
    return !e->ExceptionCheck()&&ok==JNI_TRUE;
}
int decoded(void *owner,size_t n) {
    auto *s=static_cast<State *>(owner);if(n>32768){error(s->env,"Invalid decoded accounting chunk");return 0;}
    const jboolean ok=s->env->CallBooleanMethod(s->callback,s->decoded,static_cast<jint>(n));return !s->env->ExceptionCheck()&&ok==JNI_TRUE;
}
std::string text(JNIEnv *e,jstring value) {
    if(!value||e->GetStringUTFLength(value)>32768){error(e,"Invalid native request field");return {};}
    const char *p=e->GetStringUTFChars(value,nullptr);if(!p)return {};std::string result(p);e->ReleaseStringUTFChars(value,p);return result;
}
void u32(std::vector<uint8_t>&out,uint32_t n){for(int shift=24;shift>=0;shift-=8)out.push_back(static_cast<uint8_t>(n>>shift));}
}

extern "C" JNIEXPORT jint JNICALL Java_app_antisocial_android_PublicReadNative_supported(JNIEnv *,jclass){return as_read_supported_cohorts();}
extern "C" JNIEXPORT jstring JNICALL Java_app_antisocial_android_PublicReadNative_versions(JNIEnv *e,jclass){return e->NewStringUTF(as_read_versions());}
extern "C" JNIEXPORT jlong JNICALL Java_app_antisocial_android_PublicReadNative_create(JNIEnv *e,jclass,jint cohort,jstring host,jint port,jstring path,jobjectArray headers,jint limit,jobject callback) {
    std::lock_guard<std::mutex> lock(guard);
    if(!callback||!headers||limit<1||states.size()>=8||next_id<=0||next_id==std::numeric_limits<jlong>::max()){error(e,"Native read admission failed");return 0;}
    const jsize count=e->GetArrayLength(headers);if(count<2||count>48||count%2){error(e,"Invalid native request headers");return 0;}
    try {
        auto s=std::make_unique<State>();s->env=e;s->thread=std::this_thread::get_id();
        jclass cls=e->GetObjectClass(callback);if(!cls)return 0;
        s->verify=e->GetMethodID(cls,"verifyPeer","([[BLjava/lang/String;Ljava/lang/String;[B[B)Z");
        if(!s->verify){e->DeleteLocalRef(cls);return 0;}
        s->decoded=e->GetMethodID(cls,"decodedBytes","(I)Z");e->DeleteLocalRef(cls);if(!s->verify||!s->decoded)return 0;
        const auto hostname=text(e,host),target=text(e,path);std::vector<std::string> values;values.reserve(count);
        for(jsize i=0;i<count&&!e->ExceptionCheck();++i){auto value=static_cast<jstring>(e->GetObjectArrayElement(headers,i));values.push_back(text(e,value));if(value)e->DeleteLocalRef(value);}
        if(e->ExceptionCheck())return 0;
        std::vector<AsReadHeader> pairs;for(jsize i=0;i<count;i+=2)pairs.push_back({values[i].c_str(),values[i+1].c_str()});
        s->callback=e->NewGlobalRef(callback);if(!s->callback)return 0;
        s->engine=as_read_create(cohort,hostname.c_str(),port,target.c_str(),pairs.data(),pairs.size(),limit,verify,s.get());
        if(!s->engine||!as_read_account_decoded(s->engine,decoded)){error(e,"Native read profile or request is unsupported");return 0;}
        const jlong id=next_id++;states.emplace(id,std::move(s));return id;
    }catch(const std::exception &){error(e,"Native read allocation failed");return 0;}
}
extern "C" JNIEXPORT jint JNICALL Java_app_antisocial_android_PublicReadNative_step(JNIEnv *e,jclass,jlong id) {
    std::lock_guard<std::mutex> lock(guard);auto *s=state(e,id);if(!s)return -1;
    try {const int result=as_read_step(s->engine);if(result<0)error(e,as_read_error(s->engine));return result;}
    catch(const std::exception &){error(e,"Native read processing failed");return -1;}
}
extern "C" JNIEXPORT void JNICALL Java_app_antisocial_android_PublicReadNative_feed(JNIEnv *e,jclass,jlong id,jbyteArray data,jint count) {
    std::lock_guard<std::mutex> lock(guard);auto *s=state(e,id);if(!s)return;
    if(!data||count<1||count>32768||count>e->GetArrayLength(data)){error(e,"Invalid encrypted input");return;}
    std::array<uint8_t,32768> input{};e->GetByteArrayRegion(data,0,count,reinterpret_cast<jbyte *>(input.data()));
    if(!e->ExceptionCheck()&&as_read_feed(s->engine,input.data(),count)<0)error(e,as_read_error(s->engine));
}
extern "C" JNIEXPORT jint JNICALL Java_app_antisocial_android_PublicReadNative_output(JNIEnv *e,jclass,jlong id,jbyteArray data) {
    std::lock_guard<std::mutex> lock(guard);auto *s=state(e,id);if(!s)return 0;
    if(!data||e->GetArrayLength(data)<32768){error(e,"Invalid encrypted output buffer");return 0;}
    std::array<uint8_t,32768> output{};const size_t n=as_read_output(s->engine,output.data(),output.size());
    if(n)e->SetByteArrayRegion(data,0,n,reinterpret_cast<const jbyte *>(output.data()));return n;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_app_antisocial_android_PublicReadNative_result(JNIEnv *e,jclass,jlong id) {
    std::lock_guard<std::mutex> lock(guard);auto *s=state(e,id);if(!s)return nullptr;
    const int status=as_read_status(s->engine);if(status<200||status>599){error(e,"Native response is incomplete");return nullptr;}
    try {
        size_t n=0;const uint8_t *body=as_read_body(s->engine,&n);std::vector<uint8_t> packet;packet.reserve(n+32784);
        u32(packet,status);const size_t count=as_read_header_count(s->engine);u32(packet,count);
        for(size_t i=0;i<count;++i)for(const char *v:{as_read_header_name(s->engine,i),as_read_header_value(s->engine,i)}){const size_t size=strlen(v);u32(packet,size);packet.insert(packet.end(),v,v+size);}
        u32(packet,n);if(n)packet.insert(packet.end(),body,body+n);
        auto out=e->NewByteArray(packet.size());if(out)e->SetByteArrayRegion(out,0,packet.size(),reinterpret_cast<const jbyte *>(packet.data()));return out;
    }catch(const std::exception &){error(e,"Native response allocation failed");return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_app_antisocial_android_PublicReadNative_dispose(JNIEnv *e,jclass,jlong id) {
    std::lock_guard<std::mutex> lock(guard);auto *s=state(e,id);if(!s)return;
    states.erase(id);
}
