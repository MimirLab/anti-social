#include "context_media.h"
#include <jni.h>
#include <android/bitmap.h>
#include <stdexcept>
namespace {
std::string str(JNIEnv * env,jstring value){if(!value)throw std::runtime_error("Missing media argument");const char * chars=env->GetStringUTFChars(value,nullptr);if(!chars)throw std::runtime_error("Cannot read media argument");std::string result(chars);env->ReleaseStringUTFChars(value,chars);return result;}
jbyteArray bytes(JNIEnv * env,const std::string & value){if(value.size()>256*1024)throw std::runtime_error("Extracted media text exceeds the result plan");auto result=env->NewByteArray(int(value.size()));if(result)env->SetByteArrayRegion(result,0,int(value.size()),reinterpret_cast<const jbyte *>(value.data()));return result;}
void fail(JNIEnv * env,const std::exception & error){if(!env->ExceptionCheck())env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),error.what());}
}
#define CM(name) Java_app_antisocial_android_ContextMediaNative_##name
extern "C" JNIEXPORT void JNICALL CM(start)(JNIEnv * e,jclass,jstring id){try{antisocial_media::start(str(e,id));}catch(const std::exception & x){fail(e,x);}}
extern "C" JNIEXPORT void JNICALL CM(cancel)(JNIEnv * e,jclass,jstring id){try{antisocial_media::cancel(str(e,id));}catch(const std::exception & x){fail(e,x);}}
extern "C" JNIEXPORT void JNICALL CM(finish)(JNIEnv * e,jclass,jstring id){try{antisocial_media::finish(str(e,id));}catch(const std::exception & x){fail(e,x);}}
extern "C" JNIEXPORT void JNICALL CM(prepareOcr)(JNIEnv * e,jclass,jstring path,jstring language){try{antisocial_media::prepare_ocr(str(e,path),str(e,language));}catch(const std::exception & x){fail(e,x);}}
extern "C" JNIEXPORT void JNICALL CM(prepareSpeech)(JNIEnv * e,jclass,jstring model,jstring vad,jint threads){try{antisocial_media::prepare_speech(str(e,model),str(e,vad),threads);}catch(const std::exception & x){fail(e,x);}}
extern "C" JNIEXPORT jbyteArray JNICALL CM(ocr)(JNIEnv * e,jclass,jstring path,jstring language,jobject bitmap,jstring id){
    void * pixels=nullptr;
    try{AndroidBitmapInfo info{};if(AndroidBitmap_getInfo(e,bitmap,&info)!=ANDROID_BITMAP_RESULT_SUCCESS||info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888)throw std::runtime_error("OCR requires a decoded software image");if(AndroidBitmap_lockPixels(e,bitmap,&pixels)!=ANDROID_BITMAP_RESULT_SUCCESS)throw std::runtime_error("Could not read local image pixels");auto result=antisocial_media::ocr(str(e,path),str(e,language),static_cast<uint8_t *>(pixels),int(info.width),int(info.height),int(info.stride),4,str(e,id));AndroidBitmap_unlockPixels(e,bitmap);pixels=nullptr;return bytes(e,result);}catch(const std::exception & x){if(pixels)AndroidBitmap_unlockPixels(e,bitmap);fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT jbyteArray JNICALL CM(transcribe)(JNIEnv * e,jclass,jstring path,jstring id){try{return bytes(e,antisocial_media::transcribe(str(e,path),str(e,id)));}catch(const std::exception & x){fail(e,x);return nullptr;}}
extern "C" JNIEXPORT void JNICALL CM(release)(JNIEnv * e,jclass){try{antisocial_media::release();}catch(const std::exception & x){fail(e,x);}}
