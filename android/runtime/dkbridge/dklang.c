/*
 * dklang - traduction des textes du moteur (partie de libdkbridge.so, charge via LD_PRELOAD).
 *
 * Le moteur arm64 du port integre l'anglais (GetLangAPI statique) et ne charge pas de module
 * de langue. Avant main(), on remplace donc les textes de ses tableaux tongue_* par leur
 * traduction, deduite des modules officiels du patch 1.3 (language_english.so et
 * language_<langue>.so, Linux x86_64) : meme version, memes tableaux, memes indices, donc
 * texte anglais N -> texte traduit N. Les modules ne sont lus que comme des donnees.
 *
 * Le menu principal du moteur utilise en plus des libelles ecrits en dur, absents des
 * modules : ils sont traduits par la table MENU_LABELS ci-dessous (traduction du projet) en
 * remplacant, apres chaque construction du menu, les pointeurs qui les designent en memoire.
 *
 * Variables d'environnement : DK_LANGUAGE (french, german, italian, spanish) et
 * DK_LANG_DIR (dossier contenant les deux modules).
 */
#define _GNU_SOURCE
#include <elf.h>
#include <link.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#ifndef R_X86_64_64
#define R_X86_64_64 1
#endif
#ifndef R_X86_64_RELATIVE
#define R_X86_64_RELATIVE 8
#endif
#ifndef R_AARCH64_ABS64
#define R_AARCH64_ABS64 257
#endif
#ifndef R_AARCH64_RELATIVE
#define R_AARCH64_RELATIVE 1027
#endif

/* ---- Lecture d'un fichier ELF64 (module x86_64 ou moteur aarch64) ---- */

typedef struct {
    uint64_t offset;
    uint64_t target;
} reloc_t;

typedef struct {
    unsigned char *data;
    size_t size;
    const Elf64_Ehdr *eh;
    const Elf64_Sym *dynsym;
    size_t nsyms;
    const char *dynstr;
    reloc_t *relocs;
    size_t nrelocs;
} elf_t;

static int load_file(const char *path, elf_t *e)
{
    FILE *f = fopen(path, "rb");
    long size;
    memset(e, 0, sizeof(*e));
    if (!f) return -1;
    fseek(f, 0, SEEK_END);
    size = ftell(f);
    fseek(f, 0, SEEK_SET);
    e->data = malloc((size_t)size);
    if (!e->data || fread(e->data, 1, (size_t)size, f) != (size_t)size) {
        fclose(f);
        return -1;
    }
    fclose(f);
    e->size = (size_t)size;
    e->eh = (const Elf64_Ehdr *)e->data;
    if (size < (long)sizeof(Elf64_Ehdr) || memcmp(e->eh->e_ident, ELFMAG, SELFMAG) != 0 ||
        e->eh->e_ident[EI_CLASS] != ELFCLASS64) return -1;
    return 0;
}

static const Elf64_Shdr *section(const elf_t *e, int i)
{
    return (const Elf64_Shdr *)(e->data + e->eh->e_shoff + (size_t)i * e->eh->e_shentsize);
}

/* Adresse virtuelle -> pointeur dans le fichier (segments PT_LOAD). */
static const void *at_vaddr(const elf_t *e, uint64_t va)
{
    for (int i = 0; i < e->eh->e_phnum; i++) {
        const Elf64_Phdr *ph = (const Elf64_Phdr *)(e->data + e->eh->e_phoff + (size_t)i * e->eh->e_phentsize);
        if (ph->p_type == PT_LOAD && va >= ph->p_vaddr && va < ph->p_vaddr + ph->p_filesz) {
            return e->data + ph->p_offset + (va - ph->p_vaddr);
        }
    }
    return NULL;
}

static int cmp_reloc(const void *a, const void *b)
{
    uint64_t x = ((const reloc_t *)a)->offset, y = ((const reloc_t *)b)->offset;
    return x < y ? -1 : x > y;
}

static int parse(elf_t *e)
{
    size_t cap = 0;
    for (int i = 0; i < e->eh->e_shnum; i++) {
        const Elf64_Shdr *sh = section(e, i);
        if (sh->sh_type == SHT_DYNSYM) {
            e->dynsym = (const Elf64_Sym *)(e->data + sh->sh_offset);
            e->nsyms = sh->sh_size / sizeof(Elf64_Sym);
            e->dynstr = (const char *)(e->data + section(e, (int)sh->sh_link)->sh_offset);
        }
    }
    if (!e->dynsym) return -1;

    for (int i = 0; i < e->eh->e_shnum; i++) {
        const Elf64_Shdr *sh = section(e, i);
        const Elf64_Rela *r;
        size_t n;
        if (sh->sh_type != SHT_RELA) continue;
        r = (const Elf64_Rela *)(e->data + sh->sh_offset);
        n = sh->sh_size / sizeof(Elf64_Rela);
        for (size_t k = 0; k < n; k++) {
            uint32_t type = ELF64_R_TYPE(r[k].r_info);
            uint64_t target;
            /* modules x86_64 et moteur aarch64 */
            if (type == R_X86_64_RELATIVE || type == R_AARCH64_RELATIVE) target = (uint64_t)r[k].r_addend;
            else if (type == R_X86_64_64 || type == R_AARCH64_ABS64) target = e->dynsym[ELF64_R_SYM(r[k].r_info)].st_value + (uint64_t)r[k].r_addend;
            else continue;
            if (e->nrelocs == cap) {
                cap = cap ? cap * 2 : 1024;
                e->relocs = realloc(e->relocs, cap * sizeof(reloc_t));
            }
            e->relocs[e->nrelocs].offset = r[k].r_offset;
            e->relocs[e->nrelocs].target = target;
            e->nrelocs++;
        }
    }
    qsort(e->relocs, e->nrelocs, sizeof(reloc_t), cmp_reloc);
    return 0;
}

/* Texte pointe par l'emplacement va (pointeur reloge), ou NULL. */
static const char *string_at(const elf_t *e, uint64_t va)
{
    reloc_t key = { va, 0 };
    reloc_t *r = bsearch(&key, e->relocs, e->nrelocs, sizeof(reloc_t), cmp_reloc);
    return r ? (const char *)at_vaddr(e, r->target) : NULL;
}

static const Elf64_Sym *find_symbol(const elf_t *e, const char *name)
{
    for (size_t i = 0; i < e->nsyms; i++) {
        if (e->dynsym[i].st_name && strcmp(e->dynstr + e->dynsym[i].st_name, name) == 0) return &e->dynsym[i];
    }
    return NULL;
}

static int is_text_table(const elf_t *e, const Elf64_Sym *s)
{
    const char *name = e->dynstr + s->st_name;
    return ELF64_ST_TYPE(s->st_info) == STT_OBJECT && s->st_size > 0 &&
           (strncmp(name, "tongue", 6) == 0 || strcmp(name, "texture_quality_desc") == 0);
}

/* ---- Dictionnaire anglais -> traduction ---- */

typedef struct {
    const char *en;
    const char *tr;
} entry_t;

static entry_t *dict;
static size_t ndict;

static int cmp_entry(const void *a, const void *b)
{
    return strcmp(((const entry_t *)a)->en, ((const entry_t *)b)->en);
}

static void build_dict(const elf_t *en, const elf_t *tr)
{
    size_t cap = 0;
    for (size_t i = 0; i < en->nsyms; i++) {
        const Elf64_Sym *s = &en->dynsym[i];
        const Elf64_Sym *t;
        if (!is_text_table(en, s)) continue;
        t = find_symbol(tr, en->dynstr + s->st_name);
        if (!t || t->st_size != s->st_size) continue;
        for (uint64_t off = 0; off + 8 <= s->st_size; off += 8) {
            const char *a = string_at(en, s->st_value + off);
            const char *b = string_at(tr, t->st_value + off);
            if (!a || !b || !*a) continue;
            if (ndict == cap) {
                cap = cap ? cap * 2 : 1024;
                dict = realloc(dict, cap * sizeof(entry_t));
            }
            dict[ndict].en = a;
            dict[ndict].tr = b;
            ndict++;
        }
    }
    qsort(dict, ndict, sizeof(entry_t), cmp_entry);
}

static const char *translate(const char *s)
{
    entry_t key = { s, NULL };
    entry_t *r = bsearch(&key, dict, ndict, sizeof(entry_t), cmp_entry);
    return r ? r->tr : NULL;
}

/* ---- Application au moteur en memoire ---- */

static void prepare_menu_labels(const elf_t *exe, const char *lang);

static uintptr_t exe_base;
static int find_exe(struct dl_phdr_info *info, size_t size, void *data)
{
    (void)size;
    (void)data;
    exe_base = info->dlpi_addr; /* premier objet = programme principal */
    return 1;
}

static void make_writable(void *addr, size_t len)
{
    long page = sysconf(_SC_PAGESIZE);
    uintptr_t start = (uintptr_t)addr & ~(uintptr_t)(page - 1);
    uintptr_t end = ((uintptr_t)addr + len + (uintptr_t)page - 1) & ~(uintptr_t)(page - 1);
    mprotect((void *)start, end - start, PROT_READ | PROT_WRITE);
}

static int translate_engine(const char *lang)
{
    elf_t exe;
    int replaced = 0, total = 0;
    if (load_file("/proc/self/exe", &exe) != 0 || parse(&exe) != 0) return -1;
    dl_iterate_phdr(find_exe, NULL);

    for (size_t i = 0; i < exe.nsyms; i++) {
        const Elf64_Sym *s = &exe.dynsym[i];
        const char **table;
        if (!is_text_table(&exe, s) || s->st_shndx == SHN_UNDEF) continue;
        table = (const char **)(exe_base + s->st_value);
        make_writable(table, s->st_size);
        for (uint64_t k = 0; k < s->st_size / 8; k++) {
            const char *tr;
            /* Les pointeurs sont des textes du moteur : on ne suit que ceux qu'il reloge. */
            if (!string_at(&exe, s->st_value + k * 8) || !table[k]) continue;
            total++;
            tr = translate(table[k]);
            if (tr) {
                table[k] = strdup(tr);
                replaced++;
            }
        }
    }
    fprintf(stderr, "dklang: %d/%d textes traduits\n", replaced, total);
    prepare_menu_labels(&exe, lang);
    free(exe.data);
    free(exe.relocs);
    return 0;
}

/* ---- Libelles du menu principal (en dur dans le moteur) ---- */

#define NLABELS 8
static const char *const MENU_EN[NLABELS] = {
    "Main Menu", "New Game", "Settings", "Save Game", "Load Game", "Quit", "Start", "Back",
};
static const struct {
    const char *lang;
    const char *labels[NLABELS];
} MENU_LABELS[] = { /* textes en Latin-1 (police du jeu) */
    { "french",  { "Menu principal", "Nouvelle partie", "Options", "Sauvegarder", "Charger", "Quitter",
                   "Commencer", "Retour" } },
    { "german",  { "Hauptmenü", "Neues Spiel", "Einstellungen", "Speichern", "Laden", "Beenden",
                   "Starten", "Zurück" } },
    { "italian", { "Menu principale", "Nuova partita", "Impostazioni", "Salva", "Carica", "Esci",
                   "Inizia", "Indietro" } },
    { "spanish", { "Menú principal", "Nueva partida", "Opciones", "Guardar", "Cargar", "Salir",
                   "Comenzar", "Atrás" } },
};

/* Adresses du libelle anglais dans le moteur : un libelle court peut etre la fin d'une
 * chaine plus longue ("Back" dans "Move For./Back") et exister en plusieurs exemplaires. */
#define MAX_COPIES 4
static uintptr_t label_en[NLABELS][MAX_COPIES];
static const char *label_tr[NLABELS];
static uintptr_t data_start, data_end;  /* .data + .bss du moteur */
#define MAX_SLOTS 64
static const char **slots[MAX_SLOTS];   /* emplacements memoire pointant un libelle */
static int nslots, slot_label[MAX_SLOTS];

/* Repere les libelles du menu et la zone de donnees du moteur (appele par translate_engine). */
static void prepare_menu_labels(const elf_t *exe, const char *lang)
{
    int l;
    for (l = 0; l < (int)(sizeof(MENU_LABELS) / sizeof(MENU_LABELS[0])); l++) {
        if (strcmp(MENU_LABELS[l].lang, lang) == 0) break;
    }
    if (l == (int)(sizeof(MENU_LABELS) / sizeof(MENU_LABELS[0]))) return;

    for (int i = 0; i < exe->eh->e_shnum; i++) {
        const Elf64_Shdr *sh = section(exe, i);
        const char *name = (const char *)(exe->data + section(exe, exe->eh->e_shstrndx)->sh_offset + sh->sh_name);
        if (strcmp(name, ".rodata") == 0) {
            /* libelle = chaine terminee par NUL, eventuellement fin d'une chaine plus longue */
            const char *ro = (const char *)(exe->data + sh->sh_offset);
            for (int k = 0; k < NLABELS; k++) {
                size_t len = strlen(MENU_EN[k]) + 1;
                int n = 0;
                label_tr[k] = MENU_LABELS[l].labels[k];
                for (size_t o = 0; o + len <= sh->sh_size && n < MAX_COPIES; o++) {
                    if (memcmp(ro + o, MENU_EN[k], len) == 0) label_en[k][n++] = exe_base + sh->sh_addr + o;
                }
            }
        } else if (strcmp(name, ".data") == 0) {
            data_start = exe_base + sh->sh_addr;
        } else if (strcmp(name, ".bss") == 0) {
            data_end = exe_base + sh->sh_addr + sh->sh_size;
        }
    }
}

/* Remplace les pointeurs vers les libelles anglais du menu. Appele apres chaque image. */
void dklang_frame(void)
{
    static int frame;
    if (!data_start || !data_end) return;

    for (int i = 0; i < nslots; i++) {
        for (int c = 0; c < MAX_COPIES; c++) {
            if (label_en[slot_label[i]][c] && (uintptr_t)*slots[i] == label_en[slot_label[i]][c]) {
                *slots[i] = label_tr[slot_label[i]];
            }
        }
    }
    /* Recherche des emplacements : quelques fois au demarrage, puis toutes les ~10 s */
    if (frame++ % (nslots < NLABELS ? 60 : 600) != 0) return;
    for (uintptr_t p = data_start; p + 8 <= data_end; p += 8) {
        uintptr_t v = *(const uintptr_t *)p;
        for (int k = 0; k < NLABELS; k++) {
            for (int c = 0; c < MAX_COPIES; c++) {
                if (!label_en[k][c] || v != label_en[k][c]) continue;
                if (nslots < MAX_SLOTS) {
                    slots[nslots] = (const char **)p;
                    slot_label[nslots++] = k;
                }
                *(const char **)p = label_tr[k];
            }
        }
    }
}

__attribute__((constructor)) static void dklang_init(void)
{
    const char *lang = getenv("DK_LANGUAGE");
    const char *dir = getenv("DK_LANG_DIR");
    char path[4096];
    elf_t en, tr;

    if (!lang || !dir || strcmp(lang, "english") == 0) return;
    if (readlink("/proc/self/exe", path, sizeof(path) - 1) <= 0 || !strstr(path, "daikatana")) return;

    snprintf(path, sizeof(path), "%s/language_english.so", dir);
    if (load_file(path, &en) != 0 || parse(&en) != 0) {
        fprintf(stderr, "dklang: module illisible : %s\n", path);
        return;
    }
    snprintf(path, sizeof(path), "%s/language_%s.so", dir, lang);
    if (load_file(path, &tr) != 0 || parse(&tr) != 0) {
        fprintf(stderr, "dklang: module illisible : %s\n", path);
        return;
    }
    build_dict(&en, &tr);
    translate_engine(lang);
    /* en/tr restent en memoire : le dictionnaire pointe dans leurs donnees (copiees par strdup). */
}
