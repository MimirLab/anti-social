#include "context_media.h"
#include <chrono>
#include <fstream>
#include <iostream>
#include <thread>
#include <vector>
int main(int argc,char ** argv){try{
    if(argc<4)return 2;std::string mode=argv[1];antisocial_media::start("reference");
    if(mode=="ocr"){
        std::ifstream input(argv[3],std::ios::binary);std::string magic;int w=0,h=0,max=0;input>>magic>>w>>h>>max;input.get();if(magic!="P5"||max!=255||w<=0||h<=0||int64_t(w)*h>12000000)return 3;
        std::vector<uint8_t> pixels(size_t(w)*h);input.read(reinterpret_cast<char *>(pixels.data()),pixels.size());if(!input)return 4;
        std::cout<<antisocial_media::ocr(argv[2],argc>4?argv[4]:"eng",pixels.data(),w,h,w,1,"reference")<<'\n';
    }else{
        if(argc<5)return 2;antisocial_media::prepare_speech(argv[2],argv[3],2);
        if(mode=="cancel") {
            auto started=std::chrono::steady_clock::now();bool cancelled=false;
            std::thread stop([] {std::this_thread::sleep_for(std::chrono::milliseconds(100));antisocial_media::cancel("reference");});
            try{antisocial_media::transcribe(argv[4],"reference");}catch(const std::exception & e){cancelled=std::string(e.what()).find("cancel")!=std::string::npos;}
            stop.join();antisocial_media::finish("reference");if(!cancelled)return 5;
            if(argc>5){antisocial_media::start("reference");std::cout<<antisocial_media::transcribe(argv[5],"reference")<<'\n';}
            auto elapsed=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-started).count();std::cout<<"{\"cancelled\":true,\"cancel_and_recovered_ms\":"<<elapsed<<"}\n";
        }else std::cout<<antisocial_media::transcribe(argv[4],"reference")<<'\n';
    }
    antisocial_media::finish("reference");antisocial_media::release();return 0;
}catch(const std::exception & e){std::cerr<<e.what()<<'\n';return 1;}}
