// Universal hook trampoline for AArch64. One global stub preserves every argument
// register (and copies any stack-spilled arguments) across a C++ dispatch, so a single
// code path hooks any IL2CPP signature: any arity, int/long/float/double/struct args,
// static or instance, with argument write-back and return override. Per-slot entry stubs
// set x16 = slot and branch here. Everything lives in .text so Houdini (ARM->x86
// translation) handles it; runtime-generated code in anonymous memory SIGILLs.
//
// Frame (sp grows down): [sp .. sp+0x80) copied stack arguments; [sp+0x80 .. sp+0x1D0)
// the TrampolineCtx (x19 points at it). Original stack arguments sit at [entry_sp ..] where
// entry_sp = sp + 0x1D0; they are copied down so the original method, called with our sp,
// reads the (possibly modified) copies, while the ctx stays above the original's frame.
//
// TrampolineCtx offsets (relative to x19): must match frostbite.cpp.
//   0x00 x0..x8   0x50 q0..q7   0xD0 slot   0xD8 lr
//   0xE0 ret x0,x1   0xF0 ret q0..q3   0x130 stackCopy   0x138 callerX19   0x140 callerX20

#if defined(__aarch64__)
.text
.balign 16
.hidden frostbite_trampoline
frostbite_trampoline:
    sub  sp, sp, #0x1D0
    stp  x19, x20, [sp, #0x00]      // park caller x19/x20 before we claim x19
    add  x19, sp, #0x80            // x19 = ctx base

    stp  x0, x1, [x19, #0x00]
    stp  x2, x3, [x19, #0x10]
    stp  x4, x5, [x19, #0x20]
    stp  x6, x7, [x19, #0x30]
    str  x8,     [x19, #0x40]
    stp  q0, q1, [x19, #0x50]
    stp  q2, q3, [x19, #0x70]
    stp  q4, q5, [x19, #0x90]
    stp  q6, q7, [x19, #0xB0]
    str  x16,    [x19, #0xD0]       // slot
    str  x30,    [x19, #0xD8]       // lr

    ldp  x9, x10, [sp, #0x00]       // recover caller x19/x20
    stp  x9, x10, [x19, #0x138]
    mov  x9, sp
    str  x9, [x19, #0x130]          // stackCopy = sp

    // copy S stack-argument bytes from entry_sp (sp+0x1D0) down to the copy area (sp)
    adrp x11, frostbite_slot_stackbytes
    add  x11, x11, :lo12:frostbite_slot_stackbytes
    ldr  w12, [x11, x16, lsl #2]
    add  x11, sp, #0x1D0
    mov  x9, #0
.Lfb_copy:
    cmp  w9, w12
    b.ge .Lfb_copied
    ldr  x10, [x11, x9]
    str  x10, [sp, x9]
    add  x9, x9, #8
    b    .Lfb_copy
.Lfb_copied:

    ldr  x0, [x19, #0xD0]
    mov  x1, x19
    bl   frostbite_dispatch_head
    cbnz w0, .Lfb_cancel

    ldp  x0, x1, [x19, #0x00]
    ldp  x2, x3, [x19, #0x10]
    ldp  x4, x5, [x19, #0x20]
    ldp  x6, x7, [x19, #0x30]
    ldr  x8,     [x19, #0x40]
    ldp  q0, q1, [x19, #0x50]
    ldp  q2, q3, [x19, #0x70]
    ldp  q4, q5, [x19, #0x90]
    ldp  q6, q7, [x19, #0xB0]

    ldr  x16, [x19, #0xD0]
    adrp x17, frostbite_origin_table
    add  x17, x17, :lo12:frostbite_origin_table
    ldr  x17, [x17, x16, lsl #3]
    blr  x17                        // sp unchanged: the original reads the copied stack args

    stp  x0, x1, [x19, #0xE0]
    stp  q0, q1, [x19, #0xF0]
    stp  q2, q3, [x19, #0x110]
    ldr  x0, [x19, #0xD0]
    mov  x1, x19
    bl   frostbite_dispatch_tail
    ldp  x0, x1, [x19, #0xE0]
    ldp  q0, q1, [x19, #0xF0]
    ldp  q2, q3, [x19, #0x110]

.Lfb_return:
    ldr  x30, [x19, #0xD8]
    ldp  x9, x10, [x19, #0x138]
    add  sp, sp, #0x1D0
    mov  x19, x9
    mov  x20, x10
    ret

.Lfb_cancel:
    ldp  x0, x1, [x19, #0xE0]
    ldp  q0, q1, [x19, #0xF0]
    ldp  q2, q3, [x19, #0x110]
    b    .Lfb_return

.macro FB_SLOT n
.balign 4
frostbite_slot_\n:
    movz x16, #\n
    b    frostbite_trampoline
.endm

FB_SLOT 0
FB_SLOT 1
FB_SLOT 2
FB_SLOT 3
FB_SLOT 4
FB_SLOT 5
FB_SLOT 6
FB_SLOT 7
FB_SLOT 8
FB_SLOT 9
FB_SLOT 10
FB_SLOT 11
FB_SLOT 12
FB_SLOT 13
FB_SLOT 14
FB_SLOT 15
FB_SLOT 16
FB_SLOT 17
FB_SLOT 18
FB_SLOT 19
FB_SLOT 20
FB_SLOT 21
FB_SLOT 22
FB_SLOT 23
FB_SLOT 24
FB_SLOT 25
FB_SLOT 26
FB_SLOT 27
FB_SLOT 28
FB_SLOT 29
FB_SLOT 30
FB_SLOT 31

.data
.balign 8
.global frostbite_slot_entries
.hidden frostbite_slot_entries
frostbite_slot_entries:
    .quad frostbite_slot_0,  frostbite_slot_1,  frostbite_slot_2,  frostbite_slot_3
    .quad frostbite_slot_4,  frostbite_slot_5,  frostbite_slot_6,  frostbite_slot_7
    .quad frostbite_slot_8,  frostbite_slot_9,  frostbite_slot_10, frostbite_slot_11
    .quad frostbite_slot_12, frostbite_slot_13, frostbite_slot_14, frostbite_slot_15
    .quad frostbite_slot_16, frostbite_slot_17, frostbite_slot_18, frostbite_slot_19
    .quad frostbite_slot_20, frostbite_slot_21, frostbite_slot_22, frostbite_slot_23
    .quad frostbite_slot_24, frostbite_slot_25, frostbite_slot_26, frostbite_slot_27
    .quad frostbite_slot_28, frostbite_slot_29, frostbite_slot_30, frostbite_slot_31

.bss
.balign 8
.global frostbite_origin_table
.hidden frostbite_origin_table
frostbite_origin_table:
    .space 8 * 32

.balign 4
.global frostbite_slot_stackbytes
.hidden frostbite_slot_stackbytes
frostbite_slot_stackbytes:
    .space 4 * 32
#endif
