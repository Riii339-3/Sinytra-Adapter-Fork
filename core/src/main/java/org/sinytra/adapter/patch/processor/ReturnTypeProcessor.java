package org.sinytra.adapter.patch.processor;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;
import org.sinytra.adapter.env.ctx.MixinContext;
import org.sinytra.adapter.patch.Recipe;
import org.sinytra.adapter.patch.TxResult;
import org.sinytra.adapter.patch.config.Configuration;
import org.sinytra.adapter.analysis.method.MethodAnalyzer;
import org.sinytra.adapter.types.TypeAdapter;
import org.sinytra.adapter.util.AdapterUtil;

import java.util.ArrayList;
import java.util.List;

public class ReturnTypeProcessor implements Processor {
    @Override
    public TxResult process(MixinContext context, Configuration dirty, Recipe recipe) {
        Type cleanType = recipe.clean().getReturnType();
        Type dirtyType = dirty.getReturnType();

        if (cleanType.equals(dirtyType)) {
            return TxResult.PASS;
        }

        // Converting a handler to a void return type, e.g. when a @WrapOperation
        // handler wraps a call whose return type changed (Map.put returning Object
        // -> KeyMappingLookup.put returning void). There is no TypeAdapter from a
        // value type to void, so drop the returned value and return void instead.
        // The value-producing instructions are kept, so side effects (such as
        // calling the wrapped method) are preserved.
        if (Type.VOID_TYPE.equals(dirtyType)) {
            MethodNode methodNode = context.methodNode();
            boolean changed = false;
            for (AbstractInsnNode insn : methodNode.instructions.toArray()) {
                int popOpcode = switch (insn.getOpcode()) {
                    case Opcodes.ARETURN, Opcodes.IRETURN, Opcodes.FRETURN -> Opcodes.POP;
                    case Opcodes.LRETURN, Opcodes.DRETURN -> Opcodes.POP2;
                    default -> -1;
                };
                if (popOpcode == -1) {
                    continue;
                }
                methodNode.instructions.insertBefore(insn, new InsnNode(popOpcode));
                methodNode.instructions.set(insn, new InsnNode(Opcodes.RETURN));
                changed = true;
            }
            if (!changed) {
                return TxResult.PASS;
            }
            methodNode.desc = Type.getMethodDescriptor(dirtyType, Type.getArgumentTypes(methodNode.desc));
            return TxResult.SUCCESS;
        }

        TypeAdapter adapter = context.getTypeAdapter(cleanType, dirtyType);
        if (adapter == null) {
            return TxResult.PASS;
        }

        ReturnInterpreter inter = MethodAnalyzer.analyzeInterpretMethod(context.methodNode(), new ReturnInterpreter());
        for (AbstractInsnNode insn : inter.insns) {
            if (insn.getOpcode() != Opcodes.ACONST_NULL) {
                adapter.apply(context.methodNode().instructions, insn);
            }
        }

        MethodNode methodNode = context.methodNode();
        methodNode.desc = Type.getMethodDescriptor(dirtyType, Type.getArgumentTypes(methodNode.desc));

        return TxResult.SUCCESS;
    }

    private static class ReturnInterpreter extends SourceInterpreter {
        public final List<AbstractInsnNode> insns = new ArrayList<>();

        public ReturnInterpreter() {
            super(Opcodes.ASM9);
        }

        @Override
        public void returnOperation(AbstractInsnNode insn, SourceValue value, SourceValue expected) {
            AbstractInsnNode srcInsn = AdapterUtil.getSingleInsn(value);
            if (srcInsn == null) {
                throw new IllegalStateException();
            }
            this.insns.add(srcInsn);
        }
    }
}
