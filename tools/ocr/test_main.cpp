// Command line check of the OCR bridge, built for the phone's CPU and run
// on a PC under emulation (qemu):  pensyocr-test <tessdata dir> <lang> <image.pgm>
// Prints the same lines the app receives.

#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

bool pensy_ocr_init(const char* dir, const char* lang);
std::string pensy_ocr_recognize(const unsigned char* gray, int w, int h);

static bool read_pgm(const char* path, std::vector<unsigned char>& px, int& w, int& h) {
    FILE* f = fopen(path, "rb");
    if (!f) return false;
    int maxv = 0;
    if (fscanf(f, "P5 %d %d %d", &w, &h, &maxv) != 3 || maxv != 255) { fclose(f); return false; }
    fgetc(f);
    px.resize(static_cast<size_t>(w) * h);
    bool ok = fread(px.data(), 1, px.size(), f) == px.size();
    fclose(f);
    return ok;
}

int main(int argc, char** argv) {
    if (argc != 4) { fprintf(stderr, "usage: %s tessdata lang image.pgm\n", argv[0]); return 2; }
    std::vector<unsigned char> px;
    int w = 0, h = 0;
    if (!read_pgm(argv[3], px, w, h)) { fprintf(stderr, "bad image\n"); return 1; }
    if (!pensy_ocr_init(argv[1], argv[2])) { fprintf(stderr, "init failed\n"); return 1; }
    fputs(pensy_ocr_recognize(px.data(), w, h).c_str(), stdout);
    return 0;
}
