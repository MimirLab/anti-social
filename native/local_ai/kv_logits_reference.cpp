// Reproducible cache-only comparison: identical model/tokens/batch boundaries.
// Reference logits are streamed to disk; no second resident model/context.
#include "llama.h"
#include "kv_position_profile.h"
#include "ggml-backend.h"
#include "nlohmann/json.hpp"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <fstream>
#include <iostream>
#include <numeric>
#include <stdexcept>
#include <string>
#include <vector>
using json=nlohmann::ordered_json;
template<class T>void write(std::ostream&o,const T&v){o.write(reinterpret_cast<const char*>(&v),sizeof(v));if(!o)throw std::runtime_error("reference write failed");}
template<class T>void read(std::istream&i,T&v){i.read(reinterpret_cast<char*>(&v),sizeof(v));if(!i)throw std::runtime_error("reference truncated");}
int main(int argc,char**argv){try{
    if(argc<7)throw std::runtime_error("model backend profile context corpus reference.bin [calibration.json] [--positioning=native|yarn2] [--compare-positioning]");
    std::string modelpath=argv[1],backend=argv[2],profile=argv[3],refpath=argv[6];int capacity=std::stoi(argv[4]);
    std::string positioning="native",calibration_path;bool compare_positioning=false;
    for(int i=7;i<argc;++i){
        const std::string arg=argv[i];
        if(arg.rfind("--positioning=",0)==0)positioning=arg.substr(14);
        else if(arg=="--compare-positioning")compare_positioning=true;
        else if(arg.rfind("--",0)==0 || !calibration_path.empty())throw std::runtime_error("Unknown or repeated argument");
        else calibration_path=arg;
    }
    if(positioning!="native"&&positioning!="yarn2")throw std::runtime_error("Unknown positional profile");
    const bool writing_reference=profile=="f16"&&!compare_positioning;
    std::ifstream corpusfile(argv[5]);std::string text((std::istreambuf_iterator<char>(corpusfile)),{});if(text.empty())throw std::runtime_error("missing frozen corpus");
    ggml_backend_load_all();llama_backend_init();auto mp=llama_model_default_params();mp.n_gpu_layers=0;mp.load_mtp=false;mp.check_tensors=true;
    std::vector<ggml_backend_dev_t>devices;
    if(backend=="vulkan"){
        for(size_t i=0;i<ggml_backend_dev_count();++i){auto d=ggml_backend_dev_get(i);if(std::string(ggml_backend_reg_name(ggml_backend_dev_backend_reg(d)))=="Vulkan"){devices={d,nullptr};break;}}
        if(devices.empty())throw std::runtime_error("Vulkan absent");mp.devices=devices.data();mp.n_gpu_layers=999;mp.split_mode=LLAMA_SPLIT_MODE_NONE;
    }else if(backend!="cpu")throw std::runtime_error("unknown backend");
    auto started=std::chrono::steady_clock::now();auto *model=llama_model_load_from_file(modelpath.c_str(),mp);if(!model)throw std::runtime_error("model load failed");
    auto vocab=llama_model_get_vocab(model);int count=-llama_tokenize(vocab,text.data(),text.size(),nullptr,0,true,true);std::vector<llama_token>tokens(count);
    int tokenized=llama_tokenize(vocab,text.data(),text.size(),tokens.data(),tokens.size(),true,true);if(tokenized!=count)throw std::runtime_error("tokenize mismatch");
    int limit=std::min(capacity-32,count-1);limit=limit/32*32;if(limit<512)throw std::runtime_error("fixture too small");
    auto cp=llama_context_default_params();cp.n_ctx=capacity;cp.n_batch=32;cp.n_ubatch=32;cp.n_seq_max=1;cp.n_threads=4;cp.n_threads_batch=4;
    cp.offload_kqv=backend=="vulkan";cp.op_offload=cp.offload_kqv;cp.type_k=cp.type_v=GGML_TYPE_F16;cp.flash_attn_type=LLAMA_FLASH_ATTN_TYPE_ENABLED;
    antisocial_ai::configure_position_profile(cp,model,positioning);
    if(profile=="q8")cp.type_k=cp.type_v=GGML_TYPE_Q8_0;
    else if(profile=="q4")cp.type_k=cp.type_v=GGML_TYPE_Q4_0;
    else if(profile=="pq4"||profile=="pq3"||profile=="pq4_pq3"||profile=="signed4"||profile=="planar4"||profile=="iso4"){
        cp.type_k=profile=="pq3"?GGML_TYPE_PQ3_0:GGML_TYPE_PQ4_0;cp.type_v=(profile=="pq3"||profile=="pq4_pq3")?GGML_TYPE_PQ3_0:GGML_TYPE_PQ4_0;cp.kv_rotation=profile=="signed4"?1:profile=="planar4"?2:profile=="iso4"?3:0;cp.kv_protected_recent=256;cp.kv_protected_initial=16;
    }else if(profile!="f16")throw std::runtime_error("unknown profile");
    std::vector<float> scales;
    std::string calibration_model_sha;
    if(!calibration_path.empty()){
        if(!cp.kv_protected_recent)throw std::runtime_error("Calibration requires a protected cache profile");
        std::ifstream file(calibration_path);json calibration;file>>calibration;
        if(calibration.at("schema")!="antisocial-kv-fixed-channel-variance-v1" || calibration.at("dimension")!=256)
            throw std::runtime_error("Unsupported calibration schema/geometry");
        scales=calibration.at("channel_scales").get<std::vector<float>>();
        calibration_model_sha=calibration.at("verified_model_sha256").get<std::string>();
        if(scales.size()!=256 || calibration_model_sha.size()!=64)throw std::runtime_error("Invalid calibration identity/size");
        for(float d:scales)if(!std::isfinite(d)||d<0.25f||d>4.f)throw std::runtime_error("Invalid calibration coefficient");
        cp.kv_channel_scale=scales.data();cp.kv_channel_scale_count=scales.size();
    }
    auto *ctx=llama_init_from_model(model,cp);if(!ctx)throw std::runtime_error("context failed");
    const json position_recipe=antisocial_ai::resolved_position_recipe(ctx,model);
    json reference_position_recipe;
    uint32_t nv=llama_vocab_n_tokens(vocab);std::fstream stream;
    uint32_t magic=0x4c4b4c32, nlimit=limit;
    if(writing_reference){
        stream.open(refpath,std::ios::binary|std::ios::out|std::ios::trunc);write(stream,magic);write(stream,nv);write(stream,nlimit);
        const std::string encoded=position_recipe.dump();uint32_t bytes=encoded.size();write(stream,bytes);stream.write(encoded.data(),bytes);
        reference_position_recipe=position_recipe;
        for(int i=0;i<limit+1;++i)write(stream,tokens[i]);
    }else{
        stream.open(refpath,std::ios::binary|std::ios::in);uint32_t m,v,l;read(stream,m);read(stream,v);read(stream,l);
        if(m!=magic||v!=nv||l!=nlimit)throw std::runtime_error("reference shape mismatch");
        uint32_t bytes;read(stream,bytes);if(bytes>65536)throw std::runtime_error("Invalid reference recipe length");
        std::string encoded(bytes,'\0');stream.read(encoded.data(),bytes);if(!stream)throw std::runtime_error("Reference recipe truncated");
        reference_position_recipe=json::parse(encoded);
        if(!compare_positioning&&reference_position_recipe!=position_recipe)throw std::runtime_error("Reference positional recipe mismatch");
        for(int i=0;i<limit+1;++i){llama_token t;read(stream,t);if(t!=tokens[i])throw std::runtime_error("source token mismatch");}
    }
    std::vector<float>baseline(nv);json observations=json::array();std::vector<uint8_t>snapshot;
    std::vector<float>snapshot_logits;
    bool state_ok=true;double candidate_nll=0,reference_nll=0,kl_total=0;int agreed=0,observed=0;
    auto score=[](const float *logits,uint32_t n,llama_token target){double max=*std::max_element(logits,logits+n),sum=0;for(uint32_t j=0;j<n;++j)sum+=std::exp(double(logits[j])-max);return max+std::log(sum)-logits[target];};
    for(int offset=0;offset<limit;offset+=32){
        auto batch=llama_batch_get_one(tokens.data()+offset,32);if(llama_decode(ctx,batch))throw std::runtime_error("decode failed");
        const int pos=offset+32;const float *logits=llama_get_logits_ith(ctx,-1);
        if(pos%128==0||pos==limit){
            if(writing_reference)stream.write(reinterpret_cast<const char*>(logits),nv*sizeof(float));else{stream.read(reinterpret_cast<char*>(baseline.data()),nv*sizeof(float));if(!stream)throw std::runtime_error("reference logits missing");}
            const float *ref=writing_reference?logits:baseline.data();
            double rn=score(ref,nv,tokens[pos]),cn=score(logits,nv,tokens[pos]);reference_nll+=rn;candidate_nll+=cn;
            const auto rtop=std::max_element(ref,ref+nv)-ref,ctop=std::max_element(logits,logits+nv)-logits;agreed+=rtop==ctop;++observed;
            double rmax=*std::max_element(ref,ref+nv),cmax=*std::max_element(logits,logits+nv),rz=0,cz=0;
            for(uint32_t j=0;j<nv;++j){rz+=std::exp(double(ref[j])-rmax);cz+=std::exp(double(logits[j])-cmax);}double rlz=rmax+std::log(rz),clz=cmax+std::log(cz),kl=0;
            for(uint32_t j=0;j<nv;++j)kl+=std::exp(double(ref[j])-rlz)*(double(ref[j])-rlz-double(logits[j])+clz);
            kl_total+=kl;observations.push_back({{"position",pos},{"nll",cn},{"reference_nll",rn},{"kl",kl},{"top1_same",rtop==ctop}});
        }
        // Save beyond the protected-ring boundary, advance, restore the whole
        // hybrid state, then replay the SAME continuation and compare logits.
        if(pos==384){size_t bytes=llama_state_seq_get_size(ctx,0);snapshot.resize(bytes);if(llama_state_seq_get_data(ctx,snapshot.data(),bytes,0)!=bytes)throw std::runtime_error("snapshot failed");}
        if(pos==416){
            snapshot_logits.assign(logits,logits+nv);llama_memory_clear(llama_get_memory(ctx),true);
            if(llama_state_seq_set_data(ctx,snapshot.data(),snapshot.size(),0)!=snapshot.size())throw std::runtime_error("restore failed");
            auto replay=llama_batch_get_one(tokens.data()+384,32);if(llama_decode(ctx,replay))throw std::runtime_error("replay failed");const auto *actual=llama_get_logits_ith(ctx,-1);
            double maxdiff=0;for(uint32_t j=0;j<nv;++j)maxdiff=std::max(maxdiff,std::abs(double(actual[j])-snapshot_logits[j]));state_ok=maxdiff<0.00001;
            if(!state_ok)throw std::runtime_error("hybrid snapshot changed replay logits: "+std::to_string(maxdiff));
            snapshot.clear();snapshot.shrink_to_fit();
        }
    }
    stream.close();llama_synchronize(ctx);double elapsed=std::chrono::duration<double>(std::chrono::steady_clock::now()-started).count();
    json report={{"profile",profile},{"backend",backend},{"model",modelpath},{"context",capacity},{"tokens",limit},{"observations",observations},{"mean_nll",candidate_nll/observed},{"reference_mean_nll",reference_nll/observed},{"nll_delta",(candidate_nll-reference_nll)/observed},{"mean_kl",kl_total/observed},{"top1_agreement",double(agreed)/observed},{"snapshot_replay_exact",state_ok},{"elapsed_seconds",elapsed}};
    report["calibration_model_sha256"]=calibration_model_sha;
    report["positioning"]=positioning;report["resolved_position_recipe"]=position_recipe;
    report["reference_position_recipe"]=reference_position_recipe;
    report["comparison_scope"]=compare_positioning?"joint positional/cache numerical comparison":"cache-only numerical comparison";
    // Engineering gates fixed before execution; per-case semantics are a
    // separate required check. They are not vendor quality guarantees.
    report["numerical_gate_passed"]=std::abs((candidate_nll-reference_nll)/observed)<=0.1&&kl_total/observed<=0.03&&double(agreed)/observed>=0.85&&state_ok;
    std::cout<<report.dump(2)<<'\n';llama_free(ctx);llama_model_free(model);return report["numerical_gate_passed"].get<bool>()?0:2;
}catch(const std::exception&e){std::cerr<<e.what()<<'\n';return 1;}}
