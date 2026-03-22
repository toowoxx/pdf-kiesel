#ifndef PDFGEN_H
#define PDFGEN_H

#include <stddef.h>

typedef struct {
    unsigned char *data;
    size_t len;
    char *error;
} PdfGenResult;

PdfGenResult pdfgen_generate(const char *json);

PdfGenResult pdfgen_generate_tree(const char *json);

void pdfgen_free(unsigned char *data, size_t len);

void pdfgen_free_error(char *error);

#endif
