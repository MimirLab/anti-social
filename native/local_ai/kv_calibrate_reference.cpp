// Development-only fixed diagonal calibration. Does not train/change weights.
// Observe post-RoPE, pre-cache-basis keys from the actual deployed graph.
#include "llama.h"
#include "ggml-backend.h"
#include "nlohmann/json.hpp"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <fstream>
#include <iostream>
#include <map>
#include <stdexcept>
#include <vector>
using json=nlohmann::ordered_json;
struct Stats {
    uint64_t rows=0;
    std::vector<double> mean=std::vector<double>(256,0),m2=mean;
    std::map<std::string,uint64_t> tensors;
    std::string error;
};
static bool observe(ggml_tensor*t,bool ask,void*opaque){
    auto &s=*static_cast<Stats*>(opaque);const std::string name=ggml_get_name(t);
    // The graph also calls the flattened projection Kcur before normalization.
    // Observe the actual post-RoPE tensor, not that identically named source.
    const bool wanted=name.rfind("Kcur-",0)==0 && t->op==GGML_OP_ROPE;
    if(ask)return wanted;
    if(!wanted)return true;
    try{
        if(t->type!=GGML_TYPE_F32||t->ne[0]!=256)throw std::runtime_error("Calibration key geometry is not D256 F32");
        std::vector<uint8_t>data(ggml_nbytes(t));ggml_backend_tensor_get(t,data.data(),0,data.size());
        for(int64_t b=0;b<t->ne[3];++b)for(int64_t token=0;token<t->ne[2];++token)for(int64_t head=0;head<t->ne[1];++head){
            ++s.rows;
            for(int64_t d=0;d<256;++d){float x;const size_t offset=b*t->nb[3]+token*t->nb[2]+head*t->nb[1]+d*t->nb[0];if(offset+4>data.size())throw std::runtime_error("Calibration tensor stride exceeds buffer");std::memcpy(&x,data.data()+offset,4);if(!std::isfinite(x))throw std::runtime_error("Nonfinite source key");double delta=x-s.mean[d];s.mean[d]+=delta/s.rows;s.m2[d]+=delta*(x-s.mean[d]);}
        }
        ++s.tensors[name];return true;
    }catch(const std::exception&e){s.error=e.what();return false;}
}
int main(int argc,char**argv){try{
    if(argc!=5)throw std::runtime_error("model corpus verified-model-sha256 token-limit");
    std::string modelpath=argv[1],sha=argv[3];int limit=std::stoi(argv[4]);if(sha.size()!=64||limit<512||limit>4096)throw std::runtime_error("Invalid calibration identity/budget");
    std::ifstream input(argv[2]);std::string text((std::istreambuf_iterator<char>(input)),{});if(text.empty())throw std::runtime_error("No calibration corpus");
    ggml_backend_load_all();llama_backend_init();auto mp=llama_model_default_params();mp.n_gpu_layers=0;mp.load_mtp=false;mp.check_tensors=true;
    auto *model=llama_model_load_from_file(modelpath.c_str(),mp);if(!model)throw std::runtime_error("Model failed");
    auto *vocab=llama_model_get_vocab(model);int n=-llama_tokenize(vocab,text.data(),text.size(),nullptr,0,true,true);std::vector<llama_token>tokens(n);if(llama_tokenize(vocab,text.data(),text.size(),tokens.data(),tokens.size(),true,true)!=n)throw std::runtime_error("Tokenizer mismatch");limit=std::min(limit,n)/32*32;if(limit<512)throw std::runtime_error("Calibration source too short");
    Stats stats;auto cp=llama_context_default_params();cp.n_ctx=limit+256;cp.n_batch=cp.n_ubatch=32;cp.n_seq_max=1;cp.n_threads=cp.n_threads_batch=4;cp.type_k=cp.type_v=GGML_TYPE_F16;cp.flash_attn_type=LLAMA_FLASH_ATTN_TYPE_ENABLED;cp.offload_kqv=false;cp.op_offload=false;cp.cb_eval=observe;cp.cb_eval_user_data=&stats;
    auto *ctx=llama_init_from_model(model,cp);if(!ctx)throw std::runtime_error("Calibration context failed");
    for(int pos=0;pos<limit;pos+=32){auto batch=llama_batch_get_one(tokens.data()+pos,32);if(llama_decode(ctx,batch))throw std::runtime_error("Calibration decode failed: "+stats.error);}
    if(!stats.error.empty()||stats.rows<512||stats.tensors.empty())throw std::runtime_error("Calibration did not observe enough source keys: "+stats.error);
    std::vector<double>sd(256);double logsum=0;
    for(int d=0;d<256;++d){sd[d]=std::sqrt(std::max(1e-8,stats.m2[d]/(stats.rows-1)));logsum+=std::log(sd[d]);}
    const double geometric=std::exp(logsum/256);std::vector<float>scales(256);
    for(int d=0;d<256;++d)scales[d]=std::clamp(float(sd[d]/geometric),0.25f,4.f);
    std::cout<<json({{"schema","antisocial-kv-fixed-channel-variance-v1"},{"model",modelpath},{"verified_model_sha256",sha},{"tokens",limit},{"observed_rows",stats.rows},{"observed_layers",stats.tensors},{"dimension",256},{"channel_scales",scales},{"raw_means",stats.mean},{"raw_stddev",sd},{"clamp_min",0.25},{"clamp_max",4.0},{"equations","K'=R D^-1 K; Q'=R D Q; V'=R V; output=R^T weighted(V')"},{"scope","one fixed diagonal shared across layers/heads; variance control, not the full KVarN tile codec; no weight changes"}}).dump(2)<<'\n';
    llama_free(ctx);llama_model_free(model);return 0;
}catch(const std::exception&e){std::cerr<<e.what()<<'\n';return 1;}}
