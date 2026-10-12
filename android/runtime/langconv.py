"""Convertit un module de langue Daikatana 1.3 Linux x86_64 (language_<langue>.so) en
source assembleur aarch64, pour le moteur arm64 du port.

Ces modules ne contiennent que des donnees (tableaux de textes) et GetLangAPI(), qui
remplit la structure `lang` (language_export_t) et renvoie son adresse. x86_64 et aarch64
etant tous deux LP64 little-endian, les donnees ont la meme disposition : on les recopie
octet par octet en reecrivant les pointeurs (relocations), et on regenere `lang` d'apres
le code de GetLangAPI.

Usage : python -I langconv.py language_french.so language_french.s
        puis (arm64) : gcc -shared -nostdlib -o language_french.so language_french.s
"""
import struct
import sys

from elftools.elf.elffile import ELFFile
from elftools.elf.relocation import RelocationSection

R_X86_64_64 = 1
R_X86_64_RELATIVE = 8
DATA_SECTIONS = (".rodata", ".data.rel.ro", ".data", ".bss")


def decode_getlangapi(code, func_addr, lang_addr):
    """Decode la suite lea/mov de GetLangAPI : renvoie (version, {offset_champ: adresse})."""
    fields = {}
    version = None
    pc = 0
    pending = None
    while pc < len(code):
        b = code[pc:]
        if b[:3] == b"\x48\x8d\x05":  # lea rax, [rip+disp32]
            target = func_addr + pc + 7 + struct.unpack("<i", b[3:7])[0]
            assert target == lang_addr, "GetLangAPI ne renvoie pas &lang"
            pc += 7
        elif b[:3] == b"\x48\x8d\x15":  # lea rdx, [rip+disp32]
            pending = func_addr + pc + 7 + struct.unpack("<i", b[3:7])[0]
            pc += 7
        elif b[:3] == b"\x48\x89\x50":  # mov [rax+disp8], rdx
            fields[b[3]] = pending
            pc += 4
        elif b[:3] == b"\x48\x89\x90":  # mov [rax+disp32], rdx
            fields[struct.unpack("<i", b[3:7])[0]] = pending
            pc += 7
        elif b[:2] == b"\xc7\x00":  # mov dword [rax], imm32
            version = struct.unpack("<i", b[2:6])[0]
            pc += 6
        elif b[:1] == b"\xc3":  # ret
            break
        else:
            raise ValueError(f"instruction inattendue a +{pc:#x} : {b[:8].hex()}")
    return version, fields


def main(src, out):
    elf = ELFFile(open(src, "rb"))
    sections = [s for s in elf.iter_sections() if s.name in DATA_SECTIONS]
    symtab = elf.get_section_by_name(".symtab") or elf.get_section_by_name(".dynsym")
    symbols = {s.name: s for s in symtab.iter_symbols()}

    def label_for(addr):
        for s in sections:
            if s["sh_addr"] <= addr < s["sh_addr"] + max(s["sh_size"], 1):
                return f"S{s.name.replace('.', '_')}+{addr - s['sh_addr']}"
        raise ValueError(f"adresse hors des sections de donnees : {addr:#x}")

    def in_data(addr):
        return any(s["sh_addr"] <= addr < s["sh_addr"] + s["sh_size"] for s in sections)

    # Relocations : adresse -> expression assembleur du pointeur
    pointers = {}
    for rel_section in elf.iter_sections():
        if not isinstance(rel_section, RelocationSection):
            continue
        dynsym = elf.get_section(rel_section["sh_link"])
        for rel in rel_section.iter_relocations():
            if not in_data(rel["r_offset"]):
                continue  # .init_array, .got... : code d'initialisation, sans interet ici
            kind = rel["r_info_type"]
            if kind == R_X86_64_RELATIVE:
                pointers[rel["r_offset"]] = label_for(rel["r_addend"])
            elif kind == R_X86_64_64:
                sym = dynsym.get_symbol(rel["r_info_sym"])
                pointers[rel["r_offset"]] = label_for(sym["st_value"] + rel["r_addend"])

    lang = symbols["lang"]
    api = symbols["GetLangAPI"]
    text = next(s for s in elf.iter_sections()
                if s["sh_addr"] <= api["st_value"] < s["sh_addr"] + s["sh_size"])
    code = text.data()[api["st_value"] - text["sh_addr"]:][:api["st_size"]]
    version, fields = decode_getlangapi(code, api["st_value"], lang["st_value"])

    # `lang` est rempli a l'execution par GetLangAPI : on l'initialise statiquement.
    lang_bytes = {lang["st_value"] + off: label_for(addr) for off, addr in fields.items()}
    pointers.update(lang_bytes)
    version_at = lang["st_value"]

    lines = [f"/* Genere par langconv.py depuis {src.split('/')[-1]} (version API {version}) */",
             "\t.section .note.GNU-stack,\"\",%progbits"]
    for s in sections:
        name = f"S{s.name.replace('.', '_')}"
        lines.append(f"\t.data\n\t.balign 32\n{name}:")
        base = s["sh_addr"]
        data = bytearray(s["sh_size"]) if s.name == ".bss" else bytearray(s.data())
        if base <= version_at < base + len(data):
            struct.pack_into("<i", data, version_at - base, version)
        pos = 0
        while pos < len(data):
            addr = base + pos
            if addr in pointers:
                lines.append(f"\t.quad {pointers[addr]}")
                pos += 8
                continue
            end = pos
            while end < len(data) and base + end not in pointers and end - pos < 32:
                end += 1
            lines.append("\t.byte " + ",".join(str(x) for x in data[pos:end]))
            pos = end

    for name, sym in sorted(symbols.items()):
        if sym["st_info"]["type"] != "STT_OBJECT" or sym["st_info"]["bind"] != "STB_GLOBAL" or not name:
            continue
        lines.append(f"\t.globl {name}\n\t.type {name},%object\n\t.size {name},{sym['st_size']}\n"
                     f"\t.set {name},{label_for(sym['st_value'])}")

    # Etiquette locale (pas le symbole exporte `lang`) : obligatoire dans un .so
    local_lang = label_for(lang["st_value"])
    lines.append("\t.text\n\t.globl GetLangAPI\n\t.type GetLangAPI,%function\nGetLangAPI:\n"
                 f"\tadrp x0, {local_lang}\n\tadd x0, x0, :lo12:{local_lang}\n\tret\n"
                 "\t.size GetLangAPI,.-GetLangAPI")
    open(out, "w", newline="\n").write("\n".join(lines) + "\n")
    print(f"{out} : version {version}, {len(fields)} champs, {len(pointers)} pointeurs")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
