// PENSY OCR bridge: Tesseract behind two JNI calls.
//
//   Tess.init(dir, lang)            load the models (dir holds <lang>.traineddata; "ita+eng" for two)
//   Tess.recognize(gray, w, h)      8-bit grayscale pixels -> text lines with boxes
//
// Each output line is "left\ttop\tright\tbottom\tconfidence\ttext\n", UTF-8.
// The Kotlin side turns the boxes back into reading order (core/Layout.kt).
// Everything runs on the phone: no network, no Google services.

#include <jni.h>

#include <memory>
#include <mutex>
#include <string>

#include <leptonica/allheaders.h>
#include <tesseract/baseapi.h>
#include <tesseract/resultiterator.h>

namespace {

std::mutex g_lock;
tesseract::TessBaseAPI* g_api = nullptr;
std::string g_lang;

std::string clean(const char* s) {
    std::string out(s);
    for (char& c : out)
        if (c == '\t' || c == '\n' || c == '\r') c = ' ';
    size_t a = out.find_first_not_of(' ');
    if (a == std::string::npos) return "";
    size_t b = out.find_last_not_of(' ');
    return out.substr(a, b - a + 1);
}

}  // namespace

// Shared by the JNI entry points and by the command line test (test_main.cpp).
// The same language again is free; another one ("ita" -> "ita+eng") replaces the engine.
bool pensy_ocr_init(const char* dir, const char* lang) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_api && g_lang == lang) return true;
    if (g_api) {
        g_api->End();
        delete g_api;
        g_api = nullptr;
        g_lang.clear();
    }
    setMsgSeverity(L_SEVERITY_NONE);  // Leptonica: no chatter on stderr
    auto* api = new tesseract::TessBaseAPI();
    if (api->Init(dir, lang, tesseract::OEM_LSTM_ONLY) != 0) {
        delete api;
        return false;
    }
    g_lang = lang;
    api->SetPageSegMode(tesseract::PSM_AUTO);
    // Photos have uneven light: local (Sauvola) thresholding instead of a global one.
    api->SetVariable("thresholding_method", "2");
    api->SetVariable("user_defined_dpi", "300");
    api->SetVariable("debug_file", "/dev/null");
    g_api = api;
    return true;
}

std::string pensy_ocr_recognize(const unsigned char* gray, int w, int h) {
    std::lock_guard<std::mutex> guard(g_lock);
    std::string out;
    if (!g_api || w <= 0 || h <= 0) return out;
    g_api->SetImage(gray, w, h, 1, w);
    if (g_api->Recognize(nullptr) != 0) {
        g_api->Clear();
        return out;
    }
    std::unique_ptr<tesseract::ResultIterator> it(g_api->GetIterator());
    const auto level = tesseract::RIL_TEXTLINE;
    if (it && !it->Empty(level)) {
        do {
            std::unique_ptr<char[]> txt(it->GetUTF8Text(level));
            if (!txt) continue;
            int l, t, r, b;
            if (!it->BoundingBox(level, &l, &t, &r, &b)) continue;
            std::string s = clean(txt.get());
            if (s.empty()) continue;
            int conf = static_cast<int>(it->Confidence(level));
            out += std::to_string(l) + '\t' + std::to_string(t) + '\t' + std::to_string(r) + '\t' +
                   std::to_string(b) + '\t' + std::to_string(conf) + '\t' + s + '\n';
        } while (it->Next(level));
    }
    g_api->Clear();
    return out;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_pensy_ocr_Tess_init(JNIEnv* env, jclass, jstring jdir, jstring jlang) {
    const char* dir = env->GetStringUTFChars(jdir, nullptr);
    const char* lang = env->GetStringUTFChars(jlang, nullptr);
    bool ok = pensy_ocr_init(dir, lang);
    env->ReleaseStringUTFChars(jdir, dir);
    env->ReleaseStringUTFChars(jlang, lang);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_pensy_ocr_Tess_recognize(JNIEnv* env, jclass, jbyteArray jgray, jint w, jint h) {
    std::string out;
    if (env->GetArrayLength(jgray) >= static_cast<jsize>(w) * h) {
        jbyte* px = env->GetByteArrayElements(jgray, nullptr);
        out = pensy_ocr_recognize(reinterpret_cast<const unsigned char*>(px), w, h);
        env->ReleaseByteArrayElements(jgray, px, JNI_ABORT);
    }
    // Bytes, not NewStringUTF: Java's "modified UTF-8" would choke on some characters.
    jbyteArray res = env->NewByteArray(static_cast<jsize>(out.size()));
    if (res && !out.empty())
        env->SetByteArrayRegion(res, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte*>(out.data()));
    return res;
}
