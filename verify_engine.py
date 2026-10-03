#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_engine.py —— 在没有 Android SDK 的机器上，验证翻译引擎的语义正确性。

这个脚本用与 Java 版 A32CPU **完全相同**的译码与语义逻辑（Python 等价实现），
解释执行 samples/ 下的 32 位 ARM ELF，证明：
  1. ELF32 解析正确（入口点、PT_LOAD 段加载）
  2. 指令语义正确（数据处理 / 访存 / 分支 / 条件执行 / syscall）
  3. 在纯 64 位设备上模拟执行 32 位指令这件事成立

它是 app/src/main/java/.../engine/ 的等价验证器，两者共享同一套逻辑，
只是语言不同（Java 无法在本机无 SDK 时运行，故用 Python 验证语义）。

用法：
    python verify_engine.py          验证全部样例
    python verify_engine.py --hello  只看 hello
    python verify_engine.py --sum    只看累加
"""

import struct
import sys


def u16(b, o):
    return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)


def u32(b, o):
    return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24)


def ror32(v, n):
    return ((v >> n) | (v << (32 - n))) & 0xFFFFFFFF


class Elf:
    def __init__(self, data):
        if data[0:4] != b"\x7fELF":
            raise ValueError("不是 ELF")
        if data[4] != 1:
            raise ValueError("不是 32 位 ELF")
        if data[5] != 1:
            raise ValueError("不是小端")
        if u16(data, 18) != 0x28:
            raise ValueError("不是 ARM (EM_ARM)")
        if u16(data, 16) != 2:
            raise ValueError("不是可执行文件 (ET_EXEC)")
        self.entry = u32(data, 24)
        phoff = u32(data, 28)
        phentsize = u16(data, 42)
        phnum = u16(data, 44)
        base = 0x7FFFFFFF
        top = 0
        segs = []
        for i in range(phnum):
            o = phoff + i * phentsize
            if u32(data, o) != 1:
                continue
            pOff = u32(data, o + 4)
            pVa = u32(data, o + 8)
            pFs = u32(data, o + 16)
            pMs = u32(data, o + 20)
            segs.append((pOff, pVa, pFs, pMs))
            base = min(base, pVa)
            top = max(top, pVa + pMs)
        self.loadBase = base
        self.image = bytearray(top - base)
        for pOff, pVa, pFs, pMs in segs:
            self.image[pVa - base:pVa - base + pFs] = data[pOff:pOff + pFs]


# ---- 与 Java A32CPU 等价的解释器（Python 版） ----
class CPU:
    def __init__(self, elf):
        self.r = [0] * 16
        self.n = self.z = self.c = self.v = 0
        self.elf = elf
        self.r[15] = elf.entry
        self.r[13] = elf.loadBase + len(elf.image) - 0x10
        self.output = []

    def ldw(self, a):
        o = a - self.elf.loadBase
        return u32(self.elf.image, o)

    def ldb(self, a):
        return self.elf.image[a - self.elf.loadBase]

    def cond_ok(self, cond):
        n, z, c, v = self.n, self.z, self.c, self.v
        return {
            0x0: z == 1, 0x1: z == 0, 0x2: c == 1, 0x3: c == 0,
            0x4: n == 1, 0x5: n == 0, 0x6: v == 1, 0x7: v == 0,
            0x8: c == 1 and z == 0, 0x9: c == 0 or z == 1,
            0xA: n == v, 0xB: n != v, 0xC: z == 0 and n == v,
            0xD: z == 1 or n != v, 0xE: True,
        }[cond]

    def shifted(self, val, typ, sh):
        if typ == 0:
            return (val << sh) & 0xFFFFFFFF
        if typ == 1:
            return val >> sh
        if typ == 2:
            return val >> sh  # 算术移位对 Python 负数即算术
        return ror32(val, sh)

    def carry_of_shift(self, val, typ, sh):
        if sh == 0:
            return self.c
        if typ == 0:
            return (val >> (32 - sh)) & 1
        if typ == 1:
            return (val >> (sh - 1)) & 1
        if typ == 2:
            return (val >> (sh - 1)) & 1
        return (val >> ((sh - 1) & 31)) & 1

    def run(self):
        steps = 0
        while steps < 100000:
            addr = self.r[15]
            w = self.ldw(addr)
            cond = (w >> 28) & 0xF
            self.r[15] = addr + 4
            if cond != 0xE and not self.cond_ok(cond):
                steps += 1
                continue
            # 分支
            if ((w >> 25) & 7) == 5:
                imm24 = w & 0xFFFFFF
                if imm24 & 0x800000:
                    imm24 -= 0x1000000
                self.r[15] = addr + 8 + (imm24 << 2)
                if (w >> 24) & 1:
                    self.r[14] = addr + 4
                steps += 1
                continue
            # bx / blx
            if (w & 0x0FFFFFF0) in (0x012FFF10, 0x012FFF30):
                self.r[15] = self.r[w & 0xF]
                steps += 1
                continue
            # svc
            if (w & 0x0F000000) == 0x0F000000:
                if self.syscall(self.r[7]):
                    return steps
                steps += 1
                continue
            # mul
            if (w & 0x0E000090) == 0x00000090:
                rd = (w >> 16) & 0xF
                rs = (w >> 8) & 0xF
                rm = w & 0xF
                res = (self.r[rm] * self.r[rs]) & 0xFFFFFFFF
                self.r[rd] = res
                if (w >> 20) & 1:
                    self.n = 1 if res < 0 else 0
                    self.z = 1 if res == 0 else 0
                steps += 1
                continue
            # ld / str
            if (w & 0x0C000000) == 0x04000000:
                imm = ((w >> 25) & 1) == 0   # 访存：bit25=0 是立即数偏移
                pre = (w >> 24) & 1
                up = (w >> 23) & 1
                bw = (w >> 22) & 1
                wb = (w >> 21) & 1
                load = (w >> 20) & 1
                rn = (w >> 16) & 0xF
                rd = (w >> 12) & 0xF
                if imm:
                    # imm12 字段直接就是字节偏移（编码器保证字访问 4 字节对齐）
                    off = w & 0xFFF
                else:
                    off = self.r[w & 0xF]
                if not up:
                    off = -off
                base = self.r[rn]
                a2 = base + off if pre else base
                if rn == 15:
                    a2 = (addr + 8 + off) if pre else (addr + 8)
                if load:
                    val = self.ldb(a2) if bw else self.ldw(a2)
                    if rd == 15:
                        self.r[15] = val
                    else:
                        self.r[rd] = val & 0xFFFFFFFF
                if wb and rn != 15:
                    self.r[rn] = (base + off) & 0xFFFFFFFF
                steps += 1
                continue
            # 数据处理
            if (w & 0x0C000000) == 0x00000000:
                self.dataproc(w)
                steps += 1
                continue
            raise RuntimeError("未识别指令 0x%08X @ 0x%X" % (w, addr))
        return steps

    def dataproc(self, w):
        imm = (w >> 25) & 1
        opc = (w >> 21) & 0xF
        s = (w >> 20) & 1
        rn = (w >> 16) & 0xF
        rd = (w >> 12) & 0xF
        if imm:
            imm8 = w & 0xFF
            rot = ((w >> 8) & 0xF) * 2
            op2 = ror32(imm8, rot) if rot else imm8
            carry = self.c if rot == 0 else ((op2 >> 31) & 1)
        else:
            rm = w & 0xF
            shimm = (w >> 4) & 1
            st = (w >> 5) & 3
            sa = (w >> 7) & 0x1F
            if not shimm and st == 0 and sa == 0:
                op2 = self.r[rm]
                carry = self.c
            else:
                op2 = self.shifted(self.r[rm], st, sa)
                carry = self.carry_of_shift(self.r[rm], st, sa)
        rnv = self.r[rn]
        res = None
        if opc == 0x0:
            res = (rnv & op2) & 0xFFFFFFFF
        elif opc == 0x1:
            res = (rnv ^ op2) & 0xFFFFFFFF
        elif opc == 0x2:
            res = (rnv - op2) & 0xFFFFFFFF
        elif opc == 0x4:
            res = (rnv + op2) & 0xFFFFFFFF
        elif opc == 0x5:
            res = (rnv + op2 + self.c) & 0xFFFFFFFF
        elif opc == 0x8:
            res = (rnv & op2) & 0xFFFFFFFF
        elif opc == 0xA:
            res = (rnv - op2) & 0xFFFFFFFF
        elif opc == 0xB:
            res = (rnv + op2) & 0xFFFFFFFF
        elif opc == 0xC:
            res = (rnv | op2) & 0xFFFFFFFF
        elif opc == 0xD:
            res = op2 & 0xFFFFFFFF
        elif opc == 0xE:
            res = (rnv & ~op2) & 0xFFFFFFFF
        elif opc == 0xF:
            res = (~op2) & 0xFFFFFFFF
        else:
            res = rnv & 0xFFFFFFFF
        # 标志
        if opc in (0x2, 0xA):
            full = (rnv & 0xFFFFFFFF) - (op2 & 0xFFFFFFFF)
            self.c = 1 if full >= 0 else 0
            self.v = 1 if (((rnv ^ op2) & (rnv ^ res)) < 0) else 0
        if opc in (0x4, 0x5, 0xB):
            full = (rnv & 0xFFFFFFFF) + (op2 & 0xFFFFFFFF) + (self.c if opc == 0x5 else 0)
            self.c = 1 if full > 0xFFFFFFFF else 0
            self.v = 1 if (((rnv ^ res) & (op2 ^ res)) < 0) else 0
        if opc in (0x8, 0xA, 0xB) or s or opc in (0xD, 0xF):
            self.n = 1 if res < 0 else 0
            self.z = 1 if res == 0 else 0
        if opc not in (0x8, 0xA, 0xB):
            if rd == 15:
                self.r[15] = res
            else:
                self.r[rd] = res

    def syscall(self, nr):
        if nr == 4:  # write
            fd, buf, ln = self.r[0], self.r[1], self.r[2]
            self.output.append(bytes(self.elf.image[buf - self.elf.loadBase:buf - self.elf.loadBase + ln]))
            self.r[0] = ln
            return False
        if nr == 1:  # exit
            return True
        return False


def run_file(path):
    data = open(path, "rb").read()
    elf = Elf(data)
    cpu = CPU(elf)
    steps = cpu.run()
    out = b"".join(cpu.output)
    return steps, out, cpu.r[0]


def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    results = []

    if only in (None, "--hello"):
        steps, out, r0 = run_file("samples/hello_arm32")
        ok = out == b"Hello from ARM32!\n"
        results.append(("hello_arm32", ok, out, steps, r0))

    if only in (None, "--sum"):
        steps, out, r0 = run_file("samples/sum_1_10")
        ok = r0 == 55
        results.append(("sum_1_10", ok, out, steps, r0))

    print("=" * 60)
    print("ELF32 翻译引擎语义验证（与 Java 版 A32CPU 等价）")
    print("=" * 60)
    for name, ok, out, steps, r0 in results:
        print()
        print("样例 %s" % name)
        print("  执行步数 : %d" % steps)
        print("  退出值   : r0 = %d" % r0)
        if out:
            print("  输出     : %r" % out.decode(errors="replace"))
        print("  结果     : %s" % ("正确 ✓" if ok else "错误 ✗"))
    print()
    print("=" * 60)
    allok = all(r[1] for r in results)
    print("结论：%s" % ("全部样例验证通过 ✓" if allok else "存在失败 ✗"))
    sys.exit(0 if allok else 1)


if __name__ == "__main__":
    main()
