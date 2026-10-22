/* anti social, GPL-3.0-or-later. Narrow bridge to pinned c2pa-c shipped by SDK 0.0.14.
 * Equivalent to the upstream MIT JNI reader path, but uses OUR deny-network context.
 * No HTTP, file operations, default context, or caller-provided native addresses. */
#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stddef.h>

JNIEXPORT jobject JNICALL Java_app_antisocial_android_C2paDetachedBridge_create(
    JNIEnv *e, jclass cls, jobject context, jobject stream, jstring mime, jbyteArray manifest) {
    (void)cls;
    if (!context || !stream || !mime || !manifest) return NULL;
    jclass cc=(*e)->GetObjectClass(e,context), sc=(*e)->GetObjectClass(e,stream);
    jfieldID ptr=(*e)->GetFieldID(e,cc,"ptr","J");
    jmethodID raw=(*e)->GetMethodID(e,sc,"getRawPtr$library","()J");
    if (!ptr || !raw || (*e)->ExceptionCheck(e)) return NULL;
    jlong cp=(*e)->GetLongField(e,context,ptr), sp=(*e)->CallLongMethod(e,stream,raw);
    if (!cp || !sp || (*e)->ExceptionCheck(e)) return NULL;
    jsize n=(*e)->GetArrayLength(e,manifest);
    if (n<=0 || n>4*1024*1024) return NULL;
    void *lib=dlopen("libc2pa_c.so",RTLD_NOW|RTLD_LOCAL);
    if (!lib) return NULL;
    void *(*from_context)(void *)=dlsym(lib,"c2pa_reader_from_context");
    void *(*with_manifest)(void *,const char *,void *,const unsigned char *,size_t)=dlsym(lib,"c2pa_reader_with_manifest_data_and_stream");
    if (!from_context || !with_manifest) {dlclose(lib);return NULL;}
    const char *format=(*e)->GetStringUTFChars(e,mime,NULL);
    jbyte *data=(*e)->GetByteArrayElements(e,manifest,NULL);
    void *reader=NULL;
    if (format && data) {
        void *base=from_context((void *)(uintptr_t)cp);
        if(base) reader=with_manifest(base,format,(void *)(uintptr_t)sp,(const unsigned char *)data,(size_t)n);
    }
    if(data) (*e)->ReleaseByteArrayElements(e,manifest,data,JNI_ABORT);
    if(format) (*e)->ReleaseStringUTFChars(e,mime,format);
    jobject result=NULL;
    if (reader) {
        jclass rc=(*e)->FindClass(e,"org/contentauth/c2pa/Reader");
        jmethodID ctor=rc ? (*e)->GetMethodID(e,rc,"<init>","(J)V") : NULL;
        if (ctor) result=(*e)->NewObject(e,rc,ctor,(jlong)(uintptr_t)reader);
        if (!result) {void (*release)(void *)=dlsym(lib,"c2pa_free");if(release)release(reader);}
    }
    dlclose(lib);
    return result;
}
