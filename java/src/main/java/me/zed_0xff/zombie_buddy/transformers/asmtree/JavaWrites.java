package me.zed_0xff.zombie_buddy.transformers.asmtree;

import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import me.zed_0xff.zombie_buddy.ModGuard;
import me.zed_0xff.zombie_buddy.transformers.Pipeline;

/**
 * Jumpstart fork, clean removal of Java writes: call sites in a mod's classes (patch advice too,
 * which Byte Buddy inlines with them) report what they write, before they write it.
 * <ul>
 *   <li>{@code PUTSTATIC} into a game class ({@code zombie/}, {@code se/krka/}, {@code fmod/},
 *       {@code astar/}) and {@code Field.set*} calls: {@code ModWrites} keeps the field's original
 *       value and puts it back when the mod is switched off, if nobody changed it since.</li>
 *   <li>{@code PUTFIELD} into a game class (not in constructors): the same per object.</li>
 *   <li>{@code Files.write*}, {@code newOutputStream}, {@code newBufferedWriter},
 *       {@code new FileOutputStream/FileWriter}: {@code ModIO} logs the path per mod.</li>
 * </ul>
 * No branches are added (no new stack map frames): arguments are parked in fresh locals above
 * {@code maxLocals}, reported, and loaded again for the original instruction. Only for mod jars
 * (the pipeline knows their package); not for ZombieBuddy's own patches or the embedded mod.
 */
public class JavaWrites extends AbstractTransformer {
    private static final String[] GAME_PREFIXES = { "zombie/", "se/krka/", "fmod/", "astar/" };
    private static final String WRITES = "me/zed_0xff/zombie_buddy/ModWrites";
    private static final String IO = "me/zed_0xff/zombie_buddy/ModIO";
    private static final Set<String> FILES_WRITERS = Set.of("write", "writeString", "newOutputStream", "newBufferedWriter");

    @Override
    protected boolean transformNode(ClassNode cn) {
        String pkg = Pipeline.currentPackage();
        if (pkg == null || !ModGuard.isEnabled() || ModGuard.isProtected(pkg) || ModGuard.isExempt(pkg)) {
            return false;
        }
        boolean changed = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null || mn.instructions.size() == 0) {
                continue;
            }
            changed |= transformMethod(cn, mn, pkg);
        }
        return changed;
    }

    private boolean transformMethod(ClassNode cn, MethodNode mn, String pkg) {
        int base = mn.maxLocals;
        int extraLocals = 0;
        boolean changed = false;
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTSTATIC && isGame(fi.owner) && !fi.owner.equals(cn.name)) {
                Type t = Type.getType(fi.desc);
                InsnList hook = new InsnList();
                hook.add(new InsnNode(t.getSize() == 2 ? Opcodes.DUP2 : Opcodes.DUP));
                box(hook, t);
                hook.add(new LdcInsnNode(fi.owner));
                hook.add(new LdcInsnNode(fi.name));
                hook.add(new LdcInsnNode(pkg));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, WRITES, "beforeStatic",
                    "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false));
                mn.instructions.insertBefore(fi, hook);
                changed = true;
            } else if (insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD && isGame(fi.owner)
                    && !fi.owner.equals(cn.name) && !mn.name.equals("<init>")) {
                extraLocals = Math.max(extraLocals, putField(mn, fi, base, pkg));
                changed = true;
            } else if (insn instanceof MethodInsnNode mi) {
                if (mi.getOpcode() == Opcodes.INVOKEVIRTUAL && mi.owner.equals("java/lang/reflect/Field")
                        && mi.name.startsWith("set") && Type.getArgumentTypes(mi.desc).length == 2) {
                    extraLocals = Math.max(extraLocals, fieldSet(mn, mi, base, pkg));
                    changed = true;
                } else if (mi.getOpcode() == Opcodes.INVOKESTATIC && mi.owner.equals("java/nio/file/Files")
                        && FILES_WRITERS.contains(mi.name) && firstArgIs(mi.desc, "Ljava/nio/file/Path;")) {
                    extraLocals = Math.max(extraLocals, noteFirstArg(mn, mi, base, pkg, false));
                    changed = true;
                } else if (mi.getOpcode() == Opcodes.INVOKESPECIAL && mi.name.equals("<init>")
                        && (mi.owner.equals("java/io/FileOutputStream") || mi.owner.equals("java/io/FileWriter"))
                        && (firstArgIs(mi.desc, "Ljava/io/File;") || firstArgIs(mi.desc, "Ljava/lang/String;"))) {
                    extraLocals = Math.max(extraLocals, noteFirstArg(mn, mi, base, pkg, true));
                    changed = true;
                }
            }
        }
        if (changed) {
            mn.maxLocals = base + extraLocals;
            mn.maxStack += 6;
        }
        return changed;
    }

    /** [target, value] -> ModWrites.beforeField(target, boxed value, owner, name, pkg) -> [target, value]. */
    private static int putField(MethodNode mn, FieldInsnNode fi, int base, String pkg) {
        Type value = Type.getType(fi.desc);
        int vSlot = base;
        int oSlot = base + value.getSize();
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(value.getOpcode(Opcodes.ISTORE), vSlot));
        hook.add(new VarInsnNode(Opcodes.ASTORE, oSlot));
        hook.add(new VarInsnNode(Opcodes.ALOAD, oSlot));
        hook.add(new VarInsnNode(value.getOpcode(Opcodes.ILOAD), vSlot));
        box(hook, value);
        hook.add(new LdcInsnNode(fi.owner));
        hook.add(new LdcInsnNode(fi.name));
        hook.add(new LdcInsnNode(pkg));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, WRITES, "beforeField",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false));
        hook.add(new VarInsnNode(Opcodes.ALOAD, oSlot));
        hook.add(new VarInsnNode(value.getOpcode(Opcodes.ILOAD), vSlot));
        mn.instructions.insertBefore(fi, hook);
        return value.getSize() + 1;
    }

    /** [field, target, value] -> ModWrites.beforeFieldSet(field, target, boxed value, pkg) -> [field, target, value]. */
    private static int fieldSet(MethodNode mn, MethodInsnNode mi, int base, String pkg) {
        Type value = Type.getArgumentTypes(mi.desc)[1];
        int vSlot = base;
        int tSlot = base + value.getSize();
        int fSlot = tSlot + 1;
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(value.getOpcode(Opcodes.ISTORE), vSlot));
        hook.add(new VarInsnNode(Opcodes.ASTORE, tSlot));
        hook.add(new VarInsnNode(Opcodes.ASTORE, fSlot));
        hook.add(new VarInsnNode(Opcodes.ALOAD, fSlot));
        hook.add(new VarInsnNode(Opcodes.ALOAD, tSlot));
        hook.add(new VarInsnNode(value.getOpcode(Opcodes.ILOAD), vSlot));
        box(hook, value);
        hook.add(new LdcInsnNode(pkg));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, WRITES, "beforeFieldSet",
            "(Ljava/lang/reflect/Field;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)V", false));
        hook.add(new VarInsnNode(Opcodes.ALOAD, fSlot));
        hook.add(new VarInsnNode(Opcodes.ALOAD, tSlot));
        hook.add(new VarInsnNode(value.getOpcode(Opcodes.ILOAD), vSlot));
        mn.instructions.insertBefore(mi, hook);
        return value.getSize() + 2;
    }

    /** Parks the call's arguments, reports the first (a path) to ModIO.note, loads them again. */
    private static int noteFirstArg(MethodNode mn, MethodInsnNode mi, int base, String pkg, boolean ctor) {
        Type[] args = Type.getArgumentTypes(mi.desc);
        int[] slots = new int[args.length];
        int next = base;
        for (int i = 0; i < args.length; i++) {
            slots[i] = next;
            next += args[i].getSize();
        }
        InsnList hook = new InsnList();
        for (int i = args.length - 1; i >= 0; i--) {
            hook.add(new VarInsnNode(args[i].getOpcode(Opcodes.ISTORE), slots[i]));
        }
        hook.add(new VarInsnNode(Opcodes.ALOAD, slots[0]));
        hook.add(new LdcInsnNode(pkg));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, IO, "note", "(Ljava/lang/Object;Ljava/lang/String;)V", false));
        for (int i = 0; i < args.length; i++) {
            hook.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD), slots[i]));
        }
        mn.instructions.insertBefore(mi, hook);
        return next - base;
    }

    private static boolean firstArgIs(String desc, String type) {
        Type[] args = Type.getArgumentTypes(desc);
        return args.length > 0 && args[0].getDescriptor().equals(type);
    }

    private static boolean isGame(String owner) {
        for (String p : GAME_PREFIXES) {
            if (owner.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static void box(InsnList list, Type t) {
        String box = switch (t.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.BYTE -> "java/lang/Byte";
            case Type.CHAR -> "java/lang/Character";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float";
            case Type.DOUBLE -> "java/lang/Double";
            default -> null;
        };
        if (box != null) {
            list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, box, "valueOf", "(" + t.getDescriptor() + ")L" + box + ";", false));
        }
    }
}
