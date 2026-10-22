// Real-model cache ownership and checkpoint failure tests. No network.
#include "llama.h"
#include "ggml-backend.h"
#include "nlohmann/json.hpp"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <vector>
using json=nlohmann::ordered_json;
using Context=std::unique_ptr<llama_context,decltype(&llama_free)>;
static void require(bool ok,const char*message){if(!ok)throw std::runtime_error(message);}
int main(int argc,char**argv){try{
    require(argc==3,"model corpus");
    ggml_backend_load_all();llama_backend_init();auto mp=llama_model_default_params();mp.n_gpu_layers=0;mp.load_mtp=false;
    std::unique_ptr<llama_model,decltype(&llama_model_free)> model(llama_model_load_from_file(argv[1],mp),llama_model_free);require(bool(model),"Model failed");
    std::ifstream source(argv[2]);std::string text((std::istreambuf_iterator<char>(source)),{});auto vocab=llama_model_get_vocab(model.get());
    int n=-llama_tokenize(vocab,text.data(),text.size(),nullptr,0,true,true);require(n>1024,"Corpus too short");std::vector<llama_token>tokens(n);require(llama_tokenize(vocab,text.data(),text.size(),tokens.data(),n,true,true)==n,"Tokenization failed");
    auto cp=llama_context_default_params();cp.n_ctx=2048;cp.n_batch=cp.n_ubatch=32;cp.n_seq_max=1;cp.n_threads=cp.n_threads_batch=4;cp.offload_kqv=cp.op_offload=false;cp.type_k=cp.type_v=GGML_TYPE_PQ4_0;cp.kv_protected_initial=16;cp.kv_protected_recent=256;cp.flash_attn_type=LLAMA_FLASH_ATTN_TYPE_ENABLED;
    auto make=[&](llama_context_params p){Context ctx(llama_init_from_model(model.get(),p),llama_free);require(bool(ctx),"Context failed");return ctx;};
    auto ctx=make(cp);
    auto decode=[&](llama_context*c,int from,int count){while(count){int step=std::min(count,32);auto batch=llama_batch_get_one(tokens.data()+from,step);require(llama_decode(c,batch)==0,"Decode failed");from+=step;count-=step;}};
    auto logits=[&](llama_context*c){auto *p=llama_get_logits_ith(c,-1);size_t size=llama_vocab_n_tokens(vocab);std::vector<float>v(p,p+size);for(float x:v)require(std::isfinite(x),"Nonfinite logits");return v;};
    auto same=[&](llama_context*c,const std::vector<float>&expected){auto actual=logits(c);double maxdiff=0;for(size_t j=0;j<actual.size();++j)maxdiff=std::max(maxdiff,std::abs(double(actual[j])-expected[j]));require(maxdiff<0.00001,"Restored state changed logits");return maxdiff;};
    // Deliberately cross exact-ring wraps with uneven final microbatches.
    int consumed=0;for(int count:{1,17,31,32,239,64}){decode(ctx.get(),consumed,count);consumed+=count;}
    require(consumed==384,"Invalid fixture boundary");
    size_t size=llama_state_seq_get_size(ctx.get(),0);std::vector<uint8_t>snapshot(size);require(size>0&&llama_state_seq_get_data(ctx.get(),snapshot.data(),size,0)==size,"Sequence save failed");
    decode(ctx.get(),384,64);auto expected=logits(ctx.get());
    llama_memory_clear(llama_get_memory(ctx.get()),true);
    require(llama_state_seq_set_data(ctx.get(),snapshot.data(),size,0)==size,"Sequence restore failed");decode(ctx.get(),384,64);double replay_error=same(ctx.get(),expected);
    size_t fullsize=llama_state_get_size(ctx.get());std::vector<uint8_t>full(fullsize);require(fullsize&&llama_state_get_data(ctx.get(),full.data(),fullsize)==fullsize,"Full save failed");
    decode(ctx.get(),448,32);auto full_expected=logits(ctx.get());llama_memory_clear(llama_get_memory(ctx.get()),true);
    require(llama_state_set_data(ctx.get(),full.data(),fullsize)==fullsize,"Full restore failed");decode(ctx.get(),448,32);double full_error=same(ctx.get(),full_expected);
    // A truncated state is a typed failure. The bridge must reset rather than
    // continue with partially restored attention and stale recurrent memory.
    require(llama_state_seq_set_data(ctx.get(),snapshot.data(),size-1,0)==0,"Truncated state accepted");
    llama_memory_clear(llama_get_memory(ctx.get()),true);
    require(llama_memory_seq_pos_max(llama_get_memory(ctx.get()),0)<0,"Explicit reset retained positions");
    decode(ctx.get(),0,32);auto fresh_expected=logits(ctx.get());llama_memory_clear(llama_get_memory(ctx.get()),true);decode(ctx.get(),0,32);same(ctx.get(),fresh_expected);
    ctx.reset();
    int rejected=0;
    for(int kind=0;kind<4;++kind){
        auto changed=cp;std::vector<float>scales(256,1.f);
        if(kind==0)changed.kv_rotation=2;
        if(kind==1){scales[17]=1.25f;changed.kv_channel_scale=scales.data();changed.kv_channel_scale_count=scales.size();}
        if(kind==2)changed.kv_protected_initial=8;
        if(kind==3)changed.type_v=GGML_TYPE_PQ3_0;
        auto other=make(changed);
        require(llama_state_seq_set_data(other.get(),snapshot.data(),size,0)==0,"Mismatched cache recipe accepted");++rejected;
        llama_memory_clear(llama_get_memory(other.get()),true);decode(other.get(),0,32);logits(other.get());
    }
    // The actual basis bytes are checked, not merely the public rotation name.
    uint32_t magic=0x4c4b5632;std::vector<size_t>found;
    for(size_t i=0;i+36<=snapshot.size();++i)if(std::memcmp(snapshot.data()+i,&magic,4)==0)found.push_back(i);
    require(found.size()==1,"Ambiguous recipe-header fixture");
    auto corrupt=snapshot;corrupt[found[0]+28]^=0x01; // basis_id after 5 u32 + calibration u64
    auto finalctx=make(cp);require(llama_state_seq_set_data(finalctx.get(),corrupt.data(),corrupt.size(),0)==0,"Changed basis fingerprint accepted");++rejected;
    llama_memory_clear(llama_get_memory(finalctx.get()),true);decode(finalctx.get(),0,32);same(finalctx.get(),fresh_expected);
    auto invalid=cp;invalid.kv_protected_recent=0;require(!llama_init_from_model(model.get(),invalid),"Orphan recipe fields silently ignored");++rejected;
    invalid=cp;invalid.n_seq_max=2;require(!llama_init_from_model(model.get(),invalid),"Unsupported multiple sequences admitted");++rejected;
    std::cout<<json({{"passed",true},{"uneven_prefill_tokens",consumed},{"sequence_replay_max_error",replay_error},{"full_replay_max_error",full_error},{"mismatch_or_invalid_descriptors_rejected",rejected},{"truncated_snapshot_rejected",true},{"failure_recovery","explicit reset then fresh replay"}}).dump(2)<<'\n';
    return 0;
}catch(const std::exception&e){std::cerr<<e.what()<<'\n';return 1;}}
