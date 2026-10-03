package com.example.elf32runner.engine;

/**
 * A32 CPU —— 32 位 ARM 指令集的纯 Java 解释器。
 *
 * <p>这是本应用的“模拟器”核心：在纯 64 位（AArch64）设备上，CPU 硬件已删除
 * AArch32 执行能力，本类把 32 位 ARM（ARMv7/A32）指令流一条条取出、解码、
 * 解释执行，从而“模拟”32 位程序的运行。</p>
 *
 * <p>语义与 {@code arm32-on-arm64/tango-lite/tl_core.py} 的 {@code A32CPU}
 * 一一对应（同一套 NZCV 标志规则、同样的 PC=地址+8 约定、同样的 32 位回绕）。
 * 移植的是“解释执行”这条路，而不是“翻译成 A64 再跑”——因为解释器本身就能
 * 在任何 AArch64 设备上运行，无需 JIT，体积最小、最稳定。</p>
 *
 * <p>覆盖的指令子集（足够跑静态链接、依赖少量 Linux syscall 的命令行程序）：
 * 数据处理（mov/mvn/add/sub/and/orr/eor/bic/cmp/cmn/tst/teq，含立即数、寄存器、
 * 桶形移位、带 S 位）、乘法 mul、单字/字节访存 ldr/str（偏移与前/后索引）、
 * 分支 b/bl/bx、软件中断 svc。</p>
 */
public final class A32CPU {

    /** 寄存器数量：r0..r14 + r15(PC) + CPSR。 */
    public static final int REGS = 16;

    /** 执行步数上限，防止死循环拖垮 UI（放在子线程，这里给个硬上限更保险）。 */
    public long maxSteps = 20_000_000L;

    public final int[] r = new int[REGS];   // r[15] = PC

    // NZCV 标志，采用 A32 CPSR 布局：bit31=N bit30=Z bit29=C bit28=V
    public int n, z, c, v;

    private final byte[] mem;
    private final int memBase;
    private final int memSize;

    /** 系统调用处理回调；返回后本解释器继续执行下一条指令。 */
    public interface SyscallHandler {
        /** @return true 表示该 syscall 触发进程退出 */
        boolean handle(A32CPU cpu, int nr);
    }

    private SyscallHandler syscallHandler;

    public A32CPU(byte[] memory, int baseAddress) {
        this.mem = memory;
        this.memBase = baseAddress;
        this.memSize = memory.length;
    }

    public void setSyscallHandler(SyscallHandler h) { this.syscallHandler = h; }

    // ---------------------------------------------------------------------
    // 标志位
    // ---------------------------------------------------------------------
    public void setFlags(boolean nn, boolean zz, boolean cc, boolean vv) {
        n = nn ? 1 : 0; z = zz ? 1 : 0; c = cc ? 1 : 0; v = vv ? 1 : 0;
    }

    /** 根据条件码判断是否满足（A32 每条指令都可条件执行）。 */
    private boolean condOk(int cond) {
        switch (cond) {
            case 0x0: return z == 1;                    // EQ
            case 0x1: return z == 0;                    // NE
            case 0x2: return c == 1;                    // CS/HS
            case 0x3: return c == 0;                    // CC/LO
            case 0x4: return n == 1;                    // MI
            case 0x5: return n == 0;                    // PL
            case 0x6: return v == 1;                    // VS
            case 0x7: return v == 0;                    // VC
            case 0x8: return c == 1 && z == 0;          // HI
            case 0x9: return c == 0 || z == 1;          // LS
            case 0xA: return n == v;                    // GE
            case 0xB: return n != v;                    // LT
            case 0xC: return z == 0 && n == v;          // GT
            case 0xD: return z == 1 || n != v;          // LE
            case 0xE: return true;                       // AL
            default:  return false;                      // cond=1111 保留
        }
    }

    // ---------------------------------------------------------------------
    // 内存访问（小端）
    // ---------------------------------------------------------------------
    private int translate(int addr) {
        int off = addr - memBase;
        if (off < 0 || off >= memSize) {
            throw new GuestFault("内存访问越界 @ 0x" + Integer.toHexString(addr));
        }
        return off;
    }

    public int ldw(int addr) {
        int o = translate(addr);
        return (mem[o] & 0xFF) | ((mem[o + 1] & 0xFF) << 8)
             | ((mem[o + 2] & 0xFF) << 16) | ((mem[o + 3] & 0xFF) << 24);
    }

    public void stw(int addr, int val) {
        int o = translate(addr);
        mem[o]     = (byte) (val);
        mem[o + 1] = (byte) (val >>> 8);
        mem[o + 2] = (byte) (val >>> 16);
        mem[o + 3] = (byte) (val >>> 24);
    }

    public int ldb(int addr) {
        return mem[translate(addr)] & 0xFF;
    }

    public void stb(int addr, int val) {
        mem[translate(addr)] = (byte) val;
    }

    // ---------------------------------------------------------------------
    // 主执行循环
    // ---------------------------------------------------------------------
    /** 执行直到退出（syscall 触发或 PC 落出代码区）。返回执行步数。 */
    public long run() {
        long steps = 0;
        while (steps < maxSteps) {
            int addr = r[15];
            int word;
            try {
                word = ldw(addr);
            } catch (GuestFault e) {
                throw new Trap("PC 越界 @ 0x" + Integer.toHexString(addr));
            }
            r[15] = addr + 4;         // 先推进 PC，PC = 当前地址 + 8 的语义在译码时补
            exec(word, addr);
            steps++;
        }
        return steps;
    }

    /** 执行单条指令（word 为原始机器码，addr 为指令所在地址，用于 PC 相对寻址）。 */
    public void exec(int word, int addr) {
        int cond = (word >>> 28) & 0xF;
        if (cond != 0xE && !condOk(cond)) {
            return;   // 条件不满足，指令空转
        }

        if (((word >>> 25) & 0x7) == 5) {
            execBranch(word, addr);
        } else if ((word & 0x0FFFFFF0) == 0x012FFF10 || (word & 0x0FFFFFF0) == 0x012FFF30) {
            // bx rm（0x012FFF10）与 blx rm（0x012FFF30，忽略 L 位）
            execBx(word);
        } else if ((word & 0x0F000000) == 0x0F000000) {
            execSvc(word);
        } else if ((word & 0x0E000090) == 0x00000090) {
            execMul(word);
        } else if ((word & 0x0C000000) == 0x04000000) {
            execLdStr(word, addr);
        } else if ((word & 0x0C000000) == 0x00000000) {
            execDataProc(word);
        } else {
            throw new UnsupportedA32(addr, word, "未识别的指令编码");
        }
    }

    // ---------------------------------------------------------------------
    // 分支：b / bl
    // ---------------------------------------------------------------------
    private void execBranch(int word, int addr) {
        int imm24 = word & 0x00FFFFFF;
        if ((imm24 & 0x00800000) != 0) imm24 |= 0xFF000000;   // 符号扩展
        int offset = imm24 << 2;
        int target = addr + 8 + offset;                       // PC = addr + 8
        boolean link = ((word >>> 24) & 1) != 0;
        if (link) r[14] = addr + 4;                           // LR = 下一条指令
        r[15] = target;
    }

    /** bx rm */
    private void execBx(int word) {
        int rm = word & 0xF;
        r[15] = r[rm];
    }

    /** svc #imm24 —— 交给 SyscallHandler */
    private void execSvc(int word) {
        int nr = r[7];   // ARM EABI：系统调用号在 r7
        if (syscallHandler == null) {
            throw new UnsupportedA32(r[15] - 4, word, "svc 但未注册 SyscallHandler");
        }
        if (syscallHandler.handle(this, nr)) {
            throw new Halt();   // 进程退出
        }
    }

    // ---------------------------------------------------------------------
    // 乘法 mul rd, rm, rs （无累加）
    // ---------------------------------------------------------------------
    private void execMul(int word) {
        int rd = (word >>> 16) & 0xF;
        int rs = (word >>> 8) & 0xF;
        int rm = word & 0xF;
        int result = r[rm] * r[rs];
        r[rd] = result;
        if (((word >>> 20) & 1) != 0) {   // S 位
            n = (result < 0) ? 1 : 0;
            z = (result == 0) ? 1 : 0;
        }
    }

    // ---------------------------------------------------------------------
    // 单数据传送：ldr / str（字与字节，含偏移与前后索引）
    // ---------------------------------------------------------------------
    private void execLdStr(int word, int addr) {
        // 注意：访存指令的 bit25 语义与数据处理指令相反——
        // 访存：bit25=0 是立即数偏移，bit25=1 是寄存器偏移（媒体指令）。
        boolean imm = ((word >>> 25) & 1) == 0;
        boolean pre = ((word >>> 24) & 1) != 0;
        boolean up  = ((word >>> 23) & 1) != 0;
        boolean byteW = ((word >>> 22) & 1) != 0;
        boolean writeback = ((word >>> 21) & 1) != 0;
        boolean load = ((word >>> 20) & 1) != 0;
        int rn = (word >>> 16) & 0xF;
        int rd = (word >>> 12) & 0xF;

        int offset;
        if (imm) {
            // 立即数偏移：imm12 字段直接就是字节偏移（编码器保证字访问 4 字节对齐）
            offset = word & 0xFFF;
        } else {
            int rm = word & 0xF;
            int shift = (word >>> 7) & 0x1F;
            int type = (word >>> 5) & 0x3;
            offset = shifted(r[rm], type, shift);
        }
        if (!up) offset = -offset;

        int base = r[rn];
        int addr2 = pre ? base + offset : base;
        // PC 相对寻址：rn==15 时基址 = 指令地址 + 8
        if (rn == 15) {
            addr2 = pre ? (addr + 8 + offset) : (addr + 8);
        }

        if (load) {
            int val = byteW ? ldb(addr2) : ldw(addr2);
            if (rd == 15) {
                // ldr pc —— 跳转
                r[15] = val;
            } else {
                r[rd] = val;
            }
        } else {
            if (byteW) stb(addr2, r[rd]);
            else stw(addr2, r[rd]);
        }

        if (writeback && rn != 15) {
            r[rn] = base + offset;   // 写回总是用 base+offset（即使 pre 也如此）
        }
    }

    // ---------------------------------------------------------------------
    // 桶形移位器
    // ---------------------------------------------------------------------
    private int shifted(int val, int type, int sh) {
        switch (type) {
            case 0: return val << sh;                       // LSL
            case 1: return val >>> sh;                       // LSR（逻辑）
            case 2: return val >> sh;                        // ASR（算术）
            case 3:                                         // ROR / RRX
                if (sh == 0) {
                    // RRX：右移一位，C 进入最高位
                    int out = (val >>> 1) | (c << 31);
                    c = val & 1;
                    return out;
                }
                return Integer.rotateRight(val, sh);
            default: return val;
        }
    }

    // ---------------------------------------------------------------------
    // 数据处理指令
    // ---------------------------------------------------------------------
    private void execDataProc(int word) {
        boolean imm = ((word >>> 25) & 1) != 0;
        int opc = (word >>> 21) & 0xF;
        boolean s = ((word >>> 20) & 1) != 0;
        int rn = (word >>> 16) & 0xF;
        int rd = (word >>> 12) & 0xF;

        // 第二操作数
        int operand2;
        boolean carryOut = c;   // 默认 C 保持不变（逻辑指令）
        if (imm) {
            int imm8 = word & 0xFF;
            int rot = ((word >>> 8) & 0xF) * 2;
            operand2 = Integer.rotateRight(imm8, rot);
            if (rot != 0) carryOut = (operand2 >>> 31) & 1;
        } else {
            int rm = word & 0xF;
            int shiftImm = ((word >>> 7) & 1) != 0;
            int type;
            int sh;
            if (shiftImm) {
                type = (word >>> 5) & 0x3;
                int sh5 = (word >>> 7) & 0x1F;
                if (sh5 == 0) {
                    // LSL #0 = 不移位，C 不变
                    operand2 = r[rm];
                } else {
                    operand2 = shifted(r[rm], type, sh5);
                    carryOut = carryOfShift(r[rm], type, sh5);
                }
            } else {
                // 寄存器移位：移位量在 rs 低字节
                int rs = (word >>> 8) & 0xF;
                type = (word >>> 5) & 0x3;
                sh = r[rs] & 0xFF;
                if (sh == 0) {
                    operand2 = r[rm];
                } else {
                    operand2 = shifted(r[rm], type, sh);
                    if (sh >= 32) carryOut = c;
                    else carryOut = carryOfShift(r[rm], type, sh);
                }
            }
        }

        int rnVal = r[rn];

        switch (opc) {
            case 0x0: {   // AND
                int res = rnVal & operand2;
                setResult(s, rd, res, false);
                break;
            }
            case 0x1: {   // EOR
                int res = rnVal ^ operand2;
                setResult(s, rd, res, false);
                break;
            }
            case 0x2: {   // SUB / RSB
                int res = rnVal - operand2;
                if (s) {
                    // 借位：sub 的 C = NOT borrow
                    long full = (rnVal & 0xFFFFFFFFL) - (operand2 & 0xFFFFFFFFL);
                    c = (full >= 0) ? 1 : 0;
                    v = overflowSub(rnVal, operand2, res);
                    setNZ(rd, res);
                }
                setReg(rd, res);
                break;
            }
            case 0x3: {   // RSB
                int res = operand2 - rnVal;
                if (s) {
                    long full = (operand2 & 0xFFFFFFFFL) - (rnVal & 0xFFFFFFFFL);
                    c = (full >= 0) ? 1 : 0;
                    v = overflowSub(operand2, rnVal, res);
                    setNZ(rd, res);
                }
                setReg(rd, res);
                break;
            }
            case 0x4: {   // ADD
                int res = rnVal + operand2;
                if (s) {
                    long full = (rnVal & 0xFFFFFFFFL) + (operand2 & 0xFFFFFFFFL);
                    c = (full > 0xFFFFFFFFL) ? 1 : 0;
                    v = overflowAdd(rnVal, operand2, res);
                    setNZ(rd, res);
                }
                setReg(rd, res);
                break;
            }
            case 0x5: {   // ADC
                int res = rnVal + operand2 + c;
                if (s) {
                    long full = (rnVal & 0xFFFFFFFFL) + (operand2 & 0xFFFFFFFFL) + c;
                    c = (full > 0xFFFFFFFFL) ? 1 : 0;
                    v = overflowAdd(rnVal, operand2, res);
                    setNZ(rd, res);
                }
                setReg(rd, res);
                break;
            }
            case 0x6: {   // SBC
                int res = rnVal - operand2 - (1 - c);
                if (s) {
                    long full = (rnVal & 0xFFFFFFFFL) - (operand2 & 0xFFFFFFFFL) - (1 - c);
                    c = (full >= 0) ? 1 : 0;
                    v = overflowSub(rnVal, operand2, res);
                    setNZ(rd, res);
                }
                setReg(rd, res);
                break;
            }
            case 0x7: {   // RSC
                int res = operand2 - rnVal - (1 - c);
                if (s) {
                    long full = (operand2 & 0xFFFFFFFFL) - (rnVal & 0xFFFFFFFFL) - (1 - c);
                    c = (full >= 0) ? 1 : 0;
                    v = overflowSub(operand2, rnVal, res);
                    setNZ(rd, res);
                }
                setReg(rd, res);
                break;
            }
            case 0x8: case 0x9: {   // TST / TEQ（结果不写回，只更新标志）
                int res = (opc == 0x8) ? (rnVal & operand2) : (rnVal ^ operand2);
                setNZ(15, res);   // rd 位置不影响，仅借 setNZ 更新 N/Z
                break;
            }
            case 0xA: {   // CMP
                int res = rnVal - operand2;
                long full = (rnVal & 0xFFFFFFFFL) - (operand2 & 0xFFFFFFFFL);
                c = (full >= 0) ? 1 : 0;
                v = overflowSub(rnVal, operand2, res);
                setNZ(15, res);
                break;
            }
            case 0xB: {   // CMN（加比较）
                int res = rnVal + operand2;
                long full = (rnVal & 0xFFFFFFFFL) + (operand2 & 0xFFFFFFFFL);
                c = (full > 0xFFFFFFFFL) ? 1 : 0;
                v = overflowAdd(rnVal, operand2, res);
                setNZ(15, res);
                break;
            }
            case 0xC: {   // ORR
                int res = rnVal | operand2;
                setResult(s, rd, res, false);
                break;
            }
            case 0xD: {   // MOV
                int res = operand2;
                if (s) {
                    setNZ(rd, res);
                    c = carryOut;   // MOVS 会把移位进位写进 C
                }
                setReg(rd, res);
                break;
            }
            case 0xE: {   // BIC
                int res = rnVal & ~operand2;
                setResult(s, rd, res, false);
                break;
            }
            case 0xF: {   // MVN
                int res = ~operand2;
                if (s) {
                    setNZ(rd, res);
                    c = carryOut;
                }
                setReg(rd, res);
                break;
            }
        }
    }

    private void setReg(int rd, int val) {
        if (rd == 15) {
            r[15] = val;   // 写 PC = 跳转
        } else {
            r[rd] = val;
        }
    }

    private void setResult(boolean s, int rd, int res, boolean withCarry) {
        if (s) {
            setNZ(rd, res);
        }
        setReg(rd, res);
    }

    private void setNZ(int ignored, int res) {
        n = (res < 0) ? 1 : 0;
        z = (res == 0) ? 1 : 0;
    }

    private int carryOfShift(int val, int type, int sh) {
        switch (type) {
            case 0: return sh == 0 ? c : ((val >>> (32 - sh)) & 1);   // LSL
            case 1: return sh == 0 ? ((val >>> 31) & 1) : ((val >>> (sh - 1)) & 1); // LSR
            case 2: return sh == 0 ? ((val >>> 31) & 1) : ((val >> (sh - 1)) & 1);  // ASR
            case 3: return (val >>> ((sh - 1) & 31)) & 1;             // ROR
            default: return c;
        }
    }

    private int overflowAdd(int a, int b, int res) {
        return (((a ^ res) & (b ^ res)) < 0) ? 1 : 0;
    }

    private int overflowSub(int a, int b, int res) {
        return (((a ^ b) & (a ^ res)) < 0) ? 1 : 0;
    }

    // ---------------------------------------------------------------------
    // 异常
    // ---------------------------------------------------------------------
    public static class GuestFault extends RuntimeException {
        public GuestFault(String m) { super(m); }
    }
    public static class Trap extends RuntimeException {
        public Trap(String m) { super(m); }
    }
    public static class Halt extends RuntimeException {
        public Halt() { super("process exit"); }
    }
    public static class UnsupportedA32 extends RuntimeException {
        public final int addr, word;
        public UnsupportedA32(int addr, int word, String why) {
            super(why + " @ 0x" + Integer.toHexString(addr) + " word=0x"
                    + Integer.toHexString(word));
            this.addr = addr;
            this.word = word;
        }
    }
}
