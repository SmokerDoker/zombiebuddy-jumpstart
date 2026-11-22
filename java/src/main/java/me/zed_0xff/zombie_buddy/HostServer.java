package me.zed_0xff.zombie_buddy;

import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * Jumpstart fork: a game hosted from the main menu ("Host") gets ZombieBuddy too.
 *
 * <p>PZ starts the server of a hosted game as a separate Java process (zombie.network.CoopMaster)
 * with the game's class path but without the game's Java agent, so Java mods never ran on hosted
 * servers. This puts {@code -javaagent:<this jar>=<options>} into that command line, right before
 * the main class. Options that only make sense once per computer (the Lua HTTP server and its port,
 * experimental features) are left out, and the server never asks: {@code policy=deny-new}, so it
 * loads exactly the jars the player already approved. JVM options {@code -Djumpstart.*} are passed on.
 */
public final class HostServer {
    private static final String MAIN_CLASS = "zombie.network.GameServer";
    private static final Set<String> CLIENT_ONLY = Set.of("experimental", "lua_server_port", "policy", "frontend", "exit_after_game_init");

    private HostServer() {}

    static void install(Instrumentation inst) {
        new AgentBuilder.Default()
            .disableClassFormatChanges()
            .type(ElementMatchers.named("zombie.network.CoopMaster"))
            .transform((builder, type, loader, module, domain) -> builder.visit(
                new AsmVisitorWrapper.ForDeclaredMethods().method(ElementMatchers.any(),
                    (t, method, mv, ctx, pool, wf, rf) -> new MethodVisitor(Opcodes.ASM9, mv) {
                        private boolean lastWasAload;

                        @Override
                        public void visitVarInsn(int opcode, int var) {
                            super.visitVarInsn(opcode, var);
                            lastWasAload = opcode == Opcodes.ALOAD;
                        }

                        @Override
                        public void visitLdcInsn(Object value) {
                            if (lastWasAload && MAIN_CLASS.equals(value)) {
                                // the command list is on the stack: commandList.add(...) follows
                                super.visitInsn(Opcodes.DUP);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "me/zed_0xff/zombie_buddy/HostServer",
                                    "addAgent", "(Ljava/util/List;)V", false);
                                Logger.info("hosted games: the server process gets ZombieBuddy (" + method.getName() + ")");
                            }
                            lastWasAload = false;
                            super.visitLdcInsn(value);
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            lastWasAload = false;
                            super.visitInsn(opcode);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                            lastWasAload = false;
                            super.visitMethodInsn(opcode, owner, name, desc, itf);
                        }

                        @Override
                        public void visitMaxs(int maxStack, int maxLocals) {
                            super.visitMaxs(maxStack + 1, maxLocals);
                        }
                    })))
            .installOn(inst);
    }

    /** Called from CoopMaster's server launch, just before the main class is added. */
    public static void addAgent(List<String> command) {
        try {
            command.addAll(agentOptions(Agent.arguments, System.getProperties().entrySet().stream()
                .filter(e -> String.valueOf(e.getKey()).startsWith("jumpstart."))
                .collect(TreeMap<String, String>::new, (m, e) -> m.put(String.valueOf(e.getKey()), String.valueOf(e.getValue())), Map::putAll),
                jarPath()));
            Logger.info("hosted server starts with " + command.subList(Math.max(0, command.size() - 3), command.size()));
        } catch (RuntimeException | URISyntaxException e) {
            Logger.error("hosted games: could not add ZombieBuddy to the server: " + e);
        }
    }

    /** The JVM options for the hosted server: the agent with the safe options, then -Djumpstart.* */
    static List<String> agentOptions(Map<String, String> arguments, Map<String, String> jumpstartProperties, Path jar) {
        StringBuilder options = new StringBuilder("policy=deny-new");
        new TreeMap<>(arguments).forEach((key, value) -> {
            if (!CLIENT_ONLY.contains(key)) {
                options.append(',').append(key);
                if (!value.isEmpty()) {
                    options.append('=').append(value);
                }
            }
        });
        List<String> result = new ArrayList<>();
        result.add("-javaagent:" + jar + "=" + options);
        jumpstartProperties.forEach((k, v) -> result.add("-D" + k + "=" + v));
        return result;
    }

    private static Path jarPath() throws URISyntaxException {
        return Path.of(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
    }
}
