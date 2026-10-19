package dev.frostbite.runtime;

/** Metadata supplied by the eventual IL2CPP adapter; address is the native method entrypoint. */
public record ResolvedMethod(String assembly, String namespace, String targetClass,
                             String signature, String returnType, String abi, long address) {}
