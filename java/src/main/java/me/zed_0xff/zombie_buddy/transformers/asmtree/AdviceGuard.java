package me.zed_0xff.zombie_buddy.transformers.asmtree;

import java.util.ArrayList;


import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import me.zed_0xff.zombie_buddy.ModGuard;
import me.zed_0xff.zombie_buddy.transformers.AnnElements;
import me.zed_0xff.zombie_buddy.transformers.Pipeline;

/**
 * Jumpstart fork, crash protection: every Advice method of a mod gets
 * <ul>
 *   <li>{@code suppress = Throwable}: Byte Buddy wraps the inlined advice in a handler, so an
 *       exception from the patch (or anything it calls, including a failing static initializer)
 *       no longer goes up the game method. PatchEngine sets the handler ({@link ModGuard#adviceFailed}).
 *       An enter advice that throws returns its default value (a skipOn advice then does not skip);
 *       an exit advice leaves the return value as it is.</li>
 *   <li>a trip wire as its first instructions: {@code if (ModGuard.isOff(id)) return <default>;}.
 *       Switching a mod off through the guard silences it at once, even when its patches cannot be
 *       removed (no hotswap, or a MethodDelegation patch in the same jar).</li>
 * </ul>
 * Not for the agent option {@code guard=off} or packages in {@code guard_exempt}; no trip wire for
 * ZombieBuddy's own patches and the embedded mod, which are never switched off.
 */
public class AdviceGuard extends AbstractTransformer {
    private static final String ENTER = "Lnet/bytebuddy/asm/Advice$OnMethodEnter;";
    private static final String EXIT = "Lnet/bytebuddy/asm/Advice$OnMethodExit;";
    private static final String GUARD = "me/zed_0xff/zombie_buddy/ModGuard";

    @Override
    protected boolean transformNode(ClassNode cn) {
        if (!ModGuard.isEnabled()) {
            return false;
        }
        String pkg = Pipeline.currentPackage();
        if (pkg == null) {
            int slash = cn.name.lastIndexOf('/');
            pkg = slash < 0 ? "" : cn.name.substring(0, slash).replace('/', '.');
        }
        if (ModGuard.isExempt(pkg)) {
            return false;
        }
        boolean tripWire = !ModGuard.isProtected(pkg);
        boolean changed = false;
        for (MethodNode mn : cn.methods) {
            AnnotationNode advice = adviceAnnotation(mn);
            if (advice == null) {
                continue;
            }
            changed |= suppressThrowable(advice);
            if (tripWire && (mn.access & Opcodes.ACC_STATIC) != 0) {
                changed |= addTripWire(mn, ModGuard.idFor(pkg));
            }
        }
        return changed;
    }

    private static AnnotationNode adviceAnnotation(MethodNode mn) {
        if (mn.visibleAnnotations == null) {
            return null;
        }
        for (AnnotationNode an : mn.visibleAnnotations) {
            if (ENTER.equals(an.desc) || EXIT.equals(an.desc)) {
                return an;
            }
        }
        return null;
    }

    private static boolean suppressThrowable(AnnotationNode an) {
        AnnElements els = AnnElements.fromValues(an.values);
        Type throwable = Type.getType(Throwable.class);
        if (throwable.equals(els.get("suppress"))) {
            return false;
        }
        els.put("suppress", throwable);
        an.values = new ArrayList<>(els.toValues());
        return true;
    }

    /** {@code if (ModGuard.isOff(id)) return <default>;} at the start; idempotent. */
    private static boolean addTripWire(MethodNode mn, int id) {
        AbstractInsnNode first = firstInstruction(mn);
        if (first instanceof MethodInsnNode call && call.owner.equals(GUARD) && call.name.equals("isOff")) {
            return false; // already there (a patch class transformed twice)
        }
        if (first == null) {
            return false;
        }
        // jump target: an existing frame at the very start (a loop head) is reused, else a new one
        LabelNode target = null;
        for (AbstractInsnNode n = mn.instructions.getFirst(); n != null && n.getOpcode() < 0; n = n.getNext()) {
            if (n instanceof FrameNode) {
                AbstractInsnNode prev = n.getPrevious();
                while (prev != null && !(prev instanceof LabelNode)) {
                    prev = prev.getPrevious();
                }
                if (prev instanceof LabelNode l) {
                    target = l;
                } else {
                    target = new LabelNode();
                    mn.instructions.insertBefore(n, target);
                }
                break;
            }
        }
        InsnList check = new InsnList();
        check.add(new LdcInsnNode(id));
        check.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GUARD, "isOff", "(I)Z", false));
        LabelNode go = target != null ? target : new LabelNode();
        check.add(new JumpInsnNode(Opcodes.IFEQ, go));
        Type ret = Type.getReturnType(mn.desc);
        switch (ret.getSort()) {
            case Type.VOID -> check.add(new InsnNode(Opcodes.RETURN));
            case Type.LONG -> { check.add(new InsnNode(Opcodes.LCONST_0)); check.add(new InsnNode(Opcodes.LRETURN)); }
            case Type.FLOAT -> { check.add(new InsnNode(Opcodes.FCONST_0)); check.add(new InsnNode(Opcodes.FRETURN)); }
            case Type.DOUBLE -> { check.add(new InsnNode(Opcodes.DCONST_0)); check.add(new InsnNode(Opcodes.DRETURN)); }
            case Type.OBJECT, Type.ARRAY -> { check.add(new InsnNode(Opcodes.ACONST_NULL)); check.add(new InsnNode(Opcodes.ARETURN)); }
            default -> { check.add(new InsnNode(Opcodes.ICONST_0)); check.add(new InsnNode(Opcodes.IRETURN)); }
        }
        if (target == null) {
            check.add(go);
            // same locals as on entry, empty stack; later compressed frames stay valid after F_SAME
            check.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        }
        mn.instructions.insert(check);
        mn.maxStack = Math.max(mn.maxStack, 2);
        return true;
    }

    private static AbstractInsnNode firstInstruction(MethodNode mn) {
        for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() >= 0) {
                return n instanceof LdcInsnNode ? n.getNext() : n;
            }
        }
        return null;
    }
}
