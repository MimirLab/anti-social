#pragma once
#include <cstdint>
#include <string>
namespace antisocial_media {
void start(const std::string & job);
void cancel(const std::string & job);
void finish(const std::string & job);
void prepare_ocr(const std::string & directory, const std::string & language);
std::string ocr(const std::string & directory, const std::string & language, const uint8_t * pixels, int width, int height, int stride, int channels, const std::string & job);
void prepare_speech(const std::string & model, const std::string & vad, int threads);
std::string transcribe(const std::string & pcm, const std::string & job);
void release();
}
