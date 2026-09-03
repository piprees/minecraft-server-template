package com.customdimensions.mixin.compat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Better Caves casts the aquifer sampler to its own duck interface with no
 * check ([T79]). Its {@code AquiferMixin} mixes that interface into
 * {@code AquiferSampler.Impl} only, so every dimension whose noise settings
 * have {@code aquifers_enabled: false} gets the anonymous sea-level sampler
 * instead and the cast throws on every carved chunk.
 *
 * <p>Every string here is written out literally rather than read from the
 * production constants: these are the identifiers the JVM resolves at the
 * {@code checkcast}/{@code invokeinterface} pair, so a rename on either side
 * must fail this test rather than agree with itself.
 *
 * <h2>Measured from the shipped artefacts</h2>
 * <ul>
 *   <li>{@code YungsBetterCaves-1.21.1-Fabric-3.1.5.jar},
 *       {@code worldgen/controller/MasterController.carve}: offset 20
 *       {@code checkcast ILiquidRegionsProvider}, 23 {@code invokeinterface
 *       bettercaves$getLiquidRegions}, 39 {@code ifnull 57}, 61
 *       {@code multianewarray [16][16] BlockState} — a null answer is the
 *       mod's own "this dimension has no liquid regions" path.</li>
 *   <li>{@code duck/ILiquidRegionsProvider} declares that one method
 *       {@code ACC_PUBLIC, ACC_ABSTRACT} with a jspecify {@code @Nullable}
 *       return, so the concrete body has to go on the target, not the
 *       interface.</li>
 *   <li>{@code aquiferfix/AquiferMixin} carries
 *       {@code @Mixin(Lnet/minecraft/class_6350$class_5832;)} — {@code Impl}
 *       alone.</li>
 * </ul>
 */
class BetterCavesAquiferDuckTest {

    private static final String DUCK_INTERFACE =
            "com/yungnickyoung/minecraft/bettercaves/duck/ILiquidRegionsProvider";
    private static final String DUCK_METHOD = "bettercaves$getLiquidRegions";
    private static final String DUCK_DESCRIPTOR =
            "()Lcom/yungnickyoung/minecraft/bettercaves/worldgen/liquidregion/LiquidRegions;";
    private static final String LIQUID_REGIONS =
            "com/yungnickyoung/minecraft/bettercaves/worldgen/liquidregion/LiquidRegions";

    private static final String MOD_ID = "bettercaves";
    private static final String MIXIN_PACKAGE = "com.customdimensions.mixin.compat";
    private static final String MIXIN_SIMPLE_NAME = "BetterCavesAquiferDuckMixin";
    private static final String MIXIN_CLASS = MIXIN_PACKAGE + "." + MIXIN_SIMPLE_NAME;

    private static final String AQUIFER_SAMPLER = "net/minecraft/world/gen/chunk/AquiferSampler";
    private static final String SEA_LEVEL_SAMPLER = AQUIFER_SAMPLER + "$1";
    private static final String AQUIFER_IMPL = AQUIFER_SAMPLER + "$Impl";

    private static final String MIXIN_CONFIG = "customdimensions.compat.mixins.json";

    // ---------------------------------------------------------------- helpers

    private static ClassNode read(String internalName) throws IOException {
        try (InputStream in = BetterCavesAquiferDuckTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, internalName + ".class is not on the test classpath");
            ClassNode node = new ClassNode(Opcodes.ASM9);
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    /** The production gate, driven exactly as Mixin drives it. */
    private static void preApply(ClassNode target, String mixinClass) {
        new CompatMixinPlugin().preApply(
                target.name.replace('/', '.'), target, mixinClass, null);
    }

    private static List<MethodNode> duckMethods(ClassNode node) {
        List<MethodNode> found = new ArrayList<>();
        for (MethodNode m : node.methods) {
            if (DUCK_METHOD.equals(m.name)) {
                found.add(m);
            }
        }
        return found;
    }

    private static List<Integer> realOpcodes(MethodNode method) {
        List<Integer> ops = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() >= 0) {
                ops.add(insn.getOpcode());
            }
        }
        return ops;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> modByMixin() throws Exception {
        Field field = CompatMixinPlugin.class.getDeclaredField("MOD_BY_MIXIN");
        field.setAccessible(true);
        return (Map<String, String>) field.get(null);
    }

    private static JsonObject mixinConfig() throws IOException {
        try (InputStream in =
                BetterCavesAquiferDuckTest.class.getClassLoader().getResourceAsStream(MIXIN_CONFIG)) {
            assertNotNull(in, MIXIN_CONFIG + " is not on the classpath");
            return JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    // ------------------------------------------------- the vanilla shape used

    @Test
    void theVanillaTargetIsAnInterfaceAndBothSamplersImplementIt() throws IOException {
        ClassNode sampler = read(AQUIFER_SAMPLER);
        assertTrue((sampler.access & Opcodes.ACC_INTERFACE) != 0,
                AQUIFER_SAMPLER + " is no longer an interface. The fix adds a SUPERINTERFACE and a "
                        + "DEFAULT method to it; on a class that shape is wrong and Impl's own "
                        + "concrete method would stop overriding cleanly");
        assertEquals(List.of(), duckMethods(sampler),
                AQUIFER_SAMPLER + " already declares " + DUCK_METHOD
                        + " — something else is injecting it and this fix would collide");

        assertTrue(read(SEA_LEVEL_SAMPLER).interfaces.contains(AQUIFER_SAMPLER),
                SEA_LEVEL_SAMPLER + " no longer implements " + AQUIFER_SAMPLER
                        + ". That anonymous class is the one in the ClassCastException; if it no "
                        + "longer inherits from the interface the default method never reaches it");
        ClassNode impl = read(AQUIFER_IMPL);
        assertTrue(impl.interfaces.contains(AQUIFER_SAMPLER),
                AQUIFER_IMPL + " no longer implements " + AQUIFER_SAMPLER);
        assertEquals(0, impl.access & Opcodes.ACC_INTERFACE,
                AQUIFER_IMPL + " is no longer a class. Better Caves installs a concrete "
                        + DUCK_METHOD + " on it, and only a CLASS method is guaranteed to beat an "
                        + "interface default — as an interface it would compete with ours instead");
    }

    // ----------------------------------------------------- the transformation

    @Test
    void preApplyAddsTheDuckInterfaceAndANullReturningDefault() throws IOException {
        ClassNode sampler = read(AQUIFER_SAMPLER);
        List<String> before = List.copyOf(sampler.interfaces);

        preApply(sampler, MIXIN_CLASS);

        assertTrue(sampler.interfaces.contains(DUCK_INTERFACE),
                AQUIFER_SAMPLER + " did not gain " + DUCK_INTERFACE + ". Better Caves' "
                        + "MasterController.carve does an unconditional checkcast to it at offset "
                        + "20 and every carved chunk of an aquifers_enabled=false dimension throws "
                        + "ClassCastException ([T79])");
        assertTrue(sampler.interfaces.containsAll(before),
                "the injection dropped an interface " + AQUIFER_SAMPLER + " already had");

        List<MethodNode> found = duckMethods(sampler);
        assertEquals(1, found.size(),
                "expected exactly one " + DUCK_METHOD + " on " + AQUIFER_SAMPLER
                        + ", found " + found.size());
        MethodNode method = found.get(0);
        assertEquals(DUCK_DESCRIPTOR, method.desc,
                DUCK_METHOD + " has the wrong descriptor. Better Caves resolves it as "
                        + DUCK_DESCRIPTOR + "; any other signature is a different method and the "
                        + "invokeinterface at offset 23 throws AbstractMethodError instead");
        assertTrue((method.access & Opcodes.ACC_PUBLIC) != 0, DUCK_METHOD + " must be public");
        assertEquals(0, method.access & Opcodes.ACC_ABSTRACT,
                DUCK_METHOD + " is abstract. ILiquidRegionsProvider declares it abstract too, so an "
                        + "abstract override inherits nothing and the call throws "
                        + "AbstractMethodError");
        assertEquals(0, method.access & Opcodes.ACC_STATIC,
                DUCK_METHOD + " is static; a static interface method is not inherited");
        assertEquals(List.of(Opcodes.ACONST_NULL, Opcodes.ARETURN), realOpcodes(method),
                DUCK_METHOD + " must return null and nothing else. Better Caves reads a null "
                        + "answer as 'no liquid regions here' and falls through to a fresh "
                        + "[16][16] BlockState array (carve offset 39 ifnull -> 57), which is its "
                        + "own behaviour for a dimension absent from liquidregions.json");
    }

    @Test
    void preApplyIsIdempotent() throws IOException {
        ClassNode sampler = read(AQUIFER_SAMPLER);
        preApply(sampler, MIXIN_CLASS);
        preApply(sampler, MIXIN_CLASS);

        assertEquals(1, sampler.interfaces.stream().filter(DUCK_INTERFACE::equals).count(),
                DUCK_INTERFACE + " was added twice");
        assertEquals(1, duckMethods(sampler).size(),
                DUCK_METHOD + " was added twice, which is a ClassFormatError at load");
    }

    @Test
    void preApplyIgnoresEveryOtherCompatMixin() throws Exception {
        // The gate keys on the MIXIN, exactly as shouldApplyMixin does. A gate
        // that keyed on the target class name instead would break the moment
        // the runtime hands over intermediary names.
        for (String other : modByMixin().keySet()) {
            if (MIXIN_SIMPLE_NAME.equals(other)) {
                continue;
            }
            ClassNode sampler = read(AQUIFER_SAMPLER);
            preApply(sampler, MIXIN_PACKAGE + "." + other);
            assertFalse(sampler.interfaces.contains(DUCK_INTERFACE),
                    other + " triggered the Better Caves injection");
            assertEquals(List.of(), duckMethods(sampler), other + " injected " + DUCK_METHOD);
        }
    }

    // ------------------------------------------------------- the JVM proof

    /**
     * The structural assertions above cannot tell whether the JVM accepts the
     * result. This mirrors the runtime shape in generated bytecode — a duck
     * interface with the real name and descriptor, an interface standing in
     * for {@code AquiferSampler}, and a bare implementor standing in for the
     * anonymous sea-level sampler — puts the mirror through the SAME
     * {@code preApply}, loads it, and does what {@code MasterController.carve}
     * does: cast, then call.
     *
     * <p>The untransformed half is the control: it is [T79] itself, and it
     * must fail the cast or this test proves nothing.
     */
    @Test
    void aBareImplementorIsCastableAndAnswersNullOnlyAfterTheInjection() throws Exception {
        String mirror = "mirror/AquiferSamplerMirror";
        String bare = "mirror/SeaLevelSamplerMirror";
        String owning = "mirror/ImplMirror";

        Map<String, byte[]> untransformed = new HashMap<>();
        untransformed.put(LIQUID_REGIONS, emptyClass(LIQUID_REGIONS));
        untransformed.put(DUCK_INTERFACE, duckInterface());
        untransformed.put(mirror, emptyInterface(mirror));
        untransformed.put(bare, bareImplementor(bare, mirror));
        untransformed.put(owning, implementorWithItsOwnRegions(owning, mirror));

        ClassLoader control = loaderFor(untransformed);
        Class<?> duckBefore = control.loadClass(DUCK_INTERFACE.replace('/', '.'));
        Object before = control.loadClass(bare.replace('/', '.'))
                .getDeclaredConstructor().newInstance();
        assertFalse(duckBefore.isInstance(before),
                "the control is not reproducing T79: a bare implementor of the aquifer interface "
                        + "must NOT be an " + DUCK_INTERFACE + " before the injection, or this "
                        + "test would pass with no fix at all");

        ClassNode mirrorNode = new ClassNode(Opcodes.ASM9);
        new ClassReader(untransformed.get(mirror)).accept(mirrorNode, 0);
        preApply(mirrorNode, MIXIN_CLASS);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        mirrorNode.accept(writer);

        Map<String, byte[]> transformed = new HashMap<>(untransformed);
        transformed.put(mirror, writer.toByteArray());

        ClassLoader fixed = loaderFor(transformed);
        Class<?> duck = fixed.loadClass(DUCK_INTERFACE.replace('/', '.'));
        Object sampler = fixed.loadClass(bare.replace('/', '.'))
                .getDeclaredConstructor().newInstance();
        assertTrue(duck.isInstance(sampler),
                "checkcast to " + DUCK_INTERFACE + " still fails for a class that implements the "
                        + "aquifer interface and nothing else — this is the ClassCastException in "
                        + "T79, on every carved chunk");
        assertNull(assertDoesNotThrow(
                        () -> duck.getMethod(DUCK_METHOD).invoke(sampler),
                        "invoking " + DUCK_METHOD + " on the bare implementor threw. An "
                                + "AbstractMethodError here means the interface was added without "
                                + "an inheritable body"),
                DUCK_METHOD + " must answer null for a sampler that has no liquid regions");

        // Impl's shape: a class with its own concrete body. Java method
        // selection puts a class method ahead of any interface default, which
        // is what leaves the overworld's real liquid regions alone.
        Object impl = fixed.loadClass(owning.replace('/', '.'))
                .getDeclaredConstructor().newInstance();
        Object regions = duck.getMethod(DUCK_METHOD).invoke(impl);
        assertNotNull(regions,
                "an implementor carrying its own " + DUCK_METHOD + " answered null, so the injected "
                        + "default is shadowing it. That is AquiferSampler.Impl, and every "
                        + "dimension Better Caves DOES have liquid regions for silently loses "
                        + "them");
        assertEquals(LIQUID_REGIONS.replace('/', '.'), regions.getClass().getName(),
                "the owning implementor returned something other than its own LiquidRegions");
    }

    // ------------------------------------------------------------ the gating

    @Test
    void everyMixinInTheConfigIsGatedOnAModIdAndBetterCavesIsPinned() throws Exception {
        JsonObject config = mixinConfig();
        assertEquals(MIXIN_PACKAGE, config.get("package").getAsString(),
                "the compat mixin package moved; the gate derives its key from the class name");
        assertEquals("com.customdimensions.mixin.compat.CompatMixinPlugin",
                config.get("plugin").getAsString(),
                "the plugin that performs the gating and the injection is not wired to this config");

        Set<String> listed = new LinkedHashSet<>();
        config.getAsJsonArray("mixins").forEach(e -> listed.add(e.getAsString()));
        assertTrue(listed.contains(MIXIN_SIMPLE_NAME),
                MIXIN_SIMPLE_NAME + " is not in " + MIXIN_CONFIG + "'s mixins array, so Mixin never "
                        + "applies it, preApply is never called, and the injection never happens");

        Map<String, String> gate = modByMixin();
        for (String simple : listed) {
            // The exact chain shouldApplyMixin walks: binary name -> simple name -> mod id.
            String binary = MIXIN_PACKAGE + "." + simple;
            String key = binary.substring(binary.lastIndexOf('.') + 1);
            assertTrue(gate.containsKey(key),
                    simple + " is listed in " + MIXIN_CONFIG + " but names no mod in "
                            + "CompatMixinPlugin, so it is never applied and says so in a WARN");
        }
        assertEquals(listed, gate.keySet(),
                "CompatMixinPlugin and " + MIXIN_CONFIG + " disagree about which compat mixins "
                        + "exist");
        assertEquals(MOD_ID, gate.get(MIXIN_SIMPLE_NAME),
                MIXIN_SIMPLE_NAME + " must be gated on the Fabric mod id " + MOD_ID
                        + " — the string FabricLoader.isModLoaded resolves");
    }

    @Test
    void theInjectionAnnouncesItselfByName() throws IOException {
        // The only runtime evidence that the injection ran is this line. The
        // live check on a booted server greps for the mixin's name in it.
        ClassNode plugin = read("com/customdimensions/mixin/compat/CompatMixinPlugin");
        MethodNode preApply = null;
        for (MethodNode m : plugin.methods) {
            if ("preApply".equals(m.name)) {
                preApply = m;
            }
        }
        assertNotNull(preApply, "CompatMixinPlugin no longer has a preApply to do the injection in");

        // A format string, not just any constant: the gate compares the mixin's
        // simple name too, and matching that would prove nothing about the log.
        boolean logs = false;
        List<String> constants = new ArrayList<>();
        for (AbstractInsnNode insn : preApply.instructions) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text) {
                constants.add(text);
            }
            if (insn instanceof MethodInsnNode call
                    && "org/slf4j/Logger".equals(call.owner) && "info".equals(call.name)) {
                logs = true;
            }
        }
        assertTrue(logs,
                "preApply no longer logs. Nothing else proves on a running server that the "
                        + "interface reached AquiferSampler — the mixin has no injectors, so a "
                        + "silent non-application looks identical to a working one");
        assertTrue(constants.stream()
                        .anyMatch(text -> text.contains(MIXIN_SIMPLE_NAME) && text.contains("{}")),
                "no log format in preApply names " + MIXIN_SIMPLE_NAME + ". That name is what the "
                        + "post-deploy check greps for; the constants present are " + constants);
    }

    @Test
    void theMixinTargetsAquiferSamplerAndIsAnInterfaceMixin() throws IOException {
        ClassNode mixin = read(MIXIN_CLASS.replace('.', '/'));
        assertTrue((mixin.access & Opcodes.ACC_INTERFACE) != 0,
                MIXIN_SIMPLE_NAME + " must be an interface: Mixin refuses a class mixin on an "
                        + "interface target");

        List<AnnotationNode> annotations = new ArrayList<>();
        if (mixin.invisibleAnnotations != null) {
            annotations.addAll(mixin.invisibleAnnotations);
        }
        if (mixin.visibleAnnotations != null) {
            annotations.addAll(mixin.visibleAnnotations);
        }
        List<Type> targets = new ArrayList<>();
        for (AnnotationNode annotation : annotations) {
            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(annotation.desc)
                    || annotation.values == null) {
                continue;
            }
            for (int i = 0; i < annotation.values.size(); i += 2) {
                if ("value".equals(annotation.values.get(i))) {
                    for (Object t : (List<?>) annotation.values.get(i + 1)) {
                        targets.add((Type) t);
                    }
                }
            }
        }
        assertEquals(List.of(Type.getObjectType(AQUIFER_SAMPLER)), targets,
                MIXIN_SIMPLE_NAME + " must target exactly " + AQUIFER_SAMPLER + ". preApply keys "
                        + "on the mixin name, so retargeting it silently moves the injection onto "
                        + "another class");
    }

    // -------------------------------------------- generated mirror bytecode

    private static byte[] emptyInterface(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] duckInterface() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                DUCK_INTERFACE, null, "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                DUCK_METHOD, DUCK_DESCRIPTOR, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] emptyClass(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                internalName, null, "java/lang/Object", null);
        writeConstructor(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] bareImplementor(String internalName, String iface) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                internalName, null, "java/lang/Object", new String[] {iface});
        writeConstructor(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Impl's shape: implements the interface AND carries its own concrete body. */
    private static byte[] implementorWithItsOwnRegions(String internalName, String iface) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                internalName, null, "java/lang/Object", new String[] {iface});
        writeConstructor(cw);
        org.objectweb.asm.MethodVisitor own =
                cw.visitMethod(Opcodes.ACC_PUBLIC, DUCK_METHOD, DUCK_DESCRIPTOR, null, null);
        own.visitCode();
        own.visitTypeInsn(Opcodes.NEW, LIQUID_REGIONS);
        own.visitInsn(Opcodes.DUP);
        own.visitMethodInsn(Opcodes.INVOKESPECIAL, LIQUID_REGIONS, "<init>", "()V", false);
        own.visitInsn(Opcodes.ARETURN);
        own.visitMaxs(2, 1);
        own.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void writeConstructor(ClassWriter cw) {
        org.objectweb.asm.MethodVisitor init =
                cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
    }

    /**
     * One loader per set, so the duck interface the cast resolves and the one
     * the implementor inherits are the same runtime class. Two loaders would
     * make {@code isInstance} false whatever the bytecode said.
     */
    private static ClassLoader loaderFor(Map<String, byte[]> classes) {
        return new ClassLoader(BetterCavesAquiferDuckTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }
}
