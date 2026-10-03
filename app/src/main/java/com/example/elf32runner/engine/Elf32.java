package com.example.elf32runner.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * ELF32 解析器 —— 解析 32 位 ARM 可执行文件，提取代码段与入口点。
 *
 * <p>只支持静态链接的 ELF32（ET_EXEC），ARM（EM_ARM=0x28）小端。
 * 这是"命令行二进制"的最小形态，不涉及动态链接、重定位、plt。</p>
 */
public final class Elf32 {

    public static final int EM_ARM = 0x28;

    public final int entry;
    public final byte[] image;      // 程序头指定的、加载到内存的完整映像
    public final int loadBase;      // 最低加载地址

    private Elf32(int entry, byte[] image, int loadBase) {
        this.entry = entry;
        this.image = image;
        this.loadBase = loadBase;
    }

    /** 从字节数组解析 ELF32。 */
    public static Elf32 parse(byte[] data) {
        if (data.length < 52) throw new IllegalArgumentException("文件太短，不是 ELF");
        if (data[0] != 0x7F || data[1] != 'E' || data[2] != 'L' || data[3] != 'F') {
            throw new IllegalArgumentException("不是 ELF（魔数不符）");
        }
        if (data[4] != 1) throw new IllegalArgumentException("不是 32 位 ELF（ELFCLASS32）");
        if (data[5] != 1) throw new IllegalArgumentException("不是小端（ELFDATA2LSB）");
        int machine = u16(data, 18);
        if (machine != EM_ARM) {
            throw new IllegalArgumentException("不是 ARM 架构，e_machine=0x"
                    + Integer.toHexString(machine));
        }
        int type = u16(data, 16);
        if (type != 2) {
            throw new IllegalArgumentException("不是可执行文件（ET_EXEC=2），type=" + type);
        }
        int entry = u32(data, 24);
        int phoff = u32(data, 28);
        int phentsize = u16(data, 42);
        int phnum = u16(data, 44);

        List<Seg> loads = new ArrayList<>();
        for (int i = 0; i < phnum; i++) {
            int off = phoff + i * phentsize;
            if (off + phentsize > data.length) break;
            int pType = u32(data, off);
            if (pType != 1) continue;   // 只关心 PT_LOAD
            int pOffset = u32(data, off + 4);
            int pVaddr  = u32(data, off + 8);
            int pFilesz = u32(data, off + 16);
            int pMemsz  = u32(data, off + 20);
            int pFlags  = u32(data, off + 24);
            loads.add(new Seg(pOffset, pVaddr, pFilesz, pMemsz, pFlags));
        }

        if (loads.isEmpty()) {
            throw new IllegalArgumentException("没有 PT_LOAD 段，无法加载");
        }

        int base = Integer.MAX_VALUE;
        int top = 0;
        for (Seg s : loads) {
            base = Math.min(base, s.vaddr);
            top = Math.max(top, s.vaddr + s.memsz);
        }

        byte[] image = new byte[align(top - base, 0x1000)];
        for (Seg s : loads) {
            int dst = s.vaddr - base;
            int n = Math.min(s.filesz, data.length - s.offset);
            if (n > 0) System.arraycopy(data, s.offset, image, dst, n);
        }

        return new Elf32(entry, image, base);
    }

    public static boolean isElf(byte[] data) {
        return data.length >= 4 && data[0] == 0x7F && data[1] == 'E'
                && data[2] == 'L' && data[3] == 'F';
    }

    private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
    private static int u32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
             | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }
    private static int align(int v, int a) { return (v + a - 1) & ~(a - 1); }

    private static final class Seg {
        final int offset, vaddr, filesz, memsz, flags;
        Seg(int o, int va, int f, int m, int fl) { offset = o; vaddr = va; filesz = f; memsz = m; flags = fl; }
    }
}
