#include "llama-kv-basis.h"
#include <iostream>
#include <random>
#include <numeric>
int main(){try{
    std::mt19937 random(0x4c4b5632);std::normal_distribution<float>normal(0,1);double max_score_error=0,max_value_error=0;int cases=0;
    for(auto kind:{llama_kv_rotation::pq_hadamard,llama_kv_rotation::signed_hadamard,llama_kv_rotation::planar,llama_kv_rotation::iso})for(auto n:{64u,128u,256u}){
        auto r=llama_kv_make_rotation(n,kind,0x4c49424552ULL),inverse=llama_kv_inverse_rotation(r,n);
        for(int sample=0;sample<100;++sample){std::vector<float>q(n),k(n),v(n),d(n);for(unsigned j=0;j<n;++j){q[j]=normal(random);k[j]=normal(random);v[j]=normal(random);d[j]=std::exp(std::clamp(normal(random),-2.f,2.f));}
            auto rq=llama_kv_apply_rotation(r,llama_kv_precondition(q,d,true)),rk=llama_kv_apply_rotation(r,llama_kv_precondition(k,d,false));
            double expected=std::inner_product(q.begin(),q.end(),k.begin(),0.0),actual=std::inner_product(rq.begin(),rq.end(),rk.begin(),0.0);
            double error=std::abs(actual-expected)/std::max(1.0,std::sqrt(double(n)));max_score_error=std::max(max_score_error,error);
            auto stored=llama_kv_apply_rotation(r,llama_kv_precondition(v,d,false));auto reconstructed=llama_kv_apply_rotation(inverse,stored);
            for(unsigned j=0;j<n;++j){reconstructed[j]*=d[j];max_value_error=std::max(max_value_error,std::abs(double(reconstructed[j])-v[j]));}
            if(error>0.00002||max_value_error>0.00004)throw std::runtime_error("cache coordinate identity failed");++cases;
        }
        if(kind==llama_kv_rotation::planar||kind==llama_kv_rotation::iso){
            std::vector<float>v(n,0);v[0]=1;auto wrong=llama_kv_apply_rotation(r,llama_kv_apply_rotation(r,v));double err=0;for(unsigned j=0;j<n;++j)err+=std::abs(wrong[j]-v[j]);if(err<0.1)throw std::runtime_error("fixture failed to distinguish inverse V from a second forward rotation");
        }
    }
    bool rejected=false;try{llama_kv_precondition({1.f},{0.f},true);}catch(const std::exception&){rejected=true;}if(!rejected)throw std::runtime_error("invalid calibration accepted");
    std::cout<<"{\"cases\":"<<cases<<",\"maximum_normalized_score_error\":"<<max_score_error<<",\"maximum_value_error\":"<<max_value_error<<",\"passed\":true}\n";return 0;
}catch(const std::exception&e){std::cerr<<e.what()<<'\n';return 1;}}
