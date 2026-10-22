// Tests actual QVAC CPU/Vulkan online stores and direct compressed attention.
// Independent F64 softmax checks the mixed exact/packed coordinate contract.
#include "llama.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "ggml-alloc.h"
#include "ggml-quants.h"
#include "nlohmann/json.hpp"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <iostream>
#include <random>
#include <stdexcept>
#include <vector>

using json=nlohmann::ordered_json;

static json run(ggml_backend_t backend, int length, int queries, ggml_type kt, ggml_type vt, int pattern) {
    constexpr int D=256, H=4, QH=12, slots=304;
    const float scale=1/std::sqrt(float(D));
    std::mt19937 rng(7319+length+queries+pattern);
    std::normal_distribution<float> normal(0,0.5f);
    std::vector<float> k(D*H*length),v(k.size()),q(D*QH*queries);
    for(auto &x:k)x=normal(rng);for(auto &x:v)x=normal(rng);for(auto &x:q)x=normal(rng);
    // Deliberately make exact values disagree with the compressed source.
    // A kernel that ignores the exceptions must fail the independent oracle.
    std::vector<float> ek(D*H*slots),ev(ek.size());
    for(auto &x:ek)x=normal(rng);for(auto &x:ev)x=normal(rng);
    std::vector<int32_t> map(length,-1);
    for(int t=0;t<length;++t) {
        if(pattern==1 || (pattern==0 && (t<16 || t>=std::max(16,length-288))))
            map[t]=t<16?t:16+(t-16)%288;
    }
    if(pattern==1 && length>slots)throw std::runtime_error("invalid all-exact fixture");
    std::vector<ggml_fp16_t> mask(length*queries);
    for(int b=0;b<queries;++b)for(int t=0;t<length;++t)
        mask[b*length+t]=ggml_fp32_to_fp16(t<=length-queries+b?0.f:-INFINITY);
    const size_t metadata=1024*ggml_tensor_overhead()+ggml_graph_overhead_custom(512,false);
    auto *ctx=ggml_init({metadata,nullptr,true});
    auto *ks=ggml_new_tensor_2d(ctx,kt,D*H,length);
    auto *vs=ggml_new_tensor_2d(ctx,vt,D*H,length);
    auto *ki=ggml_new_tensor_2d(ctx,GGML_TYPE_F32,D*H,length);
    auto *vi=ggml_new_tensor_2d(ctx,GGML_TYPE_F32,D*H,length);
    auto *ids=ggml_new_tensor_1d(ctx,GGML_TYPE_I64,length);
    auto *eks=ggml_new_tensor_2d(ctx,GGML_TYPE_F16,D*H,slots);
    auto *evs=ggml_new_tensor_2d(ctx,GGML_TYPE_F16,D*H,slots);
    auto *eki=ggml_new_tensor_2d(ctx,GGML_TYPE_F32,D*H,slots);
    auto *evi=ggml_new_tensor_2d(ctx,GGML_TYPE_F32,D*H,slots);
    auto *eids=ggml_new_tensor_1d(ctx,GGML_TYPE_I64,slots);
    auto *maps=ggml_new_tensor_1d(ctx,GGML_TYPE_I32,length);
    auto *qt=ggml_new_tensor_3d(ctx,GGML_TYPE_F32,D,queries,QH);
    auto *mt=ggml_new_tensor_2d(ctx,GGML_TYPE_F16,length,queries);
    auto *kv=ggml_view_4d(ctx,ks,D,length,H,1,ggml_row_size(kt,D*H),ggml_row_size(kt,D),ggml_nbytes(ks),0);
    auto *vv=ggml_view_4d(ctx,vs,D,length,H,1,ggml_row_size(vt,D*H),ggml_row_size(vt,D),ggml_nbytes(vs),0);
    auto *ekv=ggml_view_4d(ctx,eks,D,slots,H,1,D*H*2,D*2,ggml_nbytes(eks),0);
    auto *evv=ggml_view_4d(ctx,evs,D,slots,H,1,D*H*2,D*2,ggml_nbytes(evs),0);
    auto *out=ggml_flash_attn_ext(ctx,qt,kv,vv,mt,scale,0,0);
    ggml_flash_attn_ext_add_protected(out,ekv,evv,maps);
    ggml_prec_set_acc(out,GGML_PREC_F32);
    auto *graph=ggml_new_graph_custom(ctx,512,false);
    ggml_build_forward_expand(graph,ggml_set_rows(ctx,ks,ki,ids));
    ggml_build_forward_expand(graph,ggml_set_rows(ctx,vs,vi,ids));
    ggml_build_forward_expand(graph,ggml_set_rows(ctx,eks,eki,eids));
    ggml_build_forward_expand(graph,ggml_set_rows(ctx,evs,evi,eids));
    ggml_build_forward_expand(graph,out);
    if(!ggml_backend_supports_op(backend,out))throw std::runtime_error("backend rejects protected attention");
    auto *buf=ggml_backend_alloc_ctx_tensors(ctx,backend);
    if(!buf)throw std::runtime_error("allocation failed");
    ggml_backend_tensor_set(ki,k.data(),0,k.size()*4);ggml_backend_tensor_set(vi,v.data(),0,v.size()*4);
    ggml_backend_tensor_set(qt,q.data(),0,q.size()*4);ggml_backend_tensor_set(mt,mask.data(),0,mask.size()*2);
    std::vector<int64_t> indices(length);for(int t=0;t<length;++t)indices[t]=t;
    ggml_backend_tensor_set(ids,indices.data(),0,indices.size()*8);ggml_backend_tensor_set(maps,map.data(),0,map.size()*4);
    std::vector<ggml_fp16_t> ekh(ek.size()),evh(ev.size());
    ggml_fp32_to_fp16_row(ek.data(),ekh.data(),ek.size());ggml_fp32_to_fp16_row(ev.data(),evh.data(),ev.size());
    ggml_backend_tensor_set(eki,ek.data(),0,ek.size()*4);ggml_backend_tensor_set(evi,ev.data(),0,ev.size()*4);
    std::vector<int64_t> exact_indices(slots);for(int t=0;t<slots;++t)exact_indices[t]=t;
    ggml_backend_tensor_set(eids,exact_indices.data(),0,exact_indices.size()*8);
    if(ggml_backend_graph_compute(backend,graph)!=GGML_STATUS_SUCCESS)throw std::runtime_error("graph failed");
    std::vector<float> actual(ggml_nelements(out));ggml_backend_tensor_get(out,actual.data(),0,actual.size()*4);
    // Read the bytes actually stored by this backend; scalar reconstruction is
    // independent of the fused attention kernel and covers store parity too.
    std::vector<uint8_t> kb(ggml_nbytes(ks)),vb(ggml_nbytes(vs));
    ggml_backend_tensor_get(ks,kb.data(),0,kb.size());ggml_backend_tensor_get(vs,vb.data(),0,vb.size());
    std::vector<float> kd(D*H*length),vd(kd.size());
    const auto *ktr=ggml_get_type_traits(kt),*vtr=ggml_get_type_traits(vt);
    for(int t=0;t<length;++t){ktr->to_float(kb.data()+t*ggml_row_size(kt,D*H),kd.data()+t*D*H,D*H);vtr->to_float(vb.data()+t*ggml_row_size(vt,D*H),vd.data()+t*D*H,D*H);}
    // Do not let a broken all-zero writer validate itself through the decoded
    // attention oracle. Independently quantize source rows on CPU as well.
    std::vector<uint8_t> reference_k(kb.size()),reference_v(vb.size());
    ktr->from_float_ref(k.data(),reference_k.data(),k.size());vtr->from_float_ref(v.data(),reference_v.data(),v.size());
    std::vector<float> reference_kd(kd.size()),reference_vd(vd.size());
    ktr->to_float(reference_k.data(),reference_kd.data(),reference_kd.size());vtr->to_float(reference_v.data(),reference_vd.data(),reference_vd.size());
    double store_error2=0,store_reference2=0;
    for(size_t i=0;i<kd.size();++i){double e=kd[i]-reference_kd[i];store_error2+=e*e;store_reference2+=double(reference_kd[i])*reference_kd[i];e=vd[i]-reference_vd[i];store_error2+=e*e;store_reference2+=double(reference_vd[i])*reference_vd[i];}
    const double store_relative=std::sqrt(store_error2/std::max(store_reference2,1e-20));
    std::vector<ggml_fp16_t> stored_ek(ekh.size()),stored_ev(evh.size());
    ggml_backend_tensor_get(eks,stored_ek.data(),0,stored_ek.size()*2);ggml_backend_tensor_get(evs,stored_ev.data(),0,stored_ev.size()*2);
    const bool exact_store_ok=stored_ek==ekh&&stored_ev==evh;
    double error2=0,reference2=0,maxerror=0;
    for(int b=0;b<queries;++b)for(int h=0;h<QH;++h) {
        const int kh=h/(QH/H);std::vector<double> scores(length,-INFINITY);double maximum=-INFINITY;
        for(int t=0;t<=length-queries+b;++t){
            double score=0;for(int d=0;d<D;++d){float key=map[t]>=0?ggml_fp16_to_fp32(ekh[(map[t]*H+kh)*D+d]):kd[(t*H+kh)*D+d];score+=q[(h*queries+b)*D+d]*key;}
            scores[t]=score*scale;maximum=std::max(maximum,scores[t]);
        }
        std::vector<double> sum(D,0);double norm=0;
        for(int t=0;t<=length-queries+b;++t){double weight=std::exp(scores[t]-maximum);norm+=weight;for(int d=0;d<D;++d){float val=map[t]>=0?ggml_fp16_to_fp32(evh[(map[t]*H+kh)*D+d]):vd[(t*H+kh)*D+d];sum[d]+=weight*val;}}
        for(int d=0;d<D;++d){double ref=sum[d]/norm,got=actual[(b*QH+h)*D+d],e=got-ref;if(!std::isfinite(got))throw std::runtime_error("nonfinite attention");error2+=e*e;reference2+=ref*ref;maxerror=std::max(maxerror,std::abs(e));}
    }
    double relative=std::sqrt(error2/std::max(reference2,1e-20));
    // CPU compressed K uses its existing Q8 query dot. Its expected rounding is
    // distinct from the exact-row F32 dot and the GPU shader's FP32 dot.
    const bool passed=relative<0.012 && maxerror<0.006 && store_relative<0.005 && exact_store_ok;
    json result={{"length",length},{"queries",queries},{"dimension",D},{"kv_heads",H},{"query_heads",QH},{"k",ggml_type_name(kt)},{"v",ggml_type_name(vt)},{"pattern",pattern},{"relative_l2",relative},{"max_absolute",maxerror},{"store_relative_l2",store_relative},{"exact_store_parity",exact_store_ok},{"packed_bytes",kb.size()+vb.size()},{"exact_bytes",(ekh.size()+evh.size())*2},{"map_bytes",map.size()*4},{"passed",passed}};
    ggml_backend_buffer_free(buf);ggml_free(ctx);return result;
}
int main(int argc,char**argv){try{
    ggml_backend_load_all();llama_backend_init();const bool gpu=argc>1&&std::string(argv[1])=="vulkan";
    ggml_backend_dev_t dev=nullptr;
    for(size_t i=0;i<ggml_backend_dev_count();++i){auto d=ggml_backend_dev_get(i);std::string reg=ggml_backend_reg_name(ggml_backend_dev_backend_reg(d));if((gpu&&reg=="Vulkan")||(!gpu&&ggml_backend_dev_type(d)==GGML_BACKEND_DEVICE_TYPE_CPU)){dev=d;break;}}
    if(!dev)throw std::runtime_error("requested backend absent");auto *backend=ggml_backend_dev_init(dev,nullptr);
    if(!backend)throw std::runtime_error("backend init failed");if(!gpu)ggml_backend_cpu_set_n_threads(backend,4);
    json results=json::array();bool passed=true;
    for(auto kt:{GGML_TYPE_PQ4_0,GGML_TYPE_PQ3_0})for(auto vt:{GGML_TYPE_PQ4_0,GGML_TYPE_PQ3_0})for(auto length:{128,256,384,512,1024})for(auto queries:{1,16,32})for(auto pattern:{0,2}){auto r=run(backend,length,queries,kt,vt,pattern);passed=passed&&r["passed"].get<bool>();results.push_back(r);}
    for(auto length:{128,256}){auto r=run(backend,length,32,GGML_TYPE_PQ4_0,GGML_TYPE_PQ4_0,1);passed=passed&&r["passed"].get<bool>();results.push_back(r);}
    std::cout<<json({{"device",ggml_backend_dev_name(dev)},{"backend",gpu?"vulkan":"cpu"},{"cases",results},{"passed",passed}}).dump(2)<<'\n';ggml_backend_free(backend);return passed?0:2;
}catch(const std::exception&e){std::cerr<<e.what()<<'\n';return 1;}}
