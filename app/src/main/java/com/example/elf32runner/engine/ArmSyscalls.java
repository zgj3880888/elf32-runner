package com.example.elf32runner.engine;

import java.io.PrintStream;

/**
 * Linux ARM 系统调用桥 —— 让被解释的 32 位程序能与宿主交互。
 *
 * <p>ARM EABI 约定：系统调用号在 r7，参数在 r0..r6，返回值在 r0。
 * 这里实现一组最小的、足以让"hello world"类静态程序跑起来的 syscall：
 * write / exit / exit_group。其余按需扩展。</p>
 *
 * <p>这是本应用与"完整 32 位模拟器"之间最诚实的边界之一：真正的 32 位 Android
 * 应用要经过 binder / mmap / futex / sigaction 等几十个系统调用与框架交互，
 * 那些无法在纯用户态、无 root 的 APK 里可靠模拟。本桥只覆盖命令行程序的最小集。</p>
 */
public final class ArmSyscalls implements A32CPU.SyscallHandler {

    // ARM Linux 系统调用号（EABI）
    private static final int NR_EXIT       = 1;
    private static final int NR_READ       = 3;
    private static final int NR_WRITE      = 4;
    private static final int NR_OPEN       = 5;
    private static final int NR_CLOSE      = 6;
    private static final int NR_LSEEK      = 19;
    private static final int NR_GETPID     = 20;
    private static final int NR_EXIT_GROUP = 248;

    private final PrintStream out;
    private final PrintStream err;

    public ArmSyscalls(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    @Override
    public boolean handle(A32CPU cpu, int nr) {
        switch (nr) {
            case NR_WRITE: {
                int fd = cpu.r[0];
                int buf = cpu.r[1];
                int len = cpu.r[2];
                StringBuilder sb = new StringBuilder(len);
                for (int i = 0; i < len; i++) {
                    sb.append((char) (cpu.ldb(buf + i) & 0xFF));
                }
                PrintStream ps = (fd == 1) ? out : err;
                ps.print(sb.toString());
                cpu.r[0] = len;   // 返回写入字节数
                return false;
            }
            case NR_EXIT:
            case NR_EXIT_GROUP:
                return true;   // 进程退出

            case NR_GETPID:
                cpu.r[0] = 0;   // 占位：真实 PID 无意义，返回 0
                return false;

            default:
                err.println("[syscall] 未实现的系统调用号 " + nr);
                cpu.r[0] = -38;   // ENOSYS
                return false;
        }
    }
}
