package com.example.elf32runner.engine;

import java.io.PrintStream;

/**
 * 运行器 —— 把 ELF 解析、CPU、系统调用桥串起来，执行一个 32 位程序。
 */
public final class Runner {

    public static String run(byte[] elfBytes, PrintStream stdout, PrintStream stderr) {
        try {
            if (!Elf32.isElf(elfBytes)) {
                return "错误：不是 ELF 文件";
            }
            Elf32 elf = Elf32.parse(elfBytes);

            A32CPU cpu = new A32CPU(elf.image, elf.loadBase);
            cpu.setSyscallHandler(new ArmSyscalls(stdout, stderr));

            // 初始化：PC = 入口，SP 放在映像顶端（简化：栈空间由宿主侧分配的 image 尾部充当）
            cpu.r[15] = elf.entry;
            cpu.r[13] = elf.loadBase + elf.image.length - 0x10;   // 指向映像尾部留一点空间

            long steps = cpu.run();
            return "\n[程序正常退出] 执行了 " + steps + " 条指令";

        } catch (A32CPU.Halt h) {
            return "\n[程序正常退出]";
        } catch (A32CPU.UnsupportedA32 u) {
            return "\n[不支持] " + u.getMessage();
        } catch (A32CPU.GuestFault g) {
            return "\n[内存错误] " + g.getMessage();
        } catch (A32CPU.Trap t) {
            return "\n[陷阱] " + t.getMessage();
        } catch (IllegalArgumentException e) {
            return "\n[格式错误] " + e.getMessage();
        } catch (Exception e) {
            return "\n[运行时错误] " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
