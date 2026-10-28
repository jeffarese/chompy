// Write a Ghostty snapshot of a terminal fed a VT file, as a Rex server writes at the start of a
// stream. Usage: encode <in.vt> <out.snap> <columns> <rows> <scrollback lines>
#include <ghostty/vt.h>
#include <stdio.h>
#include <stdlib.h>

int main(int argc, char** argv) {
  if (argc != 6) return 2;
  FILE* f = fopen(argv[1], "rb");
  fseek(f, 0, SEEK_END);
  long len = ftell(f);
  fseek(f, 0, SEEK_SET);
  uint8_t* vt = malloc(len);
  fread(vt, 1, len, f);
  fclose(f);

  GhosttyTerminal t = NULL;
  if (ghostty_terminal_new(NULL, &t, (uint16_t)atoi(argv[3]), (uint16_t)atoi(argv[4])) != GHOSTTY_SUCCESS) return 1;
  size_t lines = (size_t)atol(argv[5]);
  ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_LINES, &lines);
  ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES, NULL);
  ghostty_terminal_vt_write(t, vt, (size_t)len);

  uint8_t* out = NULL;
  size_t out_len = 0;
  if (ghostty_snapshot_encode_alloc(t, NULL, &out, &out_len) != GHOSTTY_SUCCESS) return 1;
  FILE* o = fopen(argv[2], "wb");
  fwrite(out, 1, out_len, o);
  fclose(o);
  printf("%zu bytes\n", out_len);
  return 0;
}
