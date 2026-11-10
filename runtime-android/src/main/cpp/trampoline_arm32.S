// Universal hook trampoline for ARM (armeabi-v7a, softfp). Under the Android softfp ABI
// every argument (int, float, double, struct) is passed in r0-r3 then on the stack, so
// there is no separate FP-register handling. Per-slot entry stubs set r12 = slot and
// branch here. The origin pointer and stack-argument size are looked up by a C helper
// (frostbite_prepare32) so this code references no global symbols -> position-independent,
// no text relocations, and the library loads cleanly even where this path is unexercised.
//
// UNTESTED: validated by inspection only; this project's emulator runs the arm64 library.
//
// Frame (sp grows down): [sp .. sp+0x80) copied stack arguments; [sp+0x80 .. sp+0xC0) the
// TrampolineCtx (r4 points at it). Original stack arguments are at entry_sp = sp + 0xC0.
//
// TrampolineCtx offsets (relative to r4), must match frostbite.cpp:
//   0x00 r0..r3   0x10 slot   0x14 lr   0x18 ret r0,r1   0x20 stackCopy
//   0x24 callerR4   0x28 callerR5   0x2C origin

#if defined(__arm__)
.text
.syntax unified
.arm
.balign 4
.hidden frostbite_trampoline
frostbite_trampoline:
    sub  sp, sp, #0xC0
    str  r4, [sp, #0x00]            // park caller r4/r5 before we claim them
    str  r5, [sp, #0x04]
    add  r4, sp, #0x80             // r4 = ctx base
    stm  r4, {r0-r3}               // save r0..r3
    str  r12, [r4, #0x10]          // slot
    str  lr,  [r4, #0x14]          // lr
    ldr  r5, [sp, #0x00]
    str  r5, [r4, #0x24]           // caller r4
    ldr  r5, [sp, #0x04]
    str  r5, [r4, #0x28]           // caller r5
    mov  r5, sp
    str  r5, [r4, #0x20]           // stackCopy = sp

    ldr  r0, [r4, #0x10]           // slot
    bl   frostbite_prepare32       // -> r0 = origin, r1 = stack byte count
    str  r0, [r4, #0x2C]           // origin

    add  r2, sp, #0xC0             // entry_sp (source of original stack args)
    mov  r3, #0
.Lfb32_copy:
    cmp  r3, r1
    bge  .Lfb32_copied
    ldr  r12, [r2, r3]
    str  r12, [sp, r3]
    add  r3, r3, #4
    b    .Lfb32_copy
.Lfb32_copied:

    ldr  r0, [r4, #0x10]
    mov  r1, r4
    bl   frostbite_dispatch_head
    cmp  r0, #0
    bne  .Lfb32_cancel

    ldm  r4, {r0-r3}              // restore (possibly modified) args
    ldr  r12, [r4, #0x2C]
    blx  r12                       // call original; sp unchanged -> it reads the copied stack args
    str  r0, [r4, #0x18]
    str  r1, [r4, #0x1C]
    ldr  r0, [r4, #0x10]
    mov  r1, r4
    bl   frostbite_dispatch_tail
    ldr  r0, [r4, #0x18]
    ldr  r1, [r4, #0x1C]

.Lfb32_ret:
    ldr  lr, [r4, #0x14]
    ldr  r5, [r4, #0x28]
    ldr  r4, [r4, #0x24]          // restore caller r4 last (overwrites ctx base)
    add  sp, sp, #0xC0
    bx   lr

.Lfb32_cancel:
    ldr  r0, [r4, #0x18]
    ldr  r1, [r4, #0x1C]
    b    .Lfb32_ret

.macro FB32_SLOT n
.balign 4
frostbite_slot_\n:
    mov  r12, #\n
    b    frostbite_trampoline
.endm

FB32_SLOT 0
FB32_SLOT 1
FB32_SLOT 2
FB32_SLOT 3
FB32_SLOT 4
FB32_SLOT 5
FB32_SLOT 6
FB32_SLOT 7
FB32_SLOT 8
FB32_SLOT 9
FB32_SLOT 10
FB32_SLOT 11
FB32_SLOT 12
FB32_SLOT 13
FB32_SLOT 14
FB32_SLOT 15
FB32_SLOT 16
FB32_SLOT 17
FB32_SLOT 18
FB32_SLOT 19
FB32_SLOT 20
FB32_SLOT 21
FB32_SLOT 22
FB32_SLOT 23
FB32_SLOT 24
FB32_SLOT 25
FB32_SLOT 26
FB32_SLOT 27
FB32_SLOT 28
FB32_SLOT 29
FB32_SLOT 30
FB32_SLOT 31

.data
.balign 4
.global frostbite_slot_entries
.hidden frostbite_slot_entries
frostbite_slot_entries:
    .word frostbite_slot_0,  frostbite_slot_1,  frostbite_slot_2,  frostbite_slot_3
    .word frostbite_slot_4,  frostbite_slot_5,  frostbite_slot_6,  frostbite_slot_7
    .word frostbite_slot_8,  frostbite_slot_9,  frostbite_slot_10, frostbite_slot_11
    .word frostbite_slot_12, frostbite_slot_13, frostbite_slot_14, frostbite_slot_15
    .word frostbite_slot_16, frostbite_slot_17, frostbite_slot_18, frostbite_slot_19
    .word frostbite_slot_20, frostbite_slot_21, frostbite_slot_22, frostbite_slot_23
    .word frostbite_slot_24, frostbite_slot_25, frostbite_slot_26, frostbite_slot_27
    .word frostbite_slot_28, frostbite_slot_29, frostbite_slot_30, frostbite_slot_31

.bss
.balign 4
.global frostbite_origin_table
.hidden frostbite_origin_table
frostbite_origin_table:
    .space 4 * 32

.global frostbite_slot_stackbytes
.hidden frostbite_slot_stackbytes
frostbite_slot_stackbytes:
    .space 4 * 32
#endif
