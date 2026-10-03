import com.example.elf32runner.engine.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 宿主侧入口：在普通 JVM 上直接跑 Android 工程里那份 engine 代码。
 *
 * <p>这是比 Python 等价实现更强的自检——验证的是 APK 里真正会打包进去的同一份
 * Java 源码。engine 包刻意不依赖任何 android.* 类（只用 java.io.PrintStream），
 * 所以能在没有 Android SDK 的机器上编译运行。</p>
 *
 * <p>用法（工程根目录）：
 * <pre>
 *   javac -encoding UTF-8 -d build/host \
 *       app/src/main/java/com/example/elf32runner/engine/*.java \
 *       tools/host/HostMain.java
 *   java -Dfile.encoding=UTF-8 -cp build/host HostMain samples/hello_arm32
 *   java -cp build/host HostMain samples/sum_1_10
 * </pre>
 * </p>
 *
 * <p>退出码：全部样例通过返回 0，任一失败返回 1（供 CI 当构建门槛）。</p>
 */
public class HostMain {

    /** 一个样例：文件、期望输出、期望退出寄存器值（不需要检查时用 null）。 */
    static class Case {
        String file;
        String expectStdout;
        Integer expectR0;
        String name;

        Case(String name, String file, String expectStdout, Integer expectR0) {
            this.name = name;
            this.file = file;
            this.expectStdout = expectStdout;
            this.expectR0 = expectR0;
        }
    }

    /** 装载结果：既要拿到 stdout，也要能检查最终寄存器。 */
    static class Result {
        String stdout;
        int r0;
        String status;
    }

    public static void main(String[] args) throws Exception {
        Case[] cases;
        if (args.length > 0) {
            cases = new Case[]{new Case(args[0], args[0], null, null)};
        } else {
            cases = new Case[]{
                new Case("hello_arm32", "samples/hello_arm32", "Hello from ARM32!\n", null),
                new Case("sum_1_10",   "samples/sum_1_10",   "",                   55),
            };
        }

        PrintStream realOut = new PrintStream(System.out, true, "UTF-8");
        boolean allOk = true;

        for (Case c : cases) {
            byte[] elf = Files.readAllBytes(Paths.get(c.file));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(bos, true, StandardCharsets.UTF_8.name());

            Result r = execute(elf, ps);
            String stdout = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            ps.close();

            boolean ok = true;
            StringBuilder why = new StringBuilder();
            if (!r.status.startsWith("[程序正常退出]")) {
                ok = false;
                why.append(" 状态异常: ").append(r.status);
            }
            if (c.expectStdout != null && !c.expectStdout.equals(stdout)) {
                ok = false;
                why.append(" stdout 不符: 期望 ").append(quote(c.expectStdout))
                   .append(" 实际 ").append(quote(stdout));
            }
            if (c.expectR0 != null && r.r0 != c.expectR0) {
                ok = false;
                why.append(" r0 不符: 期望 ").append(c.expectR0).append(" 实际 ").append(r.r0);
            }

            realOut.println((ok ? "  [通过] " : "  [失败] ") + c.name
                    + "   输出=" + quote(stdout) + "   r0=" + r.r0);
            if (!ok) {
                realOut.println(why.toString());
                allOk = false;
            }
        }

        realOut.println(allOk ? "\n结论：Java 引擎全部样例通过"
                              : "\n结论：存在失败样例");
        System.exit(allOk ? 0 : 1);
    }

    /**
     * 跑一个 ELF。这里重复了 Runner 的流程，但保留 CPU 对象以便检查寄存器，
     * 因此不直接复用 Runner.run()（那个只返回字符串）。
     */
    private static Result execute(byte[] elfBytes, PrintStream ps) {
        Result r = new Result();
        try {
            Elf32 elf = Elf32.parse(elfBytes);
            A32CPU cpu = new A32CPU(elf.image, elf.loadBase);
            cpu.setSyscallHandler(new ArmSyscalls(ps, ps));
            cpu.r[15] = elf.entry;
            cpu.r[13] = elf.loadBase + elf.image.length - 0x10;

            try {
                cpu.run();
                r.status = "[程序正常退出] 未触发 exit 系统调用";
            } catch (A32CPU.Halt h) {
                r.status = "[程序正常退出]";
            } catch (A32CPU.UnsupportedA32 u) {
                r.status = "[不支持] " + u.getMessage();
            } catch (A32CPU.GuestFault g) {
                r.status = "[内存错误] " + g.getMessage();
            } catch (A32CPU.Trap t) {
                r.status = "[陷阱] " + t.getMessage();
            }
            r.r0 = cpu.r[0];
        } catch (Exception e) {
            r.status = "[错误] " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return r;
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            if (ch == '\n') b.append("\\n");
            else if (ch == '\r') b.append("\\r");
            else if (ch == '\t') b.append("\\t");
            else if (ch < 0x20 || ch > 0x7E) b.append(String.format("\\u%04X", (int) ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }
}
