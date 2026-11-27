package me.zed_0xff.zombie_buddy.transformers;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Jumpstart fork: the classes that share a nest with a patch class. A patch nested in another class
 * may use that class's private members (javac lets nestmates do that). Its advice runs inside the
 * game class, which is no nestmate: the access fails with an IllegalAccessError, and the crash
 * protection switches the whole mod off. The publicizer makes the patch classes public; it has to
 * make the rest of their nest public too.
 */
public final class PatchNests {
    private static final String PATCH = "me/zed_0xff/zombie_buddy/annotations/Patch;";
    private static final String PATCH_ZB2 = "me/zed_0xff/zombie_buddy/Patch;";

    private PatchNests() {}

    /** Binary names of the classes in a nest with a patch class, the patch classes included. */
    public static Set<String> of(JarContext jctx, List<String> classNames) {
        Set<String> hosts = new HashSet<>();
        for (String className : classNames) {
            byte[] bytes = jctx.getClassBytes(className);
            if (bytes != null) {
                Scan scan = scan(bytes);
                if (scan.patch) {
                    hosts.add(scan.host != null ? scan.host : topLevel(className));
                }
            }
        }

        Set<String> nests = new HashSet<>();
        for (String className : classNames) {
            if (hosts.contains(topLevel(className))) {
                nests.add(className);
            } else {
                byte[] bytes = jctx.getClassBytes(className);
                Scan scan = bytes == null ? null : scan(bytes);
                if (scan != null && scan.host != null && hosts.contains(scan.host)) {
                    nests.add(className);
                }
            }
        }
        return nests;
    }

    /** The outermost class of a binary name: javac names nested classes Outer$Inner, Outer$1. */
    static String topLevel(String className) {
        int dollar = className.indexOf('$', className.lastIndexOf('.') + 1);
        return dollar > 0 ? className.substring(0, dollar) : className;
    }

    private static final class Scan {
        boolean patch;
        String host;
    }

    private static Scan scan(byte[] bytes) {
        Scan scan = new Scan();
        try {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visitNestHost(String nestHost) {
                    scan.host = nestHost.replace('/', '.');
                }

                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (descriptor.endsWith(PATCH) || descriptor.endsWith(PATCH_ZB2)) {
                        scan.patch = true;
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException e) {
            // not a class ASM can read: nothing to publicize, the pipeline reports it
        }
        return scan;
    }
}
