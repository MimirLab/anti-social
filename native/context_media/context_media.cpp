/* AntiSocial GPL-3.0-or-later. Tesseract Apache-2.0; Whisper MIT. Offline data only. */
#include "context_media.h"
#include <tesseract/baseapi.h>
#include <tesseract/resultiterator.h>
#include <tesseract/ocrclass.h>
#include "whisper.h"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <fcntl.h>
#include <iomanip>
#include <locale>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <unordered_map>

namespace antisocial_media {
namespace {
std::mutex jobs_mutex, lane;
std::unordered_map<std::string,std::shared_ptr<std::atomic<bool>>> jobs;
whisper_context * speech=nullptr;
std::string speech_path,vad_path;
int speech_threads=2;
std::shared_ptr<std::atomic<bool>> job(const std::string & id) {
    std::lock_guard<std::mutex> lock(jobs_mutex);
    auto it=jobs.find(id);if(it==jobs.end())throw std::runtime_error("Media job is closed");return it->second;
}
void check(const std::shared_ptr<std::atomic<bool>> & value) {if(value->load())throw std::runtime_error("Media extraction cancelled");}
std::string quote(const std::string & value) {
    std::ostringstream out;out<<'"';
    for(unsigned char c:value){switch(c){case '"':out<<"\\\"";break;case '\\':out<<"\\\\";break;case '\n':out<<"\\n";break;case '\r':out<<"\\r";break;case '\t':out<<"\\t";break;default:if(c<32)out<<"\\u00"<<std::hex<<std::setw(2)<<std::setfill('0')<<int(c)<<std::dec;else out<<char(c);}}
    out<<'"';return out.str();
}
std::unique_ptr<tesseract::TessBaseAPI> ocr_engine(const std::string & directory,const std::string & language) {
    if(language.empty()||language.size()>16||language.find_first_not_of("abcdefghijklmnopqrstuvwxyz_")!=std::string::npos)throw std::runtime_error("Unknown OCR language");
    auto api=std::make_unique<tesseract::TessBaseAPI>();
    if(api->Init(directory.c_str(),language.c_str(),tesseract::OEM_LSTM_ONLY)!=0)throw std::runtime_error("The OCR language model could not be loaded");
    api->SetVariable("debug_file","/dev/null");api->SetPageSegMode(tesseract::PSM_AUTO);
    return api;
}
struct MappedPcm {
    int fd=-1;size_t size=0;void * bytes=MAP_FAILED;
    explicit MappedPcm(const std::string & path) {
        fd=::open(path.c_str(),O_RDONLY|O_CLOEXEC);if(fd<0)throw std::runtime_error("Decoded local audio is unavailable");
        struct stat st{};if(fstat(fd,&st)!=0||st.st_size<=0||st.st_size%4||st.st_size>76800000){::close(fd);fd=-1;throw std::runtime_error("Audio exceeds the bounded local decoding plan");}
        size=static_cast<size_t>(st.st_size);bytes=mmap(nullptr,size,PROT_READ,MAP_PRIVATE,fd,0);
        if(bytes==MAP_FAILED){::close(fd);fd=-1;throw std::runtime_error("Could not map the decoded audio");}
    }
    ~MappedPcm(){if(bytes!=MAP_FAILED)munmap(bytes,size);if(fd>=0)::close(fd);}
};
}
void start(const std::string & id){if(id.empty()||id.size()>80)throw std::runtime_error("Invalid media job");std::lock_guard<std::mutex> lock(jobs_mutex);if(!jobs.empty())throw std::runtime_error("Media worker is busy");jobs[id]=std::make_shared<std::atomic<bool>>(false);}
void cancel(const std::string & id){std::lock_guard<std::mutex> lock(jobs_mutex);auto it=jobs.find(id);if(it!=jobs.end())it->second->store(true);}
void finish(const std::string & id){std::lock_guard<std::mutex> lock(jobs_mutex);jobs.erase(id);}
void prepare_ocr(const std::string & directory,const std::string & language){std::lock_guard<std::mutex> lock(lane);ocr_engine(directory,language);}
std::string ocr(const std::string & directory,const std::string & language,const uint8_t * pixels,int width,int height,int stride,int channels,const std::string & id){
    auto cancel=job(id);check(cancel);std::lock_guard<std::mutex> lock(lane);
    if(width<=0||height<=0||int64_t(width)*height>12000000||channels<1||channels>4||int64_t(stride)<int64_t(width)*channels)throw std::runtime_error("Image exceeds the local OCR plan");
    auto api=ocr_engine(directory,language);check(cancel);api->SetImage(pixels,width,height,channels,stride);api->SetSourceResolution(200);
    tesseract::ETEXT_DESC monitor;monitor.cancel=[](void * data,int){return static_cast<std::atomic<bool> *>(data)->load();};monitor.cancel_this=cancel.get();monitor.set_deadline_msecs(45000);
    int status=api->Recognize(&monitor);check(cancel);if(status!=0)throw std::runtime_error("OCR could not finish this image within its processing plan");
    std::unique_ptr<char[]> raw(api->GetUTF8Text());std::string text=raw?raw.get():"";bool truncated=text.size()>80000;if(truncated)text.resize(80000);
    std::ostringstream lines;lines.imbue(std::locale::classic());lines<<'[';bool first=true;int count=0;
    std::unique_ptr<tesseract::ResultIterator> it(api->GetIterator());
    if(it)do{check(cancel);std::unique_ptr<char[]> words(it->GetUTF8Text(tesseract::RIL_TEXTLINE));if(!words||!words[0])continue;
        if(++count>512){truncated=true;break;}int l=0,t=0,r=0,b=0;it->BoundingBox(tesseract::RIL_TEXTLINE,&l,&t,&r,&b);float confidence=std::clamp(it->Confidence(tesseract::RIL_TEXTLINE),0.f,100.f);
        if(!first)lines<<',';first=false;lines<<"{\"text\":"<<quote(std::string(words.get()).substr(0,2000))<<",\"confidence\":"<<confidence<<",\"left\":"<<l<<",\"top\":"<<t<<",\"right\":"<<r<<",\"bottom\":"<<b<<'}';
    }while(it->Next(tesseract::RIL_TEXTLINE));
    lines<<']';float confidence=std::clamp(float(api->MeanTextConf()),0.f,100.f);
    std::ostringstream out;out.imbue(std::locale::classic());out<<"{\"text\":"<<quote(text)<<",\"lines\":"<<lines.str()<<",\"language\":"<<quote(language)<<",\"width\":"<<width<<",\"height\":"<<height<<",\"meanConfidence\":"<<confidence<<",\"uncertain\":"<<(confidence<65?"true":"false")<<",\"truncated\":"<<(truncated?"true":"false")<<",\"engine\":\"Tesseract "<<tesseract::TessBaseAPI::Version()<<" · tessdata_fast\"}";return out.str();
}
void prepare_speech(const std::string & model,const std::string & vad,int threads){
    std::lock_guard<std::mutex> lock(lane);if(speech&&model==speech_path&&vad==vad_path)return;
    if(speech)whisper_free(speech);speech=nullptr;speech_path.clear();vad_path.clear();
    auto params=whisper_context_default_params();params.use_gpu=false;params.flash_attn=false;
    speech=whisper_init_from_file_with_params(model.c_str(),params);if(!speech)throw std::runtime_error("The local speech model could not be loaded");
    auto vp=whisper_vad_default_context_params();vp.use_gpu=false;vp.n_threads=std::clamp(threads,1,2);
    auto * probe=whisper_vad_init_from_file_with_params(vad.c_str(),vp);if(!probe){whisper_free(speech);speech=nullptr;throw std::runtime_error("The voice activity model could not be loaded");}whisper_vad_free(probe);
    speech_path=model;vad_path=vad;speech_threads=std::clamp(threads,1,2);
}
std::string transcribe(const std::string & path,const std::string & id){
    auto cancel=job(id);check(cancel);std::lock_guard<std::mutex> lock(lane);if(!speech)throw std::runtime_error("Activate the local speech model first");
    MappedPcm audio(path);const int samples=int(audio.size/4);const float * values=static_cast<const float *>(audio.bytes);
    for(int i=0;i<samples;i+=4096){check(cancel);if(!std::isfinite(values[i]))throw std::runtime_error("Invalid decoded audio");}
    auto p=whisper_full_default_params(WHISPER_SAMPLING_GREEDY);p.n_threads=speech_threads;p.translate=false;p.language="auto";p.no_context=true;p.print_realtime=false;p.print_progress=false;p.print_timestamps=false;p.print_special=false;p.token_timestamps=true;p.suppress_blank=true;p.suppress_nst=true;p.temperature=0;p.temperature_inc=0;p.greedy.best_of=1;p.vad=true;p.vad_model_path=vad_path.c_str();p.vad_params=whisper_vad_default_params();p.vad_params.max_speech_duration_s=30;
    p.abort_callback=[](void * data){return static_cast<std::atomic<bool> *>(data)->load();};p.abort_callback_user_data=cancel.get();
    int status=whisper_full(speech,p,values,samples);check(cancel);if(status!=0)throw std::runtime_error("Local speech recognition could not finish this audio");
    const int segments=whisper_full_n_segments(speech);std::string text;std::ostringstream rows;rows.imbue(std::locale::classic());rows<<'[';bool uncertain=false,truncated=false;
    for(int i=0;i<segments;i++){check(cancel);if(i>=1024||text.size()>80000){truncated=true;break;}std::string part=whisper_full_get_segment_text(speech,i);if(part.size()>4000){part.resize(4000);truncated=true;}text+=part;
        float probability=0;int tokens=0;for(int j=0;j<whisper_full_n_tokens(speech,i);j++){auto token=whisper_full_get_token_data(speech,i,j);if(token.id<whisper_token_eot(speech)&&std::isfinite(token.p)){probability+=std::clamp(token.p,0.f,1.f);tokens++;}}
        probability=tokens?probability/tokens:0;float silence=whisper_full_get_segment_no_speech_prob(speech,i);if(!std::isfinite(silence))silence=1;bool weak=probability<0.55f||silence>0.4f;uncertain=uncertain||weak;
        if(i)rows<<',';rows<<"{\"startMs\":"<<whisper_full_get_segment_t0(speech,i)*10<<",\"endMs\":"<<whisper_full_get_segment_t1(speech,i)*10<<",\"text\":"<<quote(part)<<",\"meanTokenProbability\":"<<probability<<",\"noSpeechProbability\":"<<std::clamp(silence,0.f,1.f)<<",\"uncertain\":"<<(weak?"true":"false")<<'}';
    }
    rows<<']';const char * language=segments?whisper_lang_str(whisper_full_lang_id(speech)):"";
    std::ostringstream out;out<<"{\"text\":"<<quote(text)<<",\"segments\":"<<rows.str()<<",\"language\":"<<quote(language?language:"")<<",\"durationMs\":"<<int64_t(samples)*1000/16000<<",\"hasSpeech\":"<<(segments?"true":"false")<<",\"uncertain\":"<<(uncertain?"true":"false")<<",\"truncated\":"<<(truncated?"true":"false")<<",\"engine\":\"Whisper base multilingual · Q5_1 · Silero VAD\"}";return out.str();
}
void release(){std::lock_guard<std::mutex> ownership(jobs_mutex);if(!jobs.empty())return;std::lock_guard<std::mutex> lock(lane);if(speech)whisper_free(speech);speech=nullptr;speech_path.clear();vad_path.clear();}
}
